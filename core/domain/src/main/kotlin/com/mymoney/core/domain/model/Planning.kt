package com.mymoney.core.domain.model

import com.mymoney.core.domain.settings.CalendarType
import java.math.BigDecimal
import java.time.LocalDate

// ---------------- Budgets (docs/03 §3.4) ----------------

enum class BudgetPeriodType { WEEKLY, MONTHLY, YEARLY, CUSTOM }
enum class BudgetScope { TOTAL, CATEGORIES }
enum class RolloverMode { NONE, CARRY_UNUSED, CARRY_ALL }

data class Budget(
    val id: String,
    val name: String,
    val periodType: BudgetPeriodType,
    val calendar: CalendarType,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val amountMinor: Long,
    val currency: String,
    val scope: BudgetScope = BudgetScope.TOTAL,
    val categoryIds: Set<String> = emptySet(),
    val rollover: RolloverMode = RolloverMode.NONE,
    val alertThresholds: List<Int> = listOf(50, 80, 100),
    val includePending: Boolean = false,
    val isArchived: Boolean = false,
    /** Period start (epoch day) → overridden limit for that period only. */
    val overrides: Map<LocalDate, Long> = emptyMap(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

// ---------------- Recurring rules & occurrences ----------------

enum class RuleKind { BILL, SUBSCRIPTION, INCOME, LOAN_INSTALLMENT, TRANSFER, OTHER }
enum class Frequency { ONCE, DAILY, WEEKLY, MONTHLY, YEARLY }
enum class PostingPolicy { MANUAL_CONFIRM, AUTO_POST }
enum class RuleStatus { ACTIVE, PAUSED, ENDED }
enum class OccurrenceStatus { PARTIAL, PAID, SKIPPED }

data class AmountChange(val effectiveFrom: LocalDate, val amountMinor: Long)

data class RecurringRule(
    val id: String,
    val kind: RuleKind,
    val name: String,
    val accountId: String,
    val toAccountId: String? = null,
    val categoryId: String? = null,
    val amountMinor: Long,
    val currency: String,
    val amountIsEstimate: Boolean = false,
    val frequency: Frequency,
    val interval: Int = 1,
    val calendar: CalendarType,
    val startDate: LocalDate,
    val anchorDay: Int? = null,
    val endDate: LocalDate? = null,
    val maxOccurrences: Int? = null,
    val postingPolicy: PostingPolicy = PostingPolicy.MANUAL_CONFIRM,
    val reminderDaysBefore: Int? = null,
    val status: RuleStatus = RuleStatus.ACTIVE,
    val payee: String? = null,
    val note: String? = null,
    val splitInterest: Boolean = false,
    val amountChanges: List<AmountChange> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    /** Money direction from the paying account's point of view. */
    val isInflow: Boolean get() = kind == RuleKind.INCOME
}

data class OccurrenceState(
    val ruleId: String,
    val dueDate: LocalDate,
    val status: OccurrenceStatus,
    val expectedMinor: Long,
    val updatedAt: Long = 0,
)

// ---------------- Debts & receivables ----------------

enum class ObligationDirection { PAYABLE, RECEIVABLE }
enum class ObligationKind { BANK_LOAN, PERSONAL, INSTALLMENT_PURCHASE, FAMILY, CUSTOMER, OTHER }
enum class InterestMethod { NONE, SIMPLE, ANNUITY }
enum class ObligationStatus { OPEN, SETTLED, WRITTEN_OFF }

data class Obligation(
    val accountId: String,
    val direction: ObligationDirection,
    val counterparty: String,
    val kind: ObligationKind,
    val principalMinor: Long,
    val startDate: LocalDate,
    val dueDate: LocalDate? = null,
    val annualRatePercent: BigDecimal? = null,
    val interestMethod: InterestMethod = InterestMethod.NONE,
    val termMonths: Int? = null,
    val status: ObligationStatus = ObligationStatus.OPEN,
    val note: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

// ---------------- Assets ----------------

enum class AssetType { GOLD, STOCK, FUND, CRYPTO, VEHICLE, REAL_ESTATE, PERSONAL, OTHER }
enum class ValuationMode { PER_UNIT, TOTAL }
enum class TradeType { OPENING, BUY, SELL, ADJUST }
enum class ValuationSource { MANUAL, TRADE, ESTIMATE }

data class Asset(
    val id: String,
    val name: String,
    val type: AssetType,
    val unit: String,
    val currency: String,
    val valuationMode: ValuationMode = ValuationMode.PER_UNIT,
    val includeInNetWorth: Boolean = true,
    val isArchived: Boolean = false,
    val note: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    /** Market-priced assets go stale faster than property or vehicles (FBR-29). */
    val staleAfterDays: Int get() = when (type) {
        AssetType.REAL_ESTATE, AssetType.VEHICLE, AssetType.PERSONAL, AssetType.OTHER -> 365
        else -> 30
    }
}

data class AssetTrade(
    val id: String,
    val assetId: String,
    val date: LocalDate,
    val type: TradeType,
    /** Always positive; [type] gives the direction. */
    val quantity: BigDecimal,
    /** Gross price in the asset currency (before fees). */
    val grossMinor: Long,
    val feesMinor: Long = 0,
    val entryGroupId: String? = null,
    val note: String? = null,
    val createdAt: Long = 0,
)

data class AssetValuation(
    val id: String,
    val assetId: String,
    val date: LocalDate,
    val unitPrice: BigDecimal? = null,
    val totalValueMinor: Long? = null,
    val currency: String,
    val source: ValuationSource = ValuationSource.MANUAL,
    val note: String? = null,
    val createdAt: Long = 0,
)

// ---------------- Goals ----------------

enum class GoalMode { ALLOCATION, LINKED_ACCOUNTS }
enum class GoalStatus { ACTIVE, ACHIEVED, ARCHIVED }

data class SavingsGoal(
    val id: String,
    val name: String,
    val targetMinor: Long,
    val currency: String,
    val targetDate: LocalDate? = null,
    val priority: Int = 2,
    val mode: GoalMode = GoalMode.ALLOCATION,
    val status: GoalStatus = GoalStatus.ACTIVE,
    val accountIds: Set<String> = emptySet(),
    val iconKey: String? = null,
    val colorKey: String? = null,
    val note: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

data class GoalContribution(
    val id: String,
    val goalId: String,
    val accountId: String?,
    val date: LocalDate,
    /** Positive = allocate, negative = release. Virtual: never moves money (FBR-23). */
    val amountMinor: Long,
    val note: String? = null,
    val createdAt: Long = 0,
)
