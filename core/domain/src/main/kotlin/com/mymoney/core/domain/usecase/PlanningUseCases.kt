package com.mymoney.core.domain.usecase

import com.mymoney.core.domain.calc.AssetCalculator
import com.mymoney.core.domain.calc.OversellException
import com.mymoney.core.domain.calc.Recurrence
import com.mymoney.core.domain.common.AmountProblem
import com.mymoney.core.domain.common.DomainError
import com.mymoney.core.domain.common.IdGenerator
import com.mymoney.core.domain.common.Outcome
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.common.failure
import com.mymoney.core.domain.common.success
import com.mymoney.core.domain.model.Account
import com.mymoney.core.domain.model.AccountType
import com.mymoney.core.domain.model.Asset
import com.mymoney.core.domain.model.AssetTrade
import com.mymoney.core.domain.model.AssetValuation
import com.mymoney.core.domain.model.Entry
import com.mymoney.core.domain.model.EntryGroup
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.model.GoalContribution
import com.mymoney.core.domain.model.GoalMode
import com.mymoney.core.domain.model.Obligation
import com.mymoney.core.domain.model.ObligationDirection
import com.mymoney.core.domain.model.ObligationStatus
import com.mymoney.core.domain.model.OccurrenceState
import com.mymoney.core.domain.model.OccurrenceStatus
import com.mymoney.core.domain.model.RecurringRule
import com.mymoney.core.domain.model.RuleKind
import com.mymoney.core.domain.model.TradeType
import com.mymoney.core.domain.model.ValuationSource
import com.mymoney.core.domain.money.MoneyMath
import com.mymoney.core.domain.repository.AccountRepository
import com.mymoney.core.domain.repository.AssetRepository
import com.mymoney.core.domain.repository.CurrencyRepository
import com.mymoney.core.domain.repository.GoalRepository
import com.mymoney.core.domain.repository.LedgerRepository
import com.mymoney.core.domain.repository.ObligationRepository
import com.mymoney.core.domain.repository.RecurringRepository
import com.mymoney.core.domain.repository.TransactionRunner
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import javax.inject.Inject

/**
 * Confirms a due item (FBR-12, FBR-27): records the real entry linked to (rule, due date) and updates the
 * occurrence state in one transaction. Partial payments are allowed; the state becomes PAID once the
 * recorded amount reaches the expected amount.
 */
class PayOccurrenceUseCase @Inject constructor(
    private val recurring: RecurringRepository,
    private val record: RecordOperationUseCase,
    private val tx: TransactionRunner,
    private val clock: TimeProvider,
) {
    /**
     * [amountMinor] is what is paid now (positive, in the paying account's currency). For loan installments,
     * [interestMinor] is the interest part (expense); the rest reduces the debt (FBR-04).
     */
    suspend operator fun invoke(
        rule: RecurringRule,
        dueDate: LocalDate,
        groupId: String,
        amountMinor: Long,
        paidOn: LocalDate,
        interestMinor: Long = 0,
        note: String? = null,
    ): Outcome<Unit> = tx.run {
        val draft: OperationDraft = when (rule.kind) {
            RuleKind.INCOME -> IncomeExpenseDraft(
                groupId, EntryKind.INCOME, rule.accountId, amountMinor, paidOn, rule.categoryId,
                payee = rule.payee ?: rule.name, note = note, recurringRuleId = rule.id, occurrenceDate = dueDate,
            )
            RuleKind.TRANSFER -> TransferDraft(
                groupId, rule.accountId, rule.toAccountId ?: return@run DomainError.Required("account").failure(),
                amountMinor, amountMinor, paidOn, note = note ?: rule.name, recurringRuleId = rule.id, occurrenceDate = dueDate,
            )
            RuleKind.LOAN_INSTALLMENT -> {
                val principal = amountMinor - interestMinor
                if (principal <= 0) return@run DomainError.InvalidAmount(AmountProblem.ZERO).failure()
                TransferDraft(
                    groupId, rule.accountId, rule.toAccountId ?: return@run DomainError.Required("account").failure(),
                    principal, principal, paidOn, feeMinor = interestMinor, extraLeg = ExtraLeg.INTEREST,
                    note = note ?: rule.name, recurringRuleId = rule.id, occurrenceDate = dueDate,
                )
            }
            RuleKind.BILL, RuleKind.SUBSCRIPTION, RuleKind.OTHER -> IncomeExpenseDraft(
                groupId, EntryKind.EXPENSE, rule.accountId, amountMinor, paidOn,
                rule.categoryId ?: return@run DomainError.Required("category").failure(),
                payee = rule.payee ?: rule.name, note = note, recurringRuleId = rule.id, occurrenceDate = dueDate,
            )
        }
        when (val r = record(draft)) {
            is Outcome.Failure -> return@run r
            is Outcome.Success -> Unit
        }
        val expected = Recurrence.amountOn(rule, dueDate)
        val paid = recurring.paidAmounts(listOf(rule.id))[rule.id to dueDate] ?: amountMinor
        recurring.setState(
            OccurrenceState(rule.id, dueDate, if (paid >= expected) OccurrenceStatus.PAID else OccurrenceStatus.PARTIAL, expected, clock.nowMillis()),
        )
        Unit.success()
    }

    suspend fun skip(rule: RecurringRule, dueDate: LocalDate) {
        recurring.setState(OccurrenceState(rule.id, dueDate, OccurrenceStatus.SKIPPED, Recurrence.amountOn(rule, dueDate), clock.nowMillis()))
    }
}

/** How a new debt/receivable came about; decides the first ledger movement (docs/05 §5.2). */
enum class ObligationOrigin {
    /** Borrowed money arrived in a funds account / lent money left one. */
    CASH,
    /** It existed before the app: an opening balance only, no cash movement now. */
    EXISTING,
    /** A purchase financed by the debt (installments): an expense on the debt account. */
    PURCHASE,
}

/** Creates the system account + details + first movement atomically (ADR-002). */
class CreateObligationUseCase @Inject constructor(
    private val accounts: AccountRepository,
    private val obligations: ObligationRepository,
    private val record: RecordOperationUseCase,
    private val tx: TransactionRunner,
    private val ids: IdGenerator,
    private val clock: TimeProvider,
) {
    suspend operator fun invoke(
        details: Obligation,
        name: String,
        currency: String,
        origin: ObligationOrigin,
        fundsAccountId: String?,
        purchaseCategoryId: String?,
    ): Outcome<String> = try {
        create(details, name, currency, origin, fundsAccountId, purchaseCategoryId)
    } catch (e: DomainFailure) {
        e.error.failure()
    }

    private suspend fun create(
        details: Obligation,
        name: String,
        currency: String,
        origin: ObligationOrigin,
        fundsAccountId: String?,
        purchaseCategoryId: String?,
    ): Outcome<String> = tx.run {
        if (details.principalMinor <= 0) return@run DomainError.InvalidAmount(AmountProblem.ZERO).failure()
        val accountId = ids.newId()
        val payable = details.direction == ObligationDirection.PAYABLE
        val type = if (payable) AccountType.LIABILITY else AccountType.RECEIVABLE
        val now = clock.nowMillis()
        val opening = if (origin == ObligationOrigin.EXISTING) (if (payable) -details.principalMinor else details.principalMinor) else 0L
        val account = Account(
            accountId, name.trim().ifEmpty { details.counterparty }, type, currency, startDate = details.startDate,
            includeInLiquid = false, createdAt = now, updatedAt = now,
        )
        (accounts.create(account, opening) as? Outcome.Failure)?.let { return@run it }
        obligations.save(details.copy(accountId = accountId, createdAt = now, updatedAt = now))
        val draft: OperationDraft? = when (origin) {
            ObligationOrigin.EXISTING -> null
            ObligationOrigin.CASH -> {
                val funds = fundsAccountId ?: return@run DomainError.Required("account").failure()
                if (payable) {
                    TransferDraft(ids.newId(), accountId, funds, details.principalMinor, details.principalMinor, details.startDate, note = details.counterparty)
                } else {
                    TransferDraft(ids.newId(), funds, accountId, details.principalMinor, details.principalMinor, details.startDate, note = details.counterparty)
                }
            }
            ObligationOrigin.PURCHASE -> {
                if (!payable) return@run DomainError.RuleViolation("FBR-04", "purchase origin needs a debt").failure()
                IncomeExpenseDraft(
                    ids.newId(), EntryKind.EXPENSE, accountId, details.principalMinor, details.startDate,
                    purchaseCategoryId ?: return@run DomainError.Required("category").failure(), payee = details.counterparty,
                )
            }
        }
        if (draft != null) {
            val r = record(draft)
            if (r is Outcome.Failure) throw DomainFailure(r.error) // roll back the account too
        }
        accountId.success()
    }
}

/** Thrown inside a transaction to roll it back while carrying the domain error to the caller. */
class DomainFailure(val error: DomainError) : RuntimeException()

/** FBR-32: closes a receivable that will not be paid, or a debt that was forgiven. */
class WriteOffObligationUseCase @Inject constructor(
    private val accounts: AccountRepository,
    private val obligations: ObligationRepository,
    private val ledger: LedgerRepository,
    private val tx: TransactionRunner,
    private val ids: IdGenerator,
    private val clock: TimeProvider,
) {
    suspend operator fun invoke(accountId: String, date: LocalDate, reason: String): Outcome<Unit> = tx.run {
        val ob = obligations.get(accountId) ?: return@run DomainError.NotFound("obligation", accountId).failure()
        val balance = accounts.balance(accountId)
        if (balance == 0L) return@run DomainError.InvalidAmount(AmountProblem.ZERO).failure()
        val groupId = ids.newId()
        val now = clock.nowMillis()
        val entry = Entry(ids.newId(), groupId, accountId, EntryKind.WRITE_OFF, -balance, date, note = reason, createdAt = now, updatedAt = now)
        (ledger.insertGroup(EntryGroup(groupId, listOf(entry)), reason) as? Outcome.Failure)?.let { return@run it }
        obligations.save(ob.copy(status = ObligationStatus.WRITTEN_OFF, updatedAt = now))
        Unit.success()
    }
}

/** Buy/sell/open/revalue a holding; cash leg and trade are written together (FBR-06, FBR-07, FBR-30). */
class AssetOperationsUseCase @Inject constructor(
    private val assets: AssetRepository,
    private val accounts: AccountRepository,
    private val currencies: CurrencyRepository,
    private val ledger: LedgerRepository,
    private val tx: TransactionRunner,
    private val ids: IdGenerator,
    private val clock: TimeProvider,
) {
    suspend fun create(asset: Asset, openingQuantity: BigDecimal?, openingCostMinor: Long?, valuationMinor: Long?, unitPrice: BigDecimal?, date: LocalDate): Outcome<String> = tx.run {
        val id = asset.id.ifBlank { ids.newId() }
        val now = clock.nowMillis()
        (assets.saveAsset(asset.copy(id = id, createdAt = now, updatedAt = now)) as? Outcome.Failure)?.let { return@run it }
        if (openingQuantity != null && openingQuantity.signum() > 0) {
            assets.insertTrade(AssetTrade(ids.newId(), id, date, TradeType.OPENING, openingQuantity, openingCostMinor ?: 0, createdAt = now))
        }
        if (valuationMinor != null || unitPrice != null) {
            assets.insertValuation(AssetValuation(ids.newId(), id, date, unitPrice, valuationMinor, asset.currency, ValuationSource.MANUAL, createdAt = now))
        }
        id.success()
    }

    /**
     * [paidMinor] is what left [accountId] in that account's currency (gross + fees); [grossMinor]/[feesMinor]
     * are in the asset currency (they are equal to paid when currencies match).
     */
    suspend fun buy(assetId: String, accountId: String, quantity: BigDecimal, grossMinor: Long, feesMinor: Long, paidMinor: Long, date: LocalDate, groupId: String): Outcome<Unit> =
        trade(TradeType.BUY, assetId, accountId, quantity, grossMinor, feesMinor, -paidMinor, date, groupId)

    /** [receivedMinor] = net proceeds that arrived in [accountId] (gross − fees, in the account currency). */
    suspend fun sell(assetId: String, accountId: String, quantity: BigDecimal, grossMinor: Long, feesMinor: Long, receivedMinor: Long, date: LocalDate, groupId: String): Outcome<Unit> =
        trade(TradeType.SELL, assetId, accountId, quantity, grossMinor, feesMinor, receivedMinor, date, groupId)

    private suspend fun trade(
        type: TradeType, assetId: String, accountId: String, quantity: BigDecimal, grossMinor: Long, feesMinor: Long,
        cashMinor: Long, date: LocalDate, groupId: String,
    ): Outcome<Unit> = tx.run {
        if (quantity.signum() <= 0 || grossMinor < 0 || feesMinor < 0) return@run DomainError.InvalidAmount(AmountProblem.ZERO).failure()
        MoneyMath.validateAmount(cashMinor)?.let { return@run it.failure() }
        val asset = assets.get(assetId) ?: return@run DomainError.NotFound("asset", assetId).failure()
        val account = accounts.get(accountId) ?: return@run DomainError.NotFound("account", accountId).failure()
        if (account.type == AccountType.RECEIVABLE) return@run DomainError.RuleViolation("FBR-06", "cannot pay from a receivable").failure()
        if (type == TradeType.SELL) {
            val digits = currencies.get(asset.currency)?.minorDigits ?: 2
            val holding = try {
                AssetCalculator.holding(asset, assets.trades(assetId), emptyList(), digits, asOf = date)
            } catch (_: OversellException) {
                return@run DomainError.RuleViolation("FBR-30", "inconsistent holding").failure()
            }
            if (quantity > holding.quantity) return@run DomainError.RuleViolation("FBR-30", "selling more than held").failure()
        }
        val now = clock.nowMillis()
        val tradeId = ids.newId()
        val kind = if (type == TradeType.BUY) EntryKind.ASSET_BUY else EntryKind.ASSET_SELL
        val entry = Entry(
            ids.newId(), groupId, accountId, kind, cashMinor, date, note = asset.name, assetTradeId = tradeId,
            createdAt = now, updatedAt = now,
        )
        when (val r = ledger.insertGroup(EntryGroup(groupId, listOf(entry)))) {
            is Outcome.Failure -> return@run if (r.error == DomainError.AlreadySaved) Unit.success() else r
            is Outcome.Success -> Unit
        }
        assets.insertTrade(AssetTrade(tradeId, assetId, date, type, quantity, grossMinor, feesMinor, groupId, createdAt = now))
        // The trade price is a real market observation: record it as a valuation (source TRADE).
        val unit = BigDecimal(grossMinor).divide(quantity, MathContext.DECIMAL64).movePointLeft(currencies.get(asset.currency)?.minorDigits ?: 2)
        assets.insertValuation(AssetValuation(ids.newId(), assetId, date, unit.stripTrailingZeros(), null, asset.currency, ValuationSource.TRADE, createdAt = now))
        Unit.success()
    }

    suspend fun revalue(assetId: String, date: LocalDate, unitPrice: BigDecimal?, totalMinor: Long?, note: String?): Outcome<Unit> {
        val asset = assets.get(assetId) ?: return DomainError.NotFound("asset", assetId).failure()
        if ((unitPrice == null) == (totalMinor == null)) return DomainError.Required("value").failure()
        if (unitPrice != null && unitPrice.signum() < 0 || totalMinor != null && totalMinor < 0) {
            return DomainError.InvalidAmount(AmountProblem.NEGATIVE_NOT_ALLOWED).failure()
        }
        assets.insertValuation(AssetValuation(ids.newId(), assetId, date, unitPrice, totalMinor, asset.currency, ValuationSource.MANUAL, note, clock.nowMillis()))
        return Unit.success()
    }
}

/** Virtual allocations to goals (FBR-23). Releasing more than allocated is refused. */
class GoalAllocationUseCase @Inject constructor(
    private val goals: GoalRepository,
    private val ids: IdGenerator,
    private val clock: TimeProvider,
) {
    suspend operator fun invoke(goalId: String, accountId: String?, amountMinor: Long, date: LocalDate, note: String?): Outcome<Unit> {
        if (amountMinor == 0L) return DomainError.InvalidAmount(AmountProblem.ZERO).failure()
        MoneyMath.validateAmount(amountMinor)?.let { return it.failure() }
        val goal = goals.get(goalId) ?: return DomainError.NotFound("goal", goalId).failure()
        if (goal.mode != GoalMode.ALLOCATION) return DomainError.RuleViolation("FBR-23", "linked-account goals follow balances").failure()
        if (amountMinor < 0) {
            val current = goals.contributions(goalId).sumOf { it.amountMinor }
            if (current + amountMinor < 0) return DomainError.RuleViolation("FBR-23", "release exceeds allocation").failure()
        }
        goals.addContribution(GoalContribution(ids.newId(), goalId, accountId, date, amountMinor, note, clock.nowMillis()))
        return Unit.success()
    }
}
