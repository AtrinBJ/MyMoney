package com.mymoney.core.domain.calendar

import com.mymoney.core.domain.settings.CalendarType
import java.time.LocalDate

data class YearMonthDay(val year: Int, val month: Int, val day: Int)

/** Inclusive civil-date range `[start, endInclusive]` (FBR-25). */
data class DateRange(val start: LocalDate, val endInclusive: LocalDate) {
    init {
        require(!endInclusive.isBefore(start)) { "range end before start" }
    }

    val startEpochDay: Long get() = start.toEpochDay()
    val endEpochDay: Long get() = endInclusive.toEpochDay()
    val lengthInDays: Long get() = endEpochDay - startEpochDay + 1

    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(endInclusive)

    fun containsEpochDay(day: Long): Boolean = day in startEpochDay..endEpochDay
}

/**
 * A calendar used for display, month/year boundaries and recurrence. Storage is always [LocalDate]
 * (epoch day), so switching calendars never changes stored data.
 */
interface CalendarSystem {
    val type: CalendarType

    fun toYmd(date: LocalDate): YearMonthDay

    /** Throws [IllegalArgumentException] for an invalid date; use [isValid] first for user input. */
    fun toDate(year: Int, month: Int, day: Int): LocalDate

    fun monthLength(year: Int, month: Int): Int

    fun isLeapYear(year: Int): Boolean

    fun isValid(year: Int, month: Int, day: Int): Boolean =
        month in 1..12 && day >= 1 && runCatching { day <= monthLength(year, month) }.getOrDefault(false)

    fun monthRange(date: LocalDate): DateRange {
        val ymd = toYmd(date)
        return DateRange(toDate(ymd.year, ymd.month, 1), toDate(ymd.year, ymd.month, monthLength(ymd.year, ymd.month)))
    }

    fun yearRange(date: LocalDate): DateRange {
        val y = toYmd(date).year
        return DateRange(toDate(y, 1, 1), toDate(y, 12, monthLength(y, 12)))
    }

    /**
     * Adds [months] keeping the [anchorDay] where possible (FBR-26): the day is clamped to the target
     * month's length, but the anchor is reused for later months (31 Farvardin → 30 Mehr → 31 Farvardin).
     */
    fun plusMonths(date: LocalDate, months: Int, anchorDay: Int = toYmd(date).day): LocalDate {
        val ymd = toYmd(date)
        val total = ymd.year * 12L + (ymd.month - 1) + months
        val year = Math.floorDiv(total, 12L).toInt()
        val month = Math.floorMod(total, 12L).toInt() + 1
        return toDate(year, month, minOf(anchorDay, monthLength(year, month)))
    }

    fun plusYears(date: LocalDate, years: Int, anchorDay: Int = toYmd(date).day): LocalDate =
        plusMonths(date, years * 12, anchorDay)

    companion object {
        fun of(type: CalendarType): CalendarSystem = when (type) {
            CalendarType.JALALI -> JalaliCalendar
            CalendarType.GREGORIAN -> GregorianCalendar
        }
    }
}

object GregorianCalendar : CalendarSystem {
    override val type = CalendarType.GREGORIAN

    override fun toYmd(date: LocalDate) = YearMonthDay(date.year, date.monthValue, date.dayOfMonth)

    override fun toDate(year: Int, month: Int, day: Int): LocalDate {
        require(isValid(year, month, day)) { "invalid Gregorian date" }
        return LocalDate.of(year, month, day)
    }

    override fun monthLength(year: Int, month: Int): Int = java.time.YearMonth.of(year, month).lengthOfMonth()

    override fun isLeapYear(year: Int): Boolean = java.time.Year.isLeap(year.toLong())
}

object JalaliCalendar : CalendarSystem {
    override val type = CalendarType.JALALI

    override fun toYmd(date: LocalDate): YearMonthDay {
        val (y, m, d) = JalaliAlgorithm.d2j(Math.toIntExact(date.toEpochDay() + JalaliAlgorithm.EPOCH_JDN))
        return YearMonthDay(y, m, d)
    }

    override fun toDate(year: Int, month: Int, day: Int): LocalDate {
        require(isValid(year, month, day)) { "invalid Jalali date" }
        return LocalDate.ofEpochDay((JalaliAlgorithm.j2d(year, month, day) - JalaliAlgorithm.EPOCH_JDN).toLong())
    }

    override fun monthLength(year: Int, month: Int): Int {
        require(JalaliAlgorithm.isValidYear(year) && month in 1..12) { "invalid Jalali month" }
        return JalaliAlgorithm.monthLength(year, month)
    }

    override fun isLeapYear(year: Int): Boolean = JalaliAlgorithm.isLeap(year)
}
