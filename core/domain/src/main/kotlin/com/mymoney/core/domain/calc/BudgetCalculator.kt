package com.mymoney.core.domain.calc

import com.mymoney.core.domain.calendar.CalendarSystem
import com.mymoney.core.domain.calendar.DateRange
import com.mymoney.core.domain.calendar.PeriodType
import com.mymoney.core.domain.calendar.Periods
import com.mymoney.core.domain.model.Budget
import com.mymoney.core.domain.model.BudgetPeriodType
import com.mymoney.core.domain.model.BudgetScope
import com.mymoney.core.domain.model.Category
import com.mymoney.core.domain.model.CategoryType
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.model.EntryStatus
import com.mymoney.core.domain.model.RolloverMode
import com.mymoney.core.domain.money.MoneyMath
import com.mymoney.core.domain.repository.CategoryAmountRow
import com.mymoney.core.domain.settings.WeekStart
import java.time.LocalDate

/** A category-amount row tagged with its status, so pending spending can be shown separately (FBR-11). */
data class BudgetRow(val row: CategoryAmountRow, val status: EntryStatus)

data class BudgetStatus(
    val budget: Budget,
    val period: DateRange,
    /** Base limit (or this period's override) without rollover. */
    val baseLimitMinor: Long,
    val rolloverMinor: Long,
    val spentMinor: Long,
    /** Pending ("committed") spending in the period; counted in [usedMinor] only if includePending. */
    val pendingMinor: Long,
    val missing: List<MissingRate>,
) {
    val limitMinor: Long get() = MoneyMath.add(baseLimitMinor, rolloverMinor)
    val usedMinor: Long get() = if (budget.includePending) MoneyMath.add(spentMinor, pendingMinor) else spentMinor
    val remainingMinor: Long get() = MoneyMath.subtract(limitMinor, usedMinor)
    val fraction: Float get() = if (limitMinor <= 0) (if (usedMinor > 0) 1f else 0f) else usedMinor.toFloat() / limitMinor.toFloat()

    /** Highest configured threshold reached, e.g. 80 when 85% is used (FBR-19). */
    val thresholdReached: Int? get() = budget.alertThresholds.filter { fraction * 100 >= it }.maxOrNull()

    /** Linear projection of spending to the end of the period, given today's position (FBR-29 "budget at risk"). */
    fun projectedUse(today: LocalDate): Long? {
        if (today !in period) return null
        val elapsed = today.toEpochDay() - period.startEpochDay + 1
        if (elapsed < 3) return null
        return usedMinor * period.lengthInDays / elapsed
    }
}

/**
 * Budget usage (FBR-19) and rollover (FBR-20). Only EXPENSE and REFUND rows count; transfers,
 * adjustments, asset purchases and debt principal never reach a budget.
 */
class BudgetCalculator(
    private val categories: List<Category>,
    private val rates: RateBook,
    private val weekStart: WeekStart,
) {
    private val childrenOf = categories.groupBy { it.parentId }

    fun periodOf(budget: Budget, date: LocalDate): DateRange {
        if (budget.periodType == BudgetPeriodType.CUSTOM) {
            return DateRange(budget.startDate, budget.endDate ?: budget.startDate)
        }
        val periods = Periods(CalendarSystem.of(budget.calendar), weekStart)
        return periods.rangeOf(periodType(budget), date)
    }

    /** Categories counted by the budget; selecting a parent includes its subcategories. */
    fun includedCategories(budget: Budget): Set<String>? = when (budget.scope) {
        BudgetScope.TOTAL -> null // all expense categories
        BudgetScope.CATEGORIES -> budget.categoryIds.flatMap { id -> listOf(id) + childrenOf[id].orEmpty().map { it.id } }.toSet()
    }

    /**
     * Status for the period containing [date]. [rows] must cover from the budget's start date (or the
     * rollover horizon) through the end of that period.
     */
    fun status(budget: Budget, date: LocalDate, rows: List<BudgetRow>): BudgetStatus {
        val period = periodOf(budget, date)
        val included = includedCategories(budget)
        val expenseIds = if (included == null) categories.filter { it.type == CategoryType.EXPENSE }.map { it.id }.toSet() else null
        val missing = mutableMapOf<String, Long>()

        fun counts(r: CategoryAmountRow): Boolean {
            if (r.kind != EntryKind.EXPENSE && r.kind != EntryKind.REFUND) return false
            val cat = r.categoryId ?: return included == null
            return included?.contains(cat) ?: (cat in expenseIds!!)
        }

        fun spentIn(range: DateRange, status: EntryStatus): Long {
            var total = 0L
            rows.forEach { br ->
                val r = br.row
                if (br.status != status || r.date !in range || !counts(r)) return@forEach
                val converted = rates.convert(r.amountMinor, r.currency, budget.currency, r.date)
                if (converted == null) {
                    missing.merge(r.currency, r.amountMinor, MoneyMath::add)
                    return@forEach
                }
                // Expenses are negative, refunds positive: spent = −(sum).
                total = MoneyMath.subtract(total, converted.minor)
            }
            return total
        }

        val rollover = rolloverInto(budget, period, ::spentIn)
        return BudgetStatus(
            budget = budget,
            period = period,
            baseLimitMinor = budget.overrides[period.start] ?: budget.amountMinor,
            rolloverMinor = rollover,
            spentMinor = spentIn(period, EntryStatus.COMPLETED),
            pendingMinor = spentIn(period, EntryStatus.PENDING),
            missing = missing.map { MissingRate(it.key, it.value) },
        )
    }

    /** FBR-20: carry from the first period of the budget up to [period], at most 120 periods back. */
    private fun rolloverInto(budget: Budget, period: DateRange, spentIn: (DateRange, EntryStatus) -> Long): Long {
        if (budget.rollover == RolloverMode.NONE || budget.periodType == BudgetPeriodType.CUSTOM) return 0
        val chain = ArrayDeque<DateRange>()
        var p = previous(budget, period)
        while (p != null && !p.endInclusive.isBefore(budget.startDate) && chain.size < 120) {
            chain.addFirst(p)
            p = previous(budget, p)
        }
        var carry = 0L
        chain.forEach { range ->
            val limit = MoneyMath.add(budget.overrides[range.start] ?: budget.amountMinor, carry)
            val used = spentIn(range, EntryStatus.COMPLETED).let { if (budget.includePending) MoneyMath.add(it, spentIn(range, EntryStatus.PENDING)) else it }
            val left = MoneyMath.subtract(limit, used)
            carry = when (budget.rollover) {
                RolloverMode.CARRY_UNUSED -> left.coerceAtLeast(0)
                RolloverMode.CARRY_ALL -> left
                RolloverMode.NONE -> 0
            }
        }
        return carry
    }

    private fun previous(budget: Budget, range: DateRange): DateRange? {
        if (budget.periodType == BudgetPeriodType.CUSTOM) return null
        return Periods(CalendarSystem.of(budget.calendar), weekStart).previous(periodType(budget), range)
    }

    private fun periodType(budget: Budget) = when (budget.periodType) {
        BudgetPeriodType.WEEKLY -> PeriodType.WEEK
        BudgetPeriodType.MONTHLY -> PeriodType.MONTH
        BudgetPeriodType.YEARLY -> PeriodType.YEAR
        BudgetPeriodType.CUSTOM -> PeriodType.DAY
    }

    /** Earliest date whose rows are needed to evaluate [budget] at [date] (rollover chain included). */
    fun rowsNeededFrom(budget: Budget, date: LocalDate): LocalDate {
        val period = periodOf(budget, date)
        return if (budget.rollover == RolloverMode.NONE) period.start else maxOf(budget.startDate, period.start.minusYears(10))
    }
}
