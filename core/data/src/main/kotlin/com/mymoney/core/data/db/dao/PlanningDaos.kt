package com.mymoney.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
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
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.model.EntryStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface BudgetDao {
    @Query("SELECT * FROM budget WHERE (:includeArchived OR isArchived = 0) ORDER BY createdAt")
    fun observe(includeArchived: Boolean): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budget_category")
    fun observeCategories(): Flow<List<BudgetCategoryEntity>>

    @Query("SELECT * FROM budget_period_override")
    fun observeOverrides(): Flow<List<BudgetPeriodOverrideEntity>>

    @Query("SELECT * FROM budget WHERE id = :id")
    suspend fun get(id: String): BudgetEntity?

    @Query("SELECT * FROM budget_category WHERE budgetId = :id")
    suspend fun categories(id: String): List<BudgetCategoryEntity>

    @Query("SELECT * FROM budget_period_override WHERE budgetId = :id")
    suspend fun overrides(id: String): List<BudgetPeriodOverrideEntity>

    @Upsert suspend fun upsert(budget: BudgetEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertCategories(list: List<BudgetCategoryEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertOverrides(list: List<BudgetPeriodOverrideEntity>)
    @Query("DELETE FROM budget_category WHERE budgetId = :id") suspend fun clearCategories(id: String)
    @Query("DELETE FROM budget_period_override WHERE budgetId = :id") suspend fun clearOverrides(id: String)
    @Query("UPDATE budget SET isArchived = :archived, updatedAt = :now WHERE id = :id") suspend fun setArchived(id: String, archived: Boolean, now: Long)
    @Query("DELETE FROM budget WHERE id = :id") suspend fun delete(id: String)

    @Query("SELECT * FROM budget_alert_state WHERE budgetId = :id AND periodStart = :start")
    suspend fun alertState(id: String, start: Long): BudgetAlertStateEntity?

    @Upsert suspend fun upsertAlertState(state: BudgetAlertStateEntity)
}

@Dao
interface RecurringDao {
    @Query("SELECT * FROM recurring_rule ORDER BY status, name")
    fun observe(): Flow<List<RecurringRuleEntity>>

    @Query("SELECT * FROM recurring_amount_change")
    fun observeChanges(): Flow<List<RecurringAmountChangeEntity>>

    @Query("SELECT * FROM recurring_rule ORDER BY status, name")
    suspend fun getAll(): List<RecurringRuleEntity>

    @Query("SELECT * FROM recurring_amount_change")
    suspend fun allChanges(): List<RecurringAmountChangeEntity>

    @Query("SELECT * FROM recurring_rule WHERE id = :id")
    suspend fun get(id: String): RecurringRuleEntity?

    @Query("SELECT * FROM recurring_amount_change WHERE ruleId = :id ORDER BY effectiveFrom")
    suspend fun changes(id: String): List<RecurringAmountChangeEntity>

    @Upsert suspend fun upsert(rule: RecurringRuleEntity)
    @Query("DELETE FROM recurring_amount_change WHERE ruleId = :id") suspend fun clearChanges(id: String)
    @Insert suspend fun insertChanges(list: List<RecurringAmountChangeEntity>)
    @Query("DELETE FROM recurring_rule WHERE id = :id") suspend fun delete(id: String)

    /** Rule deleted: entries keep existing but lose the link (logical reference, docs/03). */
    @Query("UPDATE entry SET recurringRuleId = NULL, occurrenceDate = NULL WHERE recurringRuleId = :id")
    suspend fun unlinkEntries(id: String)

    @Query("SELECT * FROM occurrence_state")
    fun observeStates(): Flow<List<OccurrenceStateEntity>>

    @Query("SELECT * FROM occurrence_state WHERE ruleId = :id")
    suspend fun states(id: String): List<OccurrenceStateEntity>

    @Upsert suspend fun upsertState(state: OccurrenceStateEntity)
    @Query("DELETE FROM occurrence_state WHERE ruleId = :id AND dueDate = :due") suspend fun deleteState(id: String, due: Long)

    /** Σ|amount| of live legs on the rule's own account, per due date (counts each payment once). */
    @Query(
        """SELECT e.recurringRuleId AS ruleId, e.occurrenceDate AS dueDate, SUM(ABS(e.amountMinor)) AS paidMinor
           FROM entry e JOIN recurring_rule r ON r.id = e.recurringRuleId AND e.accountId = r.accountId
           WHERE e.deletedAt IS NULL AND e.occurrenceDate IS NOT NULL
           GROUP BY e.recurringRuleId, e.occurrenceDate""",
    )
    fun observePaid(): Flow<List<PaidRow>>

    @Query(
        """SELECT e.recurringRuleId AS ruleId, e.occurrenceDate AS dueDate, SUM(ABS(e.amountMinor)) AS paidMinor
           FROM entry e JOIN recurring_rule r ON r.id = e.recurringRuleId AND e.accountId = r.accountId
           WHERE e.deletedAt IS NULL AND e.occurrenceDate IS NOT NULL
           GROUP BY e.recurringRuleId, e.occurrenceDate""",
    )
    suspend fun paid(): List<PaidRow>
}

data class PaidRow(val ruleId: String, val dueDate: Long, val paidMinor: Long)

@Dao
interface ObligationDao {
    @Query("SELECT * FROM obligation") fun observe(): Flow<List<ObligationEntity>>
    @Query("SELECT * FROM obligation WHERE accountId = :id") suspend fun get(id: String): ObligationEntity?
    @Upsert suspend fun upsert(o: ObligationEntity)
}

@Dao
interface AssetDao {
    @Query("SELECT * FROM asset ORDER BY isArchived, createdAt") fun observeAssets(): Flow<List<AssetEntity>>
    @Query("SELECT * FROM asset_trade ORDER BY date, createdAt") fun observeTrades(): Flow<List<AssetTradeEntity>>
    @Query("SELECT * FROM asset_valuation ORDER BY date, createdAt") fun observeValuations(): Flow<List<AssetValuationEntity>>
    @Query("SELECT * FROM asset WHERE id = :id") suspend fun get(id: String): AssetEntity?
    @Query("SELECT * FROM asset_trade WHERE assetId = :id ORDER BY date, createdAt") suspend fun trades(id: String): List<AssetTradeEntity>
    @Query("SELECT * FROM asset_valuation WHERE assetId = :id ORDER BY date, createdAt") suspend fun valuations(id: String): List<AssetValuationEntity>
    @Upsert suspend fun upsert(a: AssetEntity)
    @Insert suspend fun insertTrade(t: AssetTradeEntity)
    @Query("DELETE FROM asset_trade WHERE id = :id") suspend fun deleteTrade(id: String)
    @Query("SELECT * FROM asset_trade WHERE id = :id") suspend fun trade(id: String): AssetTradeEntity?
    @Insert suspend fun insertValuation(v: AssetValuationEntity)
    @Query("DELETE FROM asset_valuation WHERE id = :id") suspend fun deleteValuation(id: String)
}

@Dao
interface GoalDao {
    @Query("SELECT * FROM savings_goal ORDER BY status, priority, createdAt") fun observeGoals(): Flow<List<SavingsGoalEntity>>
    @Query("SELECT * FROM goal_account") fun observeGoalAccounts(): Flow<List<GoalAccountEntity>>
    @Query("SELECT * FROM goal_contribution ORDER BY date") fun observeContributions(): Flow<List<GoalContributionEntity>>
    @Query("SELECT * FROM savings_goal WHERE id = :id") suspend fun get(id: String): SavingsGoalEntity?
    @Query("SELECT * FROM goal_account WHERE goalId = :id") suspend fun accounts(id: String): List<GoalAccountEntity>
    @Query("SELECT * FROM goal_contribution WHERE goalId = :id ORDER BY date") suspend fun contributions(id: String): List<GoalContributionEntity>
    @Upsert suspend fun upsert(g: SavingsGoalEntity)
    @Query("DELETE FROM goal_account WHERE goalId = :id") suspend fun clearAccounts(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAccounts(list: List<GoalAccountEntity>)
    @Query("DELETE FROM savings_goal WHERE id = :id") suspend fun delete(id: String)
    @Insert suspend fun insertContribution(c: GoalContributionEntity)
    @Query("DELETE FROM goal_contribution WHERE id = :id") suspend fun deleteContribution(id: String)

    @Query(
        """SELECT COALESCE(SUM(c.amountMinor), 0) FROM goal_contribution c JOIN savings_goal g ON g.id = c.goalId
           WHERE c.accountId = :accountId AND g.status = 'ACTIVE'""",
    )
    suspend fun allocatedFrom(accountId: String): Long
}

@Dao
interface NotificationLogDao {
    @Query("SELECT EXISTS(SELECT 1 FROM notification_log WHERE `key` = :key)") suspend fun exists(key: String): Boolean
    @Upsert suspend fun upsert(e: NotificationLogEntity)
    @Query("DELETE FROM notification_log WHERE sentAt < :cutoff") suspend fun purge(cutoff: Long)
}

data class StatusCategoryRow(
    val currency: String,
    val date: Long,
    val categoryId: String?,
    val kind: EntryKind,
    val status: EntryStatus,
    val amountMinor: Long,
)

data class AccountSum(val accountId: String, val amountMinor: Long)

data class CostTypeRowDb(
    val currency: String,
    val date: Long,
    val costType: com.mymoney.core.domain.model.CostType?,
    val essential: Boolean?,
    val amountMinor: Long,
)

@Dao
interface AnalyticsDao {
    @Query(
        """SELECT a.currencyCode AS currency, v.date AS date, v.categoryId AS categoryId, v.kind AS kind, v.status AS status,
                  SUM(v.amountMinor) AS amountMinor
           FROM v_category_amount v JOIN account a ON a.id = v.accountId
           WHERE v.date BETWEEN :fromDay AND :toDay AND v.kind IN ('EXPENSE', 'REFUND')
           GROUP BY a.currencyCode, v.date, v.categoryId, v.kind, v.status""",
    )
    suspend fun budgetRows(fromDay: Long, toDay: Long): List<StatusCategoryRow>

    @Query(
        """SELECT a.currencyCode AS currency, e.date AS date, NULL AS categoryId, e.kind AS kind, SUM(e.amountMinor) AS amountMinor
           FROM entry e JOIN account a ON a.id = e.accountId
           WHERE e.deletedAt IS NULL AND e.status = 'COMPLETED' AND e.recurringRuleId IS NULL
             AND e.kind IN ('EXPENSE', 'REFUND') AND e.date BETWEEN :fromDay AND :toDay
             AND a.type NOT IN ('LIABILITY', 'RECEIVABLE')
           GROUP BY a.currencyCode, e.date, e.kind""",
    )
    suspend fun variableSpending(fromDay: Long, toDay: Long): List<CategoryAmountRowDb>

    @Query("SELECT accountId, SUM(amountMinor) AS amountMinor FROM entry WHERE deletedAt IS NULL AND status = 'PENDING' GROUP BY accountId")
    suspend fun pendingByAccount(): List<AccountSum>

    @Query(
        """SELECT accountId, SUM(amountMinor) AS amountMinor FROM entry
           WHERE deletedAt IS NULL AND status = 'COMPLETED' AND date <= :day GROUP BY accountId""",
    )
    suspend fun balancesOn(day: Long): List<AccountSum>

    /** Pairs of entries matching FBR-36 (same account, kind, amount, payee; ≤1 day apart). */
    @Query(
        """SELECT COUNT(*) FROM entry a JOIN entry b
             ON a.accountId = b.accountId AND a.kind = b.kind AND a.amountMinor = b.amountMinor
            AND a.id < b.id AND a.groupId <> b.groupId AND ABS(a.date - b.date) <= 1
            AND COALESCE(a.payee, '') = COALESCE(b.payee, '')
           WHERE a.deletedAt IS NULL AND b.deletedAt IS NULL AND a.date >= :sinceDay
             AND a.kind IN ('EXPENSE', 'INCOME', 'REFUND')""",
    )
    suspend fun duplicatePairs(sinceDay: Long): Int

    @Query(
        """SELECT a.currencyCode AS currency, e.date AS date, e.costType AS costType, e.essential AS essential, SUM(e.amountMinor) AS amountMinor
           FROM entry e JOIN account a ON a.id = e.accountId
           WHERE e.deletedAt IS NULL AND e.status = 'COMPLETED' AND e.kind = 'EXPENSE' AND e.date BETWEEN :fromDay AND :toDay
           GROUP BY a.currencyCode, e.date, e.costType, e.essential""",
    )
    suspend fun costTypes(fromDay: Long, toDay: Long): List<CostTypeRowDb>
}
