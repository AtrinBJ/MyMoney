package com.mymoney.core.domain.usecase

import com.mymoney.core.domain.common.IdGenerator
import com.mymoney.core.domain.model.Entry
import com.mymoney.core.domain.model.EntryGroup
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.model.EntrySplit
import com.mymoney.core.domain.money.MoneyMath

/**
 * Turns form drafts into signed ledger legs (docs/03 §3.6). Pure: ids and timestamps are supplied,
 * so the same draft always produces the same economic result.
 */
class OperationBuilder(
    private val ids: IdGenerator,
    private val nowMillis: Long,
) {
    fun incomeExpense(d: IncomeExpenseDraft): EntryGroup {
        val sign = if (d.kind == EntryKind.EXPENSE) -1 else 1
        val entry = Entry(
            id = ids.newId(),
            groupId = d.groupId,
            accountId = d.accountId,
            kind = d.kind,
            amountMinor = sign * d.amountMinor,
            date = d.date,
            timeMinutes = d.timeMinutes,
            status = d.status,
            categoryId = if (d.splits.isEmpty()) d.categoryId else null,
            payee = d.payee.clean(),
            note = d.note.clean(),
            costType = if (d.kind == EntryKind.EXPENSE) d.costType else null,
            essential = if (d.kind == EntryKind.EXPENSE) d.essential else null,
            recurringRuleId = d.recurringRuleId,
            occurrenceDate = d.occurrenceDate,
            createdAt = nowMillis,
            updatedAt = nowMillis,
            splits = d.splits.map { EntrySplit(ids.newId(), it.categoryId, it.amountMinor, it.note.clean()) },
            tagIds = d.tagIds,
        )
        return EntryGroup(d.groupId, listOf(entry))
    }

    /**
     * Two TRANSFER legs (out negative, in positive) plus an optional fee EXPENSE leg (FBR-03, FBR-16).
     * [feeCategoryId] is the protected `expense.fees` category.
     */
    fun transfer(d: TransferDraft, feeCategoryId: String?): EntryGroup {
        val base = Entry(
            id = "",
            groupId = d.groupId,
            accountId = "",
            kind = EntryKind.TRANSFER,
            amountMinor = 0,
            date = d.date,
            timeMinutes = d.timeMinutes,
            status = d.status,
            note = d.note.clean(),
            createdAt = nowMillis,
            updatedAt = nowMillis,
            tagIds = d.tagIds,
        )
        val legs = mutableListOf(
            base.copy(id = ids.newId(), accountId = d.fromAccountId, amountMinor = -d.amountOutMinor),
            base.copy(id = ids.newId(), accountId = d.toAccountId, amountMinor = d.amountInMinor),
        )
        if (d.feeMinor > 0) {
            legs += base.copy(
                id = ids.newId(),
                accountId = d.feeAccountId ?: d.fromAccountId,
                kind = EntryKind.EXPENSE,
                amountMinor = -d.feeMinor,
                categoryId = feeCategoryId,
                tagIds = emptySet(),
            )
        }
        return EntryGroup(d.groupId, legs)
    }

    /** A refund inherits the original's category; a split original is refunded proportionally (FBR-08). */
    fun refund(d: RefundDraft, original: Entry?): EntryGroup {
        var categoryId = d.categoryId
        var splits = emptyList<EntrySplit>()
        if (original != null) {
            if (original.splits.isEmpty()) {
                categoryId = original.categoryId
            } else {
                val parts = MoneyMath.allocate(d.amountMinor, original.splits.map { it.amountMinor })
                val nonZero = original.splits.zip(parts).filter { it.second > 0 }
                if (nonZero.size == 1) {
                    categoryId = nonZero.single().first.categoryId
                } else {
                    categoryId = null
                    splits = nonZero.map { (s, p) -> EntrySplit(ids.newId(), s.categoryId, p) }
                }
            }
        }
        val entry = Entry(
            id = ids.newId(),
            groupId = d.groupId,
            accountId = d.accountId,
            kind = EntryKind.REFUND,
            amountMinor = d.amountMinor,
            date = d.date,
            timeMinutes = d.timeMinutes,
            status = d.status,
            categoryId = categoryId,
            payee = d.payee.clean() ?: original?.payee,
            note = d.note.clean(),
            relatedEntryId = original?.id,
            createdAt = nowMillis,
            updatedAt = nowMillis,
            splits = splits,
            tagIds = d.tagIds,
        )
        return EntryGroup(d.groupId, listOf(entry))
    }

    /** One ADJUSTMENT leg for the difference between the real and the computed balance (FBR-15). */
    fun adjustment(d: AdjustmentDraft, computedBalanceMinor: Long): EntryGroup? {
        val diff = MoneyMath.subtract(d.actualBalanceMinor, computedBalanceMinor)
        if (diff == 0L) return null
        val entry = Entry(
            id = ids.newId(),
            groupId = d.groupId,
            accountId = d.accountId,
            kind = EntryKind.ADJUSTMENT,
            amountMinor = diff,
            date = d.date,
            note = listOfNotNull(d.reason.clean(), d.note.clean()).joinToString(" — "),
            createdAt = nowMillis,
            updatedAt = nowMillis,
        )
        return EntryGroup(d.groupId, listOf(entry))
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
