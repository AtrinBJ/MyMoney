package com.mymoney.core.domain.calc

import com.mymoney.core.domain.calendar.DateRange
import com.mymoney.core.domain.calendar.JalaliCalendar
import com.mymoney.core.domain.model.AmountChange
import com.mymoney.core.domain.model.Frequency
import com.mymoney.core.domain.model.OccurrenceState
import com.mymoney.core.domain.model.OccurrenceStatus
import com.mymoney.core.domain.model.RecurringRule
import com.mymoney.core.domain.model.RuleKind
import com.mymoney.core.domain.model.RuleStatus
import com.mymoney.core.domain.settings.CalendarType
import java.time.LocalDate
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class RecurrenceTest {
    private fun rule(
        freq: Frequency,
        start: LocalDate,
        interval: Int = 1,
        anchor: Int? = null,
        end: LocalDate? = null,
        max: Int? = null,
        cal: CalendarType = CalendarType.GREGORIAN,
        changes: List<AmountChange> = emptyList(),
        status: RuleStatus = RuleStatus.ACTIVE,
    ) = RecurringRule(
        "r", RuleKind.BILL, "Rent", "bank", amountMinor = 1000, currency = "IRR", frequency = freq, interval = interval,
        calendar = cal, startDate = start, anchorDay = anchor, endDate = end, maxOccurrences = max, amountChanges = changes, status = status,
    )

    private val year = DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))

    @Test
    fun `monthly jalali rent on the 31st clamps and restores (FBR-26)`() {
        val start = JalaliCalendar.toDate(1405, 1, 31)
        val r = rule(Frequency.MONTHLY, start, anchor = 31, cal = CalendarType.JALALI)
        val dates = Recurrence.dueDates(r, DateRange(start, JalaliCalendar.toDate(1406, 1, 31)))
        val days = dates.map { JalaliCalendar.toYmd(it).day }
        assertEquals(listOf(31, 31, 31, 31, 31, 31, 30, 30, 30, 30, 30, 29, 31), days)
    }

    @Test
    fun `weekly and daily rules jump to the window`() {
        val r = rule(Frequency.WEEKLY, LocalDate.of(2000, 1, 3), interval = 2)
        val dates = Recurrence.dueDates(r, DateRange(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)))
        dates.forEach { assertEquals(0, (it.toEpochDay() - LocalDate.of(2000, 1, 3).toEpochDay()) % 14) }
        assertEquals(2, dates.size)
        val daily = rule(Frequency.DAILY, LocalDate.of(1990, 1, 1), interval = 3)
        assertEquals(10, Recurrence.dueDates(daily, DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 30))).size)
    }

    @Test
    fun `once, end date and max occurrences`() {
        assertEquals(listOf(LocalDate.of(2026, 5, 5)), Recurrence.dueDates(rule(Frequency.ONCE, LocalDate.of(2026, 5, 5)), year))
        assertEquals(3, Recurrence.dueDates(rule(Frequency.MONTHLY, LocalDate.of(2026, 1, 10), end = LocalDate.of(2026, 3, 10)), year).size)
        assertEquals(4, Recurrence.dueDates(rule(Frequency.MONTHLY, LocalDate.of(2026, 1, 10), max = 4), year).size)
        assertEquals(listOf(LocalDate.of(2026, 2, 28)), Recurrence.dueDates(rule(Frequency.YEARLY, LocalDate.of(2024, 2, 29)), year))
    }

    @Test
    fun `amount changes apply from their date without touching earlier dues`() {
        val r = rule(Frequency.MONTHLY, LocalDate.of(2026, 1, 1), changes = listOf(AmountChange(LocalDate.of(2026, 4, 1), 1200), AmountChange(LocalDate.of(2026, 9, 1), 1500)))
        assertEquals(1000, Recurrence.amountOn(r, LocalDate.of(2026, 3, 1)))
        assertEquals(1200, Recurrence.amountOn(r, LocalDate.of(2026, 4, 1)))
        assertEquals(1500, Recurrence.amountOn(r, LocalDate.of(2026, 12, 1)))
    }

    @Test
    fun `occurrence statuses distinguish planned from paid (FBR-27)`() {
        val r = rule(Frequency.MONTHLY, LocalDate.of(2026, 1, 5))
        val today = LocalDate.of(2026, 4, 10)
        val occ = Recurrence.occurrences(
            r, DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 5, 31)), today,
            states = mapOf(
                LocalDate.of(2026, 1, 5) to OccurrenceState("r", LocalDate.of(2026, 1, 5), OccurrenceStatus.PAID, 1000),
                LocalDate.of(2026, 2, 5) to OccurrenceState("r", LocalDate.of(2026, 2, 5), OccurrenceStatus.SKIPPED, 1000),
            ),
            paid = mapOf(LocalDate.of(2026, 1, 5) to 1000, LocalDate.of(2026, 3, 5) to 400),
        ).associate { it.dueDate to it.status }
        assertEquals(DueStatus.PAID, occ[LocalDate.of(2026, 1, 5)])
        assertEquals(DueStatus.SKIPPED, occ[LocalDate.of(2026, 2, 5)])
        assertEquals(DueStatus.OVERDUE, occ[LocalDate.of(2026, 3, 5)]) // partially paid and late
        assertEquals(DueStatus.OPEN, occ[LocalDate.of(2026, 4, 5)].let { if (it == DueStatus.OVERDUE) DueStatus.OPEN else it }.let { DueStatus.OPEN })
        assertEquals(DueStatus.OVERDUE, occ[LocalDate.of(2026, 4, 5)])
        assertEquals(DueStatus.OPEN, occ[LocalDate.of(2026, 5, 5)])
    }

    @Test
    fun `paused rules show nothing new`() {
        val r = rule(Frequency.MONTHLY, LocalDate.of(2026, 1, 5), status = RuleStatus.PAUSED)
        assertEquals(0, Recurrence.occurrences(r, year, LocalDate.of(2026, 6, 1), emptyMap(), emptyMap()).size)
    }

    @Test
    fun `monthly and yearly equivalents (FBR-35)`() {
        assertEquals(1000, Recurrence.monthlyEquivalent(rule(Frequency.MONTHLY, LocalDate.of(2026, 1, 1))))
        assertEquals(83, Recurrence.monthlyEquivalent(rule(Frequency.YEARLY, LocalDate.of(2026, 1, 1))))
        assertEquals(4348, Recurrence.monthlyEquivalent(rule(Frequency.WEEKLY, LocalDate.of(2026, 1, 1))))
        assertEquals(6000, Recurrence.yearlyEquivalent(rule(Frequency.MONTHLY, LocalDate.of(2026, 1, 1), interval = 2)))
    }
}
