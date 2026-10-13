package com.mymoney.core.domain.rules

import com.mymoney.core.domain.common.AmountProblem
import com.mymoney.core.domain.common.DomainError
import com.mymoney.core.domain.model.Account
import com.mymoney.core.domain.model.Category
import com.mymoney.core.domain.model.CategoryType
import com.mymoney.core.domain.model.Entry
import com.mymoney.core.domain.model.EntryGroup
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.model.SystemCategories
import com.mymoney.core.domain.money.MoneyMath
import java.time.LocalDate

/** What the validator needs to know about a refunded expense (FBR-08). */
data class RefundTarget(
    val original: Entry,
    /** Sum of refunds already linked to [original], excluding the group being validated. */
    val alreadyRefundedMinor: Long,
)

/**
 * Validates an [EntryGroup] against docs/03 §3.6 and docs/04 before it is saved.
 * Returns the first problem found, or null when the group is valid.
 */
class LedgerValidator(
    private val accounts: Map<String, Account>,
    private val categories: Map<String, Category>,
    private val refundTarget: (entryId: String) -> RefundTarget? = { null },
) {
    fun validate(group: EntryGroup): DomainError? {
        group.entries.forEach { e -> validateEntry(e)?.let { return it } }
        return validateComposition(group)
    }

    private fun validateEntry(e: Entry): DomainError? {
        val account = accounts[e.accountId] ?: return DomainError.NotFound("account", e.accountId)
        MoneyMath.validateAmount(e.amountMinor)?.let { return it }
        when (EntryClassification.sign(e.kind)) {
            EntryClassification.Sign.POSITIVE -> if (e.amountMinor < 0) return DomainError.InvalidAmount(AmountProblem.NEGATIVE_NOT_ALLOWED)
            EntryClassification.Sign.NEGATIVE -> if (e.amountMinor > 0) return DomainError.InvalidAmount(AmountProblem.POSITIVE_NOT_ALLOWED)
            EntryClassification.Sign.NON_ZERO -> Unit
        }
        if (e.date.isBefore(MIN_DATE) || e.date.isAfter(MAX_DATE)) return DomainError.InvalidDate("out of range")
        if (e.timeMinutes != null && e.timeMinutes !in 0..1439) return DomainError.InvalidDate("time")

        if (e.kind == EntryKind.WRITE_OFF && !account.type.isObligation) {
            return DomainError.RuleViolation("FBR-32", "write-off only on debt or receivable accounts")
        }
        if (e.kind != EntryKind.EXPENSE && (e.costType != null || e.essential != null)) {
            return DomainError.RuleViolation("FBR-38", "cost type only applies to expenses")
        }
        return validateCategory(e)
    }

    private fun validateCategory(e: Entry): DomainError? {
        if (!EntryClassification.requiresCategory(e.kind)) {
            return if (e.categoryId != null || e.splits.isNotEmpty()) {
                DomainError.RuleViolation("FBR-02", "${e.kind} must not have a category")
            } else {
                null
            }
        }
        val expectedType = if (e.kind == EntryKind.INCOME) CategoryType.INCOME else CategoryType.EXPENSE
        if (e.splits.isEmpty()) {
            val id = e.categoryId ?: return DomainError.Required("category")
            val cat = categories[id] ?: return DomainError.NotFound("category", id)
            if (cat.type != expectedType) return DomainError.RuleViolation("FBR-01", "category type mismatch")
            return null
        }
        if (e.categoryId != null) return DomainError.RuleViolation("FBR-14", "split entry must not also have a category")
        if (e.splits.size < 2) return DomainError.RuleViolation("FBR-14", "a split needs at least two parts")
        e.splits.forEach { s ->
            if (s.amountMinor <= 0) return DomainError.InvalidAmount(AmountProblem.ZERO)
            val cat = categories[s.categoryId] ?: return DomainError.NotFound("category", s.categoryId)
            if (cat.type != expectedType) return DomainError.RuleViolation("FBR-01", "split category type mismatch")
        }
        val total = try {
            MoneyMath.sum(e.splits.map { it.amountMinor })
        } catch (_: ArithmeticException) {
            return DomainError.Overflow
        }
        if (total != Math.abs(e.amountMinor)) return DomainError.RuleViolation("FBR-14", "split parts must add up to the amount")
        return null
    }

    private fun validateComposition(group: EntryGroup): DomainError? {
        val entries = group.entries
        val transfers = entries.filter { it.kind == EntryKind.TRANSFER }
        if (transfers.isNotEmpty()) return validateTransferGroup(entries, transfers)

        if (entries.size != 1) {
            // Multi-leg groups other than transfers arrive with assets/debts in stage 4.
            return DomainError.RuleViolation("FBR-13", "unsupported multi-entry group")
        }
        val e = entries.single()
        if (e.kind == EntryKind.REFUND && e.relatedEntryId != null) return validateRefund(e)
        return null
    }

    private fun validateTransferGroup(entries: List<Entry>, transfers: List<Entry>): DomainError? {
        if (transfers.size != 2) return DomainError.RuleViolation("FBR-16", "a transfer has exactly two legs")
        val out = transfers.firstOrNull { it.amountMinor < 0 }
        val inn = transfers.firstOrNull { it.amountMinor > 0 }
        if (out == null || inn == null) return DomainError.RuleViolation("FBR-16", "a transfer needs one outgoing and one incoming leg")
        if (out.accountId == inn.accountId) return DomainError.RuleViolation("FBR-16", "cannot transfer to the same account")
        if (out.date != inn.date || out.status != inn.status) {
            return DomainError.RuleViolation("FBR-16", "transfer legs must share date and status")
        }
        val fromCurrency = accounts.getValue(out.accountId).currency
        val toCurrency = accounts.getValue(inn.accountId).currency
        if (fromCurrency == toCurrency && -out.amountMinor != inn.amountMinor) {
            return DomainError.RuleViolation("FBR-16", "same-currency transfer legs must be equal")
        }
        val others = entries - transfers.toSet()
        if (others.size > 1) return DomainError.RuleViolation("FBR-03", "at most one fee or interest leg")
        others.singleOrNull()?.let { extra ->
            if (extra.kind != EntryKind.EXPENSE) return DomainError.RuleViolation("FBR-03", "extra leg must be an expense")
            val key = extra.categoryId?.let { categories[it]?.systemKey }
            if (key != SystemCategories.FEES && key != SystemCategories.INTEREST) {
                return DomainError.RuleViolation("FBR-03", "extra leg must use the fees or interest category")
            }
            if (extra.accountId != out.accountId && extra.accountId != inn.accountId) {
                return DomainError.RuleViolation("FBR-03", "fee must be charged to one of the transfer accounts")
            }
        }
        return null
    }

    private fun validateRefund(e: Entry): DomainError? {
        val target = refundTarget(e.relatedEntryId!!) ?: return DomainError.NotFound("entry", e.relatedEntryId)
        if (target.original.kind != EntryKind.EXPENSE) return DomainError.RuleViolation("FBR-08", "only expenses can be refunded")
        val originalCurrency = accounts[target.original.accountId]?.currency
        val refundCurrency = accounts[e.accountId]?.currency
        if (originalCurrency != refundCurrency) {
            return DomainError.RuleViolation("FBR-08", "a linked refund must be in the original currency")
        }
        val refundable = Math.abs(target.original.amountMinor) - target.alreadyRefundedMinor
        if (e.amountMinor > refundable) return DomainError.RuleViolation("FBR-08", "refund exceeds the refundable amount")
        return null
    }

    companion object {
        val MIN_DATE: LocalDate = LocalDate.of(1900, 1, 1)
        val MAX_DATE: LocalDate = LocalDate.of(2200, 12, 31)
    }
}
