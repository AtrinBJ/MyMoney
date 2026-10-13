package com.mymoney.core.domain.usecase

import com.mymoney.core.domain.common.DomainError
import com.mymoney.core.domain.common.IdGenerator
import com.mymoney.core.domain.common.Outcome
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.common.failure
import com.mymoney.core.domain.common.success
import com.mymoney.core.domain.model.EntryGroup
import com.mymoney.core.domain.model.ExchangeRate
import com.mymoney.core.domain.model.RateSource
import com.mymoney.core.domain.model.SystemCategories
import com.mymoney.core.domain.money.MoneyMath
import com.mymoney.core.domain.repository.AccountRepository
import com.mymoney.core.domain.repository.CategoryRepository
import com.mymoney.core.domain.repository.CurrencyRepository
import com.mymoney.core.domain.repository.ExchangeRateRepository
import com.mymoney.core.domain.repository.LedgerRepository
import com.mymoney.core.domain.rules.LedgerValidator
import com.mymoney.core.domain.rules.RefundTarget
import java.math.MathContext
import java.math.RoundingMode
import javax.inject.Inject

enum class RecordResult { SAVED, NO_CHANGE }

/**
 * Builds, validates and atomically saves (or replaces, when editing) one operation.
 * The draft's groupId makes a double submit harmless (FBR-13).
 */
class RecordOperationUseCase @Inject constructor(
    private val accounts: AccountRepository,
    private val categories: CategoryRepository,
    private val currencies: CurrencyRepository,
    private val ledger: LedgerRepository,
    private val rates: ExchangeRateRepository,
    private val ids: IdGenerator,
    private val clock: TimeProvider,
) {
    suspend operator fun invoke(draft: OperationDraft): Outcome<RecordResult> {
        val builder = OperationBuilder(ids, clock.nowMillis())
        val existing = ledger.getGroup(draft.groupId)

        var refundTarget: RefundTarget? = null
        var reason: String? = null
        val group: EntryGroup = when (draft) {
            is IncomeExpenseDraft -> builder.incomeExpense(draft)
            is TransferDraft -> {
                val key = if (draft.extraLeg == ExtraLeg.INTEREST) SystemCategories.INTEREST else SystemCategories.FEES
                builder.transfer(draft, categories.getBySystemKey(key)?.id)
            }
            is RefundDraft -> {
                val original = draft.originalEntryId?.let { ledger.getEntry(it) }
                if (draft.originalEntryId != null && original == null) {
                    return DomainError.NotFound("entry", draft.originalEntryId).failure()
                }
                if (original != null) {
                    refundTarget = RefundTarget(original, ledger.refundedTotal(original.id, excludingGroupId = draft.groupId))
                }
                builder.refund(draft, original)
            }
            is AdjustmentDraft -> {
                if (draft.reason.isBlank()) return DomainError.Required("reason").failure()
                reason = draft.reason.trim()
                // When editing an adjustment, its own previous leg must not count toward the computed balance.
                val ownPrevious = existing?.entries?.filter { it.accountId == draft.accountId && it.date <= draft.date }
                    ?.sumOf { it.amountMinor } ?: 0L
                val computed = MoneyMath.subtract(accounts.balance(draft.accountId, upTo = draft.date), ownPrevious)
                builder.adjustment(draft, computed) ?: return RecordResult.NO_CHANGE.success()
            }
        }

        val accountMap = accounts.getAll().associateBy { it.id }
        val categoryMap = categories.getAll().associateBy { it.id }
        val validator = LedgerValidator(accountMap, categoryMap) { id ->
            refundTarget?.takeIf { it.original.id == id }
        }
        validator.validate(group)?.let { return it.failure() }

        val saved = if (existing == null) ledger.insertGroup(group, reason) else ledger.replaceGroup(group, reason)
        if (saved is Outcome.Failure) {
            return if (saved.error == DomainError.AlreadySaved) RecordResult.SAVED.success() else saved
        }

        if (draft is TransferDraft) recordImpliedRate(draft, accountMap.mapValues { it.value.currency })
        return RecordResult.SAVED.success()
    }

    /** A cross-currency transfer reveals the rate actually obtained; keep it as a rate source (FBR-03). */
    private suspend fun recordImpliedRate(d: TransferDraft, currencyOf: Map<String, String>) {
        val from = currencyOf[d.fromAccountId] ?: return
        val to = currencyOf[d.toAccountId] ?: return
        if (from == to || d.amountOutMinor <= 0 || d.amountInMinor <= 0) return
        val fromDigits = currencies.get(from)?.minorDigits ?: return
        val toDigits = currencies.get(to)?.minorDigits ?: return
        val outMajor = MoneyMath.toMajor(d.amountOutMinor, fromDigits)
        val inMajor = MoneyMath.toMajor(d.amountInMinor, toDigits)
        val rate = inMajor.divide(outMajor, MathContext(18, RoundingMode.HALF_EVEN))
        rates.save(
            ExchangeRate(
                id = ids.newId(),
                base = from,
                quote = to,
                rate = rate.stripTrailingZeros(),
                date = d.date,
                source = RateSource.TRANSFER_IMPLIED,
                note = d.groupId,
                createdAt = clock.nowMillis(),
            ),
        )
    }
}
