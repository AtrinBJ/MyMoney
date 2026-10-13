package com.mymoney.core.domain.calc

import com.mymoney.core.domain.calendar.CalendarSystem
import com.mymoney.core.domain.calendar.DateRange
import com.mymoney.core.domain.model.Frequency
import com.mymoney.core.domain.model.OccurrenceState
import com.mymoney.core.domain.model.OccurrenceStatus
import com.mymoney.core.domain.model.RecurringRule
import com.mymoney.core.domain.model.RuleStatus
import java.time.LocalDate

/** Display status of one due date (FBR-27). Planned ≠ paid: OPEN means nothing was recorded yet. */
enum class DueStatus { OPEN, OVERDUE, PARTIAL, PAID, SKIPPED }

data class Occurrence(
    val rule: RecurringRule,
    val dueDate: LocalDate,
    val expectedMinor: Long,
    val status: DueStatus,
    val paidMinor: Long = 0,
) {
    val outstandingMinor: Long get() = if (status == DueStatus.PAID || status == DueStatus.SKIPPED) 0 else (expectedMinor - paidMinor).coerceAtLeast(0)
}

/**
 * Due-date generation (FBR-26). Dates are computed, never stored; only their states are.
 * Monthly/yearly rules keep the anchor day (31 → 30 → 29 → back to 31).
 */
object Recurrence {
    /** Hard cap on generated dates per call, protecting against pathological rules. */
    private const val MAX_ITERATIONS = 20_000

    fun dueDates(rule: RecurringRule, window: DateRange): List<LocalDate> {
        val out = mutableListOf<LocalDate>()
        val cal = CalendarSystem.of(rule.calendar)
        val anchor = rule.anchorDay ?: cal.toYmd(rule.startDate).day
        val step = rule.interval.coerceAtLeast(1)
        var k = when (rule.frequency) {
            // Jump close to the window instead of iterating from the start for day/week rules.
            Frequency.DAILY -> ((window.startEpochDay - rule.startDate.toEpochDay()) / step).coerceAtLeast(0).toInt()
            Frequency.WEEKLY -> ((window.startEpochDay - rule.startDate.toEpochDay()) / (7L * step)).coerceAtLeast(0).toInt()
            else -> 0
        }
        var guard = 0
        while (guard++ < MAX_ITERATIONS) {
            if (rule.maxOccurrences != null && k >= rule.maxOccurrences) break
            val date = when (rule.frequency) {
                Frequency.ONCE -> if (k == 0) rule.startDate else break
                Frequency.DAILY -> rule.startDate.plusDays(k.toLong() * step)
                Frequency.WEEKLY -> rule.startDate.plusWeeks(k.toLong() * step)
                Frequency.MONTHLY -> cal.plusMonths(rule.startDate, k * step, anchor)
                Frequency.YEARLY -> cal.plusYears(rule.startDate, k * step, anchor)
            }
            if (date.isAfter(window.endInclusive)) break
            if (rule.endDate != null && date.isAfter(rule.endDate)) break
            if (!date.isBefore(window.start)) out += date
            k++
        }
        return out
    }

    /** Amount due on [date]: the latest amount change effective on or before it, else the base amount. */
    fun amountOn(rule: RecurringRule, date: LocalDate): Long =
        rule.amountChanges.filter { !it.effectiveFrom.isAfter(date) }.maxByOrNull { it.effectiveFrom }?.amountMinor ?: rule.amountMinor

    /**
     * Occurrences of [rule] in [window] with their state. [paid] maps due date → amount already recorded
     * (sum of linked entries, positive). Paused/ended rules only show what was already recorded.
     */
    fun occurrences(
        rule: RecurringRule,
        window: DateRange,
        today: LocalDate,
        states: Map<LocalDate, OccurrenceState>,
        paid: Map<LocalDate, Long>,
    ): List<Occurrence> {
        val dates = if (rule.status == RuleStatus.ACTIVE) dueDates(rule, window) else dueDates(rule, window).filter { it in states || it in paid }
        return dates.map { d ->
            val expected = states[d]?.expectedMinor ?: amountOn(rule, d)
            val paidMinor = paid[d] ?: 0
            val status = when (states[d]?.status) {
                OccurrenceStatus.PAID -> DueStatus.PAID
                OccurrenceStatus.SKIPPED -> DueStatus.SKIPPED
                OccurrenceStatus.PARTIAL -> if (d.isBefore(today)) DueStatus.OVERDUE else DueStatus.PARTIAL
                null -> when {
                    paidMinor >= expected && paidMinor > 0 -> DueStatus.PAID
                    paidMinor > 0 -> if (d.isBefore(today)) DueStatus.OVERDUE else DueStatus.PARTIAL
                    d.isBefore(today) -> DueStatus.OVERDUE
                    else -> DueStatus.OPEN
                }
            }
            Occurrence(rule, d, expected, status, paidMinor)
        }
    }

    /** Average number of occurrences per year, for monthly/yearly equivalents (FBR-35). */
    fun perYear(rule: RecurringRule): Double {
        val n = rule.interval.coerceAtLeast(1).toDouble()
        return when (rule.frequency) {
            Frequency.ONCE -> 0.0
            Frequency.DAILY -> 365.0 / n
            Frequency.WEEKLY -> 52.1775 / n
            Frequency.MONTHLY -> 12.0 / n
            Frequency.YEARLY -> 1.0 / n
        }
    }

    /** Monthly equivalent in minor units (display/analysis only, rounded half-up to a whole minor unit). */
    fun monthlyEquivalent(rule: RecurringRule, amountMinor: Long = rule.amountMinor): Long =
        Math.round(amountMinor * perYear(rule) / 12.0)

    fun yearlyEquivalent(rule: RecurringRule, amountMinor: Long = rule.amountMinor): Long =
        Math.round(amountMinor * perYear(rule))
}
