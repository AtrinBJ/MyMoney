package com.mymoney.core.data.repository

import androidx.room.withTransaction
import com.mymoney.core.data.db.MyMoneyDatabase
import com.mymoney.core.data.db.entity.AssetEntity
import com.mymoney.core.data.db.entity.AssetTradeEntity
import com.mymoney.core.data.db.entity.AssetValuationEntity
import com.mymoney.core.data.db.entity.BudgetAlertStateEntity
import com.mymoney.core.data.db.entity.BudgetCategoryEntity
import com.mymoney.core.data.db.entity.BudgetEntity
import com.mymoney.core.data.db.entity.BudgetPeriodOverrideEntity
import com.mymoney.core.data.db.entity.GoalAccountEntity
import com.mymoney.core.data.db.entity.GoalContributionEntity
import com.mymoney.core.data.db.entity.NotificationLogEntity
import com.mymoney.core.data.db.entity.ObligationEntity
import com.mymoney.core.data.db.entity.OccurrenceStateEntity
import com.mymoney.core.data.db.entity.RecurringAmountChangeEntity
import com.mymoney.core.data.db.entity.RecurringRuleEntity
import com.mymoney.core.data.db.entity.SavingsGoalEntity
import com.mymoney.core.domain.calc.BudgetRow
import com.mymoney.core.domain.common.DomainError
import com.mymoney.core.domain.common.IdGenerator
import com.mymoney.core.domain.common.Outcome
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.model.AmountChange
import com.mymoney.core.domain.model.Asset
import com.mymoney.core.domain.model.AssetTrade
import com.mymoney.core.domain.model.AssetValuation
import com.mymoney.core.domain.model.Budget
import com.mymoney.core.domain.model.GoalContribution
import com.mymoney.core.domain.model.Obligation
import com.mymoney.core.domain.model.OccurrenceState
import com.mymoney.core.domain.model.RecurringRule
import com.mymoney.core.domain.model.SavingsGoal
import com.mymoney.core.domain.money.MoneyMath
import com.mymoney.core.domain.repository.AssetRepository
import com.mymoney.core.domain.repository.BudgetRepository
import com.mymoney.core.domain.repository.CategoryAmountRow
import com.mymoney.core.domain.repository.GoalRepository
import com.mymoney.core.domain.repository.LedgerAnalytics
import com.mymoney.core.domain.repository.NotificationLogRepository
import com.mymoney.core.domain.repository.ObligationRepository
import com.mymoney.core.domain.repository.RecurringRepository
import com.mymoney.core.domain.repository.TransactionRunner
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

@Singleton
class RoomTransactionRunner @Inject constructor(private val db: MyMoneyDatabase) : TransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T = db.withTransaction { block() }
}

// ---------------- Budgets ----------------

@Singleton
class BudgetRepositoryImpl @Inject constructor(
    private val db: MyMoneyDatabase,
    private val ids: IdGenerator,
    private val clock: TimeProvider,
) : BudgetRepository {
    private val dao get() = db.budgetDao()

    override fun observeAll(includeArchived: Boolean): Flow<List<Budget>> =
        combine(dao.observe(includeArchived), dao.observeCategories(), dao.observeOverrides()) { budgets, cats, overrides ->
            val catsBy = cats.groupBy({ it.budgetId }, { it.categoryId })
            val ovBy = overrides.groupBy { it.budgetId }
            budgets.map { it.toDomain(catsBy[it.id].orEmpty().toSet(), ovBy[it.id].orEmpty()) }
        }

    override suspend fun get(id: String): Budget? = dao.get(id)?.let { it.toDomain(dao.categories(id).map { c -> c.categoryId }.toSet(), dao.overrides(id)) }

    override suspend fun save(budget: Budget): Outcome<Unit> {
        if (budget.name.isBlank()) return Outcome.Failure(DomainError.InvalidName("name"))
        if (budget.amountMinor <= 0) return Outcome.Failure(DomainError.InvalidAmount(com.mymoney.core.domain.common.AmountProblem.ZERO))
        MoneyMath.validateAmount(budget.amountMinor)?.let { return Outcome.Failure(it) }
        if (budget.endDate?.isBefore(budget.startDate) == true) return Outcome.Failure(DomainError.InvalidDate("end before start"))
        if (budget.alertThresholds.any { it !in 1..200 }) return Outcome.Failure(DomainError.RuleViolation("FBR-19", "threshold 1..200"))
        val now = clock.nowMillis()
        val id = budget.id.ifBlank { ids.newId() }
        db.withTransaction {
            val existing = dao.get(id)
            dao.upsert(
                BudgetEntity(
                    id, budget.name.trim(), budget.periodType, budget.calendar, budget.startDate.toEpochDay(), budget.endDate?.toEpochDay(),
                    budget.amountMinor, budget.currency, budget.scope, budget.rollover, budget.alertThresholds.sorted().joinToString(","),
                    budget.includePending, budget.isArchived, existing?.createdAt ?: now, now,
                ),
            )
            dao.clearCategories(id)
            if (budget.categoryIds.isNotEmpty()) dao.insertCategories(budget.categoryIds.map { BudgetCategoryEntity(id, it) })
            dao.clearOverrides(id)
            if (budget.overrides.isNotEmpty()) dao.insertOverrides(budget.overrides.map { (d, v) -> BudgetPeriodOverrideEntity(id, d.toEpochDay(), v) })
        }
        return Outcome.Success(Unit)
    }

    override suspend fun setArchived(id: String, archived: Boolean) = dao.setArchived(id, archived, clock.nowMillis())
    override suspend fun delete(id: String) = dao.delete(id)

    override suspend fun lastNotifiedThreshold(budgetId: String, periodStart: LocalDate): Int? =
        dao.alertState(budgetId, periodStart.toEpochDay())?.lastThresholdPct

    override suspend fun setNotifiedThreshold(budgetId: String, periodStart: LocalDate, percent: Int) =
        dao.upsertAlertState(BudgetAlertStateEntity(budgetId, periodStart.toEpochDay(), percent, clock.nowMillis()))

    private fun BudgetEntity.toDomain(cats: Set<String>, ov: List<BudgetPeriodOverrideEntity>) = Budget(
        id, name, periodType, calendarSystem, startDate.toDate(), endDate?.toDate(), amountMinor, currencyCode, scope, cats, rolloverMode,
        alertThresholds.split(',').mapNotNull { it.trim().toIntOrNull() }, includePending, isArchived,
        ov.associate { it.periodStart.toDate() to it.amountMinor }, createdAt, updatedAt,
    )
}

// ---------------- Recurring ----------------

@Singleton
class RecurringRepositoryImpl @Inject constructor(
    private val db: MyMoneyDatabase,
    private val ids: IdGenerator,
    private val clock: TimeProvider,
) : RecurringRepository {
    private val dao get() = db.recurringDao()

    override fun observeAll(): Flow<List<RecurringRule>> = combine(dao.observe(), dao.observeChanges()) { rules, changes ->
        val by = changes.groupBy { it.ruleId }
        rules.map { it.toDomain(by[it.id].orEmpty()) }
    }

    override suspend fun getAll(): List<RecurringRule> {
        val by = dao.allChanges().groupBy { it.ruleId }
        return dao.getAll().map { it.toDomain(by[it.id].orEmpty()) }
    }

    override suspend fun get(id: String): RecurringRule? = dao.get(id)?.toDomain(dao.changes(id))

    override suspend fun save(rule: RecurringRule): Outcome<Unit> {
        if (rule.name.isBlank()) return Outcome.Failure(DomainError.InvalidName("name"))
        if (rule.amountMinor <= 0) return Outcome.Failure(DomainError.InvalidAmount(com.mymoney.core.domain.common.AmountProblem.ZERO))
        MoneyMath.validateAmount(rule.amountMinor)?.let { return Outcome.Failure(it) }
        if (rule.interval !in 1..366) return Outcome.Failure(DomainError.RuleViolation("FBR-26", "interval"))
        if (rule.anchorDay?.let { it !in 1..31 } == true) return Outcome.Failure(DomainError.InvalidDate("anchor"))
        if (rule.endDate?.isBefore(rule.startDate) == true) return Outcome.Failure(DomainError.InvalidDate("end before start"))
        if ((rule.kind == com.mymoney.core.domain.model.RuleKind.TRANSFER || rule.kind == com.mymoney.core.domain.model.RuleKind.LOAN_INSTALLMENT) &&
            (rule.toAccountId == null || rule.toAccountId == rule.accountId)
        ) {
            return Outcome.Failure(DomainError.RuleViolation("FBR-16", "transfer rule needs two accounts"))
        }
        val now = clock.nowMillis()
        val id = rule.id.ifBlank { ids.newId() }
        db.withTransaction {
            val existing = dao.get(id)
            dao.upsert(
                RecurringRuleEntity(
                    id, rule.kind, rule.name.trim(), rule.accountId, rule.toAccountId, rule.categoryId, rule.amountMinor, rule.currency,
                    rule.amountIsEstimate, rule.frequency, rule.interval, rule.calendar, rule.startDate.toEpochDay(), rule.anchorDay,
                    rule.endDate?.toEpochDay(), rule.maxOccurrences, rule.postingPolicy, rule.reminderDaysBefore, rule.status,
                    rule.payee, rule.note, rule.splitInterest, existing?.createdAt ?: now, now,
                ),
            )
            dao.clearChanges(id)
            if (rule.amountChanges.isNotEmpty()) {
                dao.insertChanges(rule.amountChanges.distinctBy { it.effectiveFrom }.map { RecurringAmountChangeEntity(ids.newId(), id, it.effectiveFrom.toEpochDay(), it.amountMinor) })
            }
        }
        return Outcome.Success(Unit)
    }

    override suspend fun delete(id: String) {
        db.withTransaction {
            dao.unlinkEntries(id)
            dao.delete(id)
        }
    }

    override fun observeStates(): Flow<List<OccurrenceState>> = dao.observeStates().map { l -> l.map { it.toDomain() } }
    override suspend fun states(ruleId: String): List<OccurrenceState> = dao.states(ruleId).map { it.toDomain() }
    override suspend fun setState(state: OccurrenceState) =
        dao.upsertState(OccurrenceStateEntity(state.ruleId, state.dueDate.toEpochDay(), state.status, state.expectedMinor, state.updatedAt))
    override suspend fun clearState(ruleId: String, dueDate: LocalDate) = dao.deleteState(ruleId, dueDate.toEpochDay())

    override suspend fun paidAmounts(ruleIds: Collection<String>?): Map<Pair<String, LocalDate>, Long> =
        dao.paid().filter { ruleIds == null || it.ruleId in ruleIds }.associate { (it.ruleId to it.dueDate.toDate()) to it.paidMinor }

    override fun observePaidAmounts(): Flow<Map<Pair<String, LocalDate>, Long>> =
        dao.observePaid().map { rows -> rows.associate { (it.ruleId to it.dueDate.toDate()) to it.paidMinor } }

    private fun RecurringRuleEntity.toDomain(changes: List<RecurringAmountChangeEntity>) = RecurringRule(
        id, kind, name, accountId, toAccountId, categoryId, amountMinor, currencyCode, amountIsEstimate, frequency, interval,
        calendarSystem, startDate.toDate(), anchorDay, endDate?.toDate(), maxOccurrences, postingPolicy, reminderDaysBefore, status,
        payee, note, splitInterest, changes.map { AmountChange(it.effectiveFrom.toDate(), it.amountMinor) }.sortedBy { it.effectiveFrom },
        createdAt, updatedAt,
    )

    private fun OccurrenceStateEntity.toDomain() = OccurrenceState(ruleId, dueDate.toDate(), status, expectedMinor, updatedAt)
}

// ---------------- Obligations ----------------

@Singleton
class ObligationRepositoryImpl @Inject constructor(private val db: MyMoneyDatabase) : ObligationRepository {
    override fun observeAll(): Flow<List<Obligation>> = db.obligationDao().observe().map { l -> l.map { it.toDomain() } }
    override suspend fun get(accountId: String): Obligation? = db.obligationDao().get(accountId)?.toDomain()
    override suspend fun save(obligation: Obligation) = db.obligationDao().upsert(
        with(obligation) {
            ObligationEntity(
                accountId, direction, counterparty.trim(), kind, principalMinor, startDate.toEpochDay(), dueDate?.toEpochDay(),
                annualRatePercent, interestMethod, termMonths, status, note, createdAt, updatedAt,
            )
        },
    )

    private fun ObligationEntity.toDomain() = Obligation(
        accountId, direction, counterparty, kind, principalMinor, startDate.toDate(), dueDate?.toDate(), annualRate,
        interestMethod, termMonths, status, note, createdAt, updatedAt,
    )
}

// ---------------- Assets ----------------

@Singleton
class AssetRepositoryImpl @Inject constructor(private val db: MyMoneyDatabase, private val clock: TimeProvider) : AssetRepository {
    private val dao get() = db.assetDao()
    override fun observeAssets(): Flow<List<Asset>> = dao.observeAssets().map { l -> l.map { it.toDomain() } }
    override fun observeTrades(): Flow<List<AssetTrade>> = dao.observeTrades().map { l -> l.map { it.toDomain() } }
    override fun observeValuations(): Flow<List<AssetValuation>> = dao.observeValuations().map { l -> l.map { it.toDomain() } }
    override suspend fun get(id: String): Asset? = dao.get(id)?.toDomain()
    override suspend fun trades(assetId: String): List<AssetTrade> = dao.trades(assetId).map { it.toDomain() }
    override suspend fun valuations(assetId: String): List<AssetValuation> = dao.valuations(assetId).map { it.toDomain() }

    override suspend fun saveAsset(asset: Asset): Outcome<Unit> {
        if (asset.name.isBlank()) return Outcome.Failure(DomainError.InvalidName("name"))
        val existing = dao.get(asset.id)
        dao.upsert(
            with(asset) {
                AssetEntity(id, name.trim(), type, unit.trim(), currency, valuationMode, includeInNetWorth, isArchived, note,
                    existing?.createdAt ?: createdAt.takeIf { it > 0 } ?: clock.nowMillis(), clock.nowMillis())
            },
        )
        return Outcome.Success(Unit)
    }

    override suspend fun insertTrade(trade: AssetTrade) = dao.insertTrade(
        with(trade) { AssetTradeEntity(id, assetId, date.toEpochDay(), type, quantity, grossMinor, feesMinor, entryGroupId, note, createdAt) },
    )

    override suspend fun deleteTrade(id: String) {
        db.withTransaction {
            // Deleting a trade also removes its cash movement, keeping holdings and balances consistent.
            dao.trade(id)?.entryGroupId?.let { db.entryDao().softDeleteGroup(it, clock.nowMillis()) }
            dao.deleteTrade(id)
        }
    }

    override suspend fun insertValuation(valuation: AssetValuation) = dao.insertValuation(
        with(valuation) { AssetValuationEntity(id, assetId, date.toEpochDay(), unitPrice, totalValueMinor, currency, source, note, createdAt) },
    )

    override suspend fun deleteValuation(id: String) = dao.deleteValuation(id)

    private fun AssetEntity.toDomain() = Asset(id, name, type, unit, currencyCode, valuationMode, includeInNetWorth, isArchived, note, createdAt, updatedAt)
    private fun AssetTradeEntity.toDomain() = AssetTrade(id, assetId, date.toDate(), type, quantity, grossMinor, feesMinor, entryGroupId, note, createdAt)
    private fun AssetValuationEntity.toDomain() = AssetValuation(id, assetId, date.toDate(), unitPrice, totalValueMinor, currencyCode, source, note, createdAt)
}

// ---------------- Goals ----------------

@Singleton
class GoalRepositoryImpl @Inject constructor(
    private val db: MyMoneyDatabase,
    private val ids: IdGenerator,
    private val clock: TimeProvider,
) : GoalRepository {
    private val dao get() = db.goalDao()

    override fun observeGoals(): Flow<List<SavingsGoal>> = combine(dao.observeGoals(), dao.observeGoalAccounts()) { goals, links ->
        val by = links.groupBy({ it.goalId }, { it.accountId })
        goals.map { it.toDomain(by[it.id].orEmpty().toSet()) }
    }

    override fun observeContributions(): Flow<List<GoalContribution>> = dao.observeContributions().map { l -> l.map { it.toDomain() } }
    override suspend fun get(id: String): SavingsGoal? = dao.get(id)?.toDomain(dao.accounts(id).map { it.accountId }.toSet())
    override suspend fun contributions(goalId: String): List<GoalContribution> = dao.contributions(goalId).map { it.toDomain() }

    override suspend fun save(goal: SavingsGoal): Outcome<Unit> {
        if (goal.name.isBlank()) return Outcome.Failure(DomainError.InvalidName("name"))
        if (goal.targetMinor <= 0) return Outcome.Failure(DomainError.InvalidAmount(com.mymoney.core.domain.common.AmountProblem.ZERO))
        MoneyMath.validateAmount(goal.targetMinor)?.let { return Outcome.Failure(it) }
        val id = goal.id.ifBlank { ids.newId() }
        val now = clock.nowMillis()
        db.withTransaction {
            val existing = dao.get(id)
            dao.upsert(
                with(goal) {
                    SavingsGoalEntity(id, name.trim(), targetMinor, currency, targetDate?.toEpochDay(), priority, mode, status, iconKey, colorKey, note,
                        existing?.createdAt ?: now, now)
                },
            )
            dao.clearAccounts(id)
            if (goal.accountIds.isNotEmpty()) dao.insertAccounts(goal.accountIds.map { GoalAccountEntity(id, it) })
        }
        return Outcome.Success(Unit)
    }

    override suspend fun delete(id: String) = dao.delete(id)
    override suspend fun addContribution(c: GoalContribution) = dao.insertContribution(
        GoalContributionEntity(c.id, c.goalId, c.accountId, c.date.toEpochDay(), c.amountMinor, c.note, c.createdAt),
    )
    override suspend fun deleteContribution(id: String) = dao.deleteContribution(id)
    override suspend fun allocatedFrom(accountId: String): Long = dao.allocatedFrom(accountId)

    private fun SavingsGoalEntity.toDomain(accounts: Set<String>) = SavingsGoal(
        id, name, targetMinor, currencyCode, targetDate?.toDate(), priority, mode, status, accounts, iconKey, colorKey, note, createdAt, updatedAt,
    )
    private fun GoalContributionEntity.toDomain() = GoalContribution(id, goalId, accountId, date.toDate(), amountMinor, note, createdAt)
}

// ---------------- Misc ----------------

@Singleton
class NotificationLogRepositoryImpl @Inject constructor(private val db: MyMoneyDatabase) : NotificationLogRepository {
    override suspend fun wasSent(key: String) = db.notificationLogDao().exists(key)
    override suspend fun markSent(key: String, at: Long) = db.notificationLogDao().upsert(NotificationLogEntity(key, at))
    override suspend fun purgeOlderThan(cutoff: Long) = db.notificationLogDao().purge(cutoff)
}

@Singleton
class LedgerAnalyticsImpl @Inject constructor(private val db: MyMoneyDatabase) : LedgerAnalytics {
    private val dao get() = db.analyticsDao()

    override suspend fun budgetRows(from: LocalDate, to: LocalDate): List<BudgetRow> =
        dao.budgetRows(from.toEpochDay(), to.toEpochDay()).map { BudgetRow(CategoryAmountRow(it.currency, it.date.toDate(), it.categoryId, it.kind, it.amountMinor), it.status) }

    override suspend fun variableSpendingRows(from: LocalDate, to: LocalDate): List<CategoryAmountRow> =
        dao.variableSpending(from.toEpochDay(), to.toEpochDay()).map { it.toDomain() }

    override suspend fun pendingByAccount(): Map<String, Long> = dao.pendingByAccount().associate { it.accountId to it.amountMinor }
    override suspend fun balancesOn(date: LocalDate): Map<String, Long> = dao.balancesOn(date.toEpochDay()).associate { it.accountId to it.amountMinor }
    override suspend fun possibleDuplicateCount(since: LocalDate): Int = dao.duplicatePairs(since.toEpochDay())

    override suspend fun costTypeRows(from: LocalDate, to: LocalDate): List<com.mymoney.core.domain.repository.CostTypeRow> =
        dao.costTypes(from.toEpochDay(), to.toEpochDay()).map { com.mymoney.core.domain.repository.CostTypeRow(it.currency, it.date.toDate(), it.costType, it.essential, it.amountMinor) }
}
