package com.mymoney.core.domain.calendar

import com.mymoney.core.domain.settings.WeekStart
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class JalaliCalendarTest {

    @Test
    fun `reference Nowruz dates (1 Farvardin)`() {
        // Official Nowruz dates (Gregorian) published by the University of Tehran Geophysics Institute.
        val nowruz = mapOf(
            1300 to LocalDate.of(1921, 3, 21),
            1354 to LocalDate.of(1975, 3, 21),
            1370 to LocalDate.of(1991, 3, 21),
            1375 to LocalDate.of(1996, 3, 20),
            1390 to LocalDate.of(2011, 3, 21),
            1395 to LocalDate.of(2016, 3, 20),
            1399 to LocalDate.of(2020, 3, 20),
            1400 to LocalDate.of(2021, 3, 21),
            1401 to LocalDate.of(2022, 3, 21),
            1402 to LocalDate.of(2023, 3, 21),
            1403 to LocalDate.of(2024, 3, 20),
            1404 to LocalDate.of(2025, 3, 21),
            1405 to LocalDate.of(2026, 3, 21),
        )
        nowruz.forEach { (jy, date) ->
            assertEquals(date, JalaliCalendar.toDate(jy, 1, 1), "1 Farvardin $jy")
            assertEquals(YearMonthDay(jy, 1, 1), JalaliCalendar.toYmd(date))
        }
    }

    @Test
    fun `known leap years`() {
        listOf(1370, 1375, 1379, 1383, 1387, 1391, 1395, 1399, 1403, 1408).forEach {
            assertTrue(JalaliCalendar.isLeapYear(it), "$it should be leap")
            assertEquals(30, JalaliCalendar.monthLength(it, 12))
        }
        listOf(1400, 1401, 1402, 1404, 1405, 1406, 1407).forEach {
            assertFalse(JalaliCalendar.isLeapYear(it), "$it should not be leap")
            assertEquals(29, JalaliCalendar.monthLength(it, 12))
        }
    }

    @Test
    fun `month lengths and validation`() {
        (1..6).forEach { assertEquals(31, JalaliCalendar.monthLength(1404, it)) }
        (7..11).forEach { assertEquals(30, JalaliCalendar.monthLength(1404, it)) }
        assertFalse(JalaliCalendar.isValid(1404, 12, 30), "30 Esfand 1404 does not exist")
        assertTrue(JalaliCalendar.isValid(1403, 12, 30))
        assertFalse(JalaliCalendar.isValid(1404, 7, 31), "Mehr has 30 days")
        assertFalse(JalaliCalendar.isValid(1404, 13, 1))
        assertFalse(JalaliCalendar.isValid(1404, 1, 0))
    }

    @Test
    fun `today example`() {
        // 2026-10-13 = 21 Mehr 1405
        assertEquals(YearMonthDay(1405, 7, 21), JalaliCalendar.toYmd(LocalDate.of(2026, 10, 13)))
    }

    @Test
    fun `round trip for every day 1900-2200`() {
        var d = LocalDate.of(1900, 1, 1)
        val end = LocalDate.of(2200, 12, 31)
        var prev: YearMonthDay? = null
        while (!d.isAfter(end)) {
            val ymd = JalaliCalendar.toYmd(d)
            assertEquals(d, JalaliCalendar.toDate(ymd.year, ymd.month, ymd.day))
            // consecutive days are consecutive Jalali dates
            if (prev != null) {
                val expected = if (prev.day < JalaliCalendar.monthLength(prev.year, prev.month)) {
                    prev.copy(day = prev.day + 1)
                } else if (prev.month < 12) {
                    YearMonthDay(prev.year, prev.month + 1, 1)
                } else {
                    YearMonthDay(prev.year + 1, 1, 1)
                }
                assertEquals(expected, ymd)
            }
            prev = ymd
            d = d.plusDays(1)
        }
    }

    @Test
    fun `gregorian helper agrees with java time`() {
        var d = LocalDate.of(1900, 1, 1)
        while (d.year < 2201) {
            val jdn = (d.toEpochDay() + JalaliAlgorithm.EPOCH_JDN).toInt()
            assertEquals(Triple(d.year, d.monthValue, d.dayOfMonth), JalaliAlgorithm.d2g(jdn))
            assertEquals(jdn, JalaliAlgorithm.g2d(d.year, d.monthValue, d.dayOfMonth))
            d = d.plusDays(7)
        }
    }

    @Test
    fun `month and year ranges (FBR-25)`() {
        val r = JalaliCalendar.monthRange(LocalDate.of(2026, 10, 13)) // Mehr 1405
        assertEquals(LocalDate.of(2026, 9, 23), r.start)
        assertEquals(LocalDate.of(2026, 10, 22), r.endInclusive)
        val esfand = JalaliCalendar.monthRange(JalaliCalendar.toDate(1403, 12, 15))
        assertEquals(30, esfand.lengthInDays.toInt())
        val year = JalaliCalendar.yearRange(LocalDate.of(2025, 1, 1)) // 1403
        assertEquals(LocalDate.of(2024, 3, 20), year.start)
        assertEquals(LocalDate.of(2025, 3, 20), year.endInclusive)
        assertEquals(366, year.lengthInDays.toInt())
    }

    @Test
    fun `plusMonths keeps the anchor day (FBR-26)`() {
        val start = JalaliCalendar.toDate(1405, 1, 31)
        val dates = (0..12).map { JalaliCalendar.toYmd(JalaliCalendar.plusMonths(start, it, anchorDay = 31)) }
        assertEquals(YearMonthDay(1405, 6, 31), dates[5])
        assertEquals(YearMonthDay(1405, 7, 30), dates[6])
        assertEquals(YearMonthDay(1405, 12, 29), dates[11]) // 1405 not leap
        assertEquals(YearMonthDay(1406, 1, 31), dates[12]) // anchor restored
        // Gregorian: Jan 31 → Feb 28 / 29
        assertEquals(LocalDate.of(2027, 2, 28), GregorianCalendar.plusMonths(LocalDate.of(2027, 1, 31), 1, 31))
        assertEquals(LocalDate.of(2028, 2, 29), GregorianCalendar.plusMonths(LocalDate.of(2028, 1, 31), 1, 31))
        assertEquals(LocalDate.of(2027, 3, 31), GregorianCalendar.plusMonths(LocalDate.of(2027, 1, 31), 2, 31))
        // Negative months
        assertEquals(YearMonthDay(1404, 12, 29), JalaliCalendar.toYmd(JalaliCalendar.plusMonths(start, -1, 31)))
    }

    @Test
    fun `30 Esfand yearly recurrence clamps in common years`() {
        val leapDay = JalaliCalendar.toDate(1403, 12, 30)
        assertEquals(YearMonthDay(1404, 12, 29), JalaliCalendar.toYmd(JalaliCalendar.plusYears(leapDay, 1, 30)))
        assertEquals(YearMonthDay(1408, 12, 30), JalaliCalendar.toYmd(JalaliCalendar.plusYears(leapDay, 5, 30)))
    }

    @Test
    fun `weeks start on the configured day`() {
        val periods = Periods(JalaliCalendar, WeekStart.SATURDAY)
        val w = periods.rangeOf(PeriodType.WEEK, LocalDate.of(2026, 10, 13)) // Tuesday
        assertEquals(LocalDate.of(2026, 10, 10), w.start) // Saturday
        assertEquals(LocalDate.of(2026, 10, 16), w.endInclusive) // Friday
        val mon = Periods(GregorianCalendar, WeekStart.MONDAY).rangeOf(PeriodType.WEEK, LocalDate.of(2026, 10, 13))
        assertEquals(LocalDate.of(2026, 10, 12), mon.start)
    }

    @Test
    fun `lastN returns consecutive periods oldest first`() {
        val periods = Periods(JalaliCalendar, WeekStart.SATURDAY)
        val months = periods.lastN(PeriodType.MONTH, LocalDate.of(2026, 4, 1), 3) // Farvardin 1405
        assertEquals(listOf(11, 12, 1), months.map { JalaliCalendar.toYmd(it.start).month })
        months.zipWithNext().forEach { (a, b) -> assertEquals(a.endInclusive.plusDays(1), b.start) }
    }
}
