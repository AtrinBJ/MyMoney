package com.mymoney.core.domain.calendar

import com.mymoney.core.domain.settings.WeekStart
import java.time.LocalDate

enum class PeriodType { DAY, WEEK, MONTH, YEAR }

/** Period arithmetic in the user's calendar (FBR-25). */
class Periods(
    private val calendar: CalendarSystem,
    private val weekStart: WeekStart,
) {
    fun rangeOf(type: PeriodType, date: LocalDate): DateRange = when (type) {
        PeriodType.DAY -> DateRange(date, date)
        PeriodType.WEEK -> {
            val back = Math.floorMod(date.dayOfWeek.value - weekStart.isoDayOfWeek, 7)
            val start = date.minusDays(back.toLong())
            DateRange(start, start.plusDays(6))
        }
        PeriodType.MONTH -> calendar.monthRange(date)
        PeriodType.YEAR -> calendar.yearRange(date)
    }

    fun previous(type: PeriodType, range: DateRange): DateRange = rangeOf(type, range.start.minusDays(1))

    fun next(type: PeriodType, range: DateRange): DateRange = rangeOf(type, range.endInclusive.plusDays(1))

    /** The [count] most recent periods ending with the one containing [date], oldest first. */
    fun lastN(type: PeriodType, date: LocalDate, count: Int): List<DateRange> {
        require(count > 0)
        val out = ArrayDeque<DateRange>()
        var r = rangeOf(type, date)
        repeat(count) {
            out.addFirst(r)
            r = previous(type, r)
        }
        return out.toList()
    }

    /** Same-length range immediately before [range] (for custom-range comparisons). */
    fun precedingOfSameLength(range: DateRange): DateRange {
        val len = range.lengthInDays
        val end = range.start.minusDays(1)
        return DateRange(end.minusDays(len - 1), end)
    }
}
