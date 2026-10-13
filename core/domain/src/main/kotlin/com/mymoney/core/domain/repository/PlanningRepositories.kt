package com.mymoney.core.domain.repository

import com.mymoney.core.domain.calc.BudgetRow
import com.mymoney.core.domain.common.Outcome
import com.mymoney.core.domain.model.Asset
import com.mymoney.core.domain.model.AssetTrade
import com.mymoney.core.domain.model.AssetValuation
import com.mymoney.core.domain.model.Budget
import com.mymoney.core.domain.model.GoalContribution
import com.mymoney.core.domain.model.Obligation
import com.mymoney.core.domain.model.OccurrenceState
import com.mymoney.core.domain.model.RecurringRule
import com.mymoney.core.domain.model.SavingsGoal
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/** Runs [block] in one database transaction; nested calls join the outer transaction (FBR-13). */
interface TransactionRunner {
    suspend fun <T> run(block: suspend () -> T): T
}

interface BudgetRepository {
    fun observeAll(includeArchived: Boolean = false): Flow<List<Budget>>
    suspend fun get(id: String): Budget?
    suspend fun save(budget: Budget): Outcome<Unit>
    suspend fun setArchived(id: String, archived: Boolean)
    suspend fun delete(id: String)

    /** Last threshold already notified for the period starting at [periodStart] (avoids repeat alerts). */
    suspend fun lastNotifiedThreshold(budgetId: String, periodStart: LocalDate): Int?
    suspend fun setNotifiedThreshold(budgetId: String, periodStart: LocalDate, percent: Int)
}

interface RecurringRepository {
    fun observeAll(): Flow<List<RecurringRule>>
    suspend fun getAll(): List<RecurringRule>
    suspend fun get(id: String): RecurringRule?
    suspend fun save(rule: RecurringRule): Outcome<Unit>
    suspend fun delete(id: String)
    fun observeStates(): Flow<List<OccurrenceState>>
    suspend fun states(ruleId: String): List<OccurrenceState>
    suspend fun setState(state: OccurrenceState)
    suspend fun clearState(ruleId: String, dueDate: LocalDate)

    /** Amount already recorded per (rule, due date): Σ |entry amounts| linked by recurringRuleId+occurrenceDate. */
    suspend fun paidAmounts(ruleIds: Collection<String>? = null): Map<Pair<String, LocalDate>, Long>
    fun observePaidAmounts(): Flow<Map<Pair<String, LocalDate>, Long>>
}

interface ObligationRepository {
    fun observeAll(): Flow<List<Obligation>>
    suspend fun get(accountId: String): Obligation?
    suspend fun save(obligation: Obligation)
}

interface AssetRepository {
    fun observeAssets(): Flow<List<Asset>>
    fun observeTrades(): Flow<List<AssetTrade>>
    fun observeValuations(): Flow<List<AssetValuation>>
    suspend fun get(id: String): Asset?
    suspend fun trades(assetId: String): List<AssetTrade>
    suspend fun valuations(assetId: String): List<AssetValuation>
    suspend fun saveAsset(asset: Asset): Outcome<Unit>
    suspend fun insertTrade(trade: AssetTrade)
    suspend fun deleteTrade(id: String)
    suspend fun insertValuation(valuation: AssetValuation)
    suspend fun deleteValuation(id: String)
}

interface GoalRepository {
    fun observeGoals(): Flow<List<SavingsGoal>>
    fun observeContributions(): Flow<List<GoalContribution>>
    suspend fun get(id: String): SavingsGoal?
    suspend fun contributions(goalId: String): List<GoalContribution>
    suspend fun save(goal: SavingsGoal): Outcome<Unit>
    suspend fun delete(id: String)
    suspend fun addContribution(c: GoalContribution)
    suspend fun deleteContribution(id: String)
    /** Σ allocations from [accountId] across all active goals (FBR-23). */
    suspend fun allocatedFrom(accountId: String): Long
}

interface NotificationLogRepository {
    suspend fun wasSent(key: String): Boolean
    suspend fun markSent(key: String, at: Long)
    suspend fun purgeOlderThan(cutoff: Long)
}

/** Extra ledger reads needed by stage-4 calculators. */
interface LedgerAnalytics {
    /** Category rows with their status (COMPLETED and PENDING) for budgets. */
    suspend fun budgetRows(from: LocalDate, to: LocalDate): List<BudgetRow>

    /** Non-recurring EXPENSE/REFUND per (currency, day), COMPLETED only (FBR-28 variable spending). */
    suspend fun variableSpendingRows(from: LocalDate, to: LocalDate): List<CategoryAmountRow>

    /** Σ PENDING amounts per account. */
    suspend fun pendingByAccount(): Map<String, Long>

    /** Balance of every account on [date] (end of day). */
    suspend fun balancesOn(date: LocalDate): Map<String, Long>

    /** Count of groups flagged by FBR-36 in the last [days] days. */
    suspend fun possibleDuplicateCount(since: LocalDate): Int
}
