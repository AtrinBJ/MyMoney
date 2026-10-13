package com.mymoney.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mymoney.core.domain.model.AssetType
import com.mymoney.core.domain.model.BudgetPeriodType
import com.mymoney.core.domain.model.BudgetScope
import com.mymoney.core.domain.model.Frequency
import com.mymoney.core.domain.model.GoalMode
import com.mymoney.core.domain.model.GoalStatus
import com.mymoney.core.domain.model.InterestMethod
import com.mymoney.core.domain.model.ObligationDirection
import com.mymoney.core.domain.model.ObligationKind
import com.mymoney.core.domain.model.ObligationStatus
import com.mymoney.core.domain.model.OccurrenceStatus
import com.mymoney.core.domain.model.PostingPolicy
import com.mymoney.core.domain.model.RolloverMode
import com.mymoney.core.domain.model.RuleKind
import com.mymoney.core.domain.model.RuleStatus
import com.mymoney.core.domain.model.TradeType
import com.mymoney.core.domain.model.ValuationMode
import com.mymoney.core.domain.model.ValuationSource
import com.mymoney.core.domain.settings.CalendarType
import java.math.BigDecimal

// Schema v2 additions (docs/03 §3.4).

@Entity(
    tableName = "budget",
    foreignKeys = [ForeignKey(entity = CurrencyEntity::class, parentColumns = ["code"], childColumns = ["currencyCode"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["currencyCode"]), Index(value = ["isArchived"])],
)
data class BudgetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val periodType: BudgetPeriodType,
    val calendarSystem: CalendarType,
    val startDate: Long,
    val endDate: Long?,
    val amountMinor: Long,
    val currencyCode: String,
    val scope: BudgetScope,
    val rolloverMode: RolloverMode,
    val alertThresholds: String,
    @ColumnInfo(defaultValue = "0") val includePending: Boolean,
    @ColumnInfo(defaultValue = "0") val isArchived: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "budget_category",
    primaryKeys = ["budgetId", "categoryId"],
    foreignKeys = [
        ForeignKey(entity = BudgetEntity::class, parentColumns = ["id"], childColumns = ["budgetId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["categoryId"])],
)
data class BudgetCategoryEntity(val budgetId: String, val categoryId: String)

@Entity(
    tableName = "budget_period_override",
    primaryKeys = ["budgetId", "periodStart"],
    foreignKeys = [ForeignKey(entity = BudgetEntity::class, parentColumns = ["id"], childColumns = ["budgetId"], onDelete = ForeignKey.CASCADE)],
)
data class BudgetPeriodOverrideEntity(val budgetId: String, val periodStart: Long, val amountMinor: Long)

@Entity(
    tableName = "budget_alert_state",
    primaryKeys = ["budgetId", "periodStart"],
    foreignKeys = [ForeignKey(entity = BudgetEntity::class, parentColumns = ["id"], childColumns = ["budgetId"], onDelete = ForeignKey.CASCADE)],
)
data class BudgetAlertStateEntity(val budgetId: String, val periodStart: Long, val lastThresholdPct: Int, val notifiedAt: Long)

@Entity(
    tableName = "recurring_rule",
    foreignKeys = [
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["toAccountId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index(value = ["accountId"]), Index(value = ["toAccountId"]), Index(value = ["categoryId"]), Index(value = ["status"])],
)
data class RecurringRuleEntity(
    @PrimaryKey val id: String,
    val kind: RuleKind,
    val name: String,
    val accountId: String,
    val toAccountId: String?,
    val categoryId: String?,
    val amountMinor: Long,
    val currencyCode: String,
    @ColumnInfo(defaultValue = "0") val amountIsEstimate: Boolean,
    val frequency: Frequency,
    @ColumnInfo(defaultValue = "1") val interval: Int,
    val calendarSystem: CalendarType,
    val startDate: Long,
    val anchorDay: Int?,
    val endDate: Long?,
    val maxOccurrences: Int?,
    val postingPolicy: PostingPolicy,
    val reminderDaysBefore: Int?,
    val status: RuleStatus,
    val payee: String?,
    val note: String?,
    @ColumnInfo(defaultValue = "0") val splitInterest: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "recurring_amount_change",
    foreignKeys = [ForeignKey(entity = RecurringRuleEntity::class, parentColumns = ["id"], childColumns = ["ruleId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["ruleId", "effectiveFrom"], unique = true)],
)
data class RecurringAmountChangeEntity(
    @PrimaryKey val id: String,
    val ruleId: String,
    val effectiveFrom: Long,
    val amountMinor: Long,
)

/** PK (ruleId, dueDate): a due date can be confirmed only once — protects against double auto-posting (FBR-12). */
@Entity(
    tableName = "occurrence_state",
    primaryKeys = ["ruleId", "dueDate"],
    foreignKeys = [ForeignKey(entity = RecurringRuleEntity::class, parentColumns = ["id"], childColumns = ["ruleId"], onDelete = ForeignKey.CASCADE)],
)
data class OccurrenceStateEntity(
    val ruleId: String,
    val dueDate: Long,
    val status: OccurrenceStatus,
    val expectedMinor: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "obligation",
    foreignKeys = [ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)],
)
data class ObligationEntity(
    @PrimaryKey val accountId: String,
    val direction: ObligationDirection,
    val counterparty: String,
    val kind: ObligationKind,
    val principalMinor: Long,
    val startDate: Long,
    val dueDate: Long?,
    val annualRate: BigDecimal?,
    val interestMethod: InterestMethod,
    val termMonths: Int?,
    val status: ObligationStatus,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "asset",
    foreignKeys = [ForeignKey(entity = CurrencyEntity::class, parentColumns = ["code"], childColumns = ["currencyCode"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["currencyCode"])],
)
data class AssetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: AssetType,
    val unit: String,
    val currencyCode: String,
    val valuationMode: ValuationMode,
    @ColumnInfo(defaultValue = "1") val includeInNetWorth: Boolean,
    @ColumnInfo(defaultValue = "0") val isArchived: Boolean,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "asset_trade",
    foreignKeys = [ForeignKey(entity = AssetEntity::class, parentColumns = ["id"], childColumns = ["assetId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["assetId", "date"]), Index(value = ["entryGroupId"])],
)
data class AssetTradeEntity(
    @PrimaryKey val id: String,
    val assetId: String,
    val date: Long,
    val type: TradeType,
    val quantity: BigDecimal,
    val grossMinor: Long,
    val feesMinor: Long,
    val entryGroupId: String?,
    val note: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "asset_valuation",
    foreignKeys = [ForeignKey(entity = AssetEntity::class, parentColumns = ["id"], childColumns = ["assetId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["assetId", "date"])],
)
data class AssetValuationEntity(
    @PrimaryKey val id: String,
    val assetId: String,
    val date: Long,
    val unitPrice: BigDecimal?,
    val totalValueMinor: Long?,
    val currencyCode: String,
    val source: ValuationSource,
    val note: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "savings_goal",
    foreignKeys = [ForeignKey(entity = CurrencyEntity::class, parentColumns = ["code"], childColumns = ["currencyCode"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["currencyCode"])],
)
data class SavingsGoalEntity(
    @PrimaryKey val id: String,
    val name: String,
    val targetMinor: Long,
    val currencyCode: String,
    val targetDate: Long?,
    @ColumnInfo(defaultValue = "2") val priority: Int,
    val mode: GoalMode,
    val status: GoalStatus,
    val iconKey: String?,
    val colorKey: String?,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "goal_account",
    primaryKeys = ["goalId", "accountId"],
    foreignKeys = [
        ForeignKey(entity = SavingsGoalEntity::class, parentColumns = ["id"], childColumns = ["goalId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["accountId"])],
)
data class GoalAccountEntity(val goalId: String, val accountId: String)

@Entity(
    tableName = "goal_contribution",
    foreignKeys = [
        ForeignKey(entity = SavingsGoalEntity::class, parentColumns = ["id"], childColumns = ["goalId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index(value = ["goalId", "date"]), Index(value = ["accountId"])],
)
data class GoalContributionEntity(
    @PrimaryKey val id: String,
    val goalId: String,
    val accountId: String?,
    val date: Long,
    val amountMinor: Long,
    val note: String?,
    val createdAt: Long,
)

@Entity(tableName = "notification_log")
data class NotificationLogEntity(@PrimaryKey val key: String, val sentAt: Long)
