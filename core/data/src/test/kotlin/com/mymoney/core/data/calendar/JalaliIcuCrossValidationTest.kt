package com.mymoney.core.data.calendar

import android.icu.util.Calendar
import android.icu.util.TimeZone
import android.icu.util.ULocale
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mymoney.core.domain.calendar.JalaliCalendar
import com.mymoney.core.domain.calendar.YearMonthDay
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ADR-005: the ported jalaali-js algorithm must agree with Android's built-in ICU Persian calendar for
 * every single day of Jalali years 1300…1500 (Gregorian 1921…2122).
 */
@RunWith(AndroidJUnit4::class)
class JalaliIcuCrossValidationTest {

    @Test
    fun `every day 1300-1500 matches ICU persian calendar`() {
        val icu = Calendar.getInstance(ULocale("fa_IR@calendar=persian"))
        icu.timeZone = TimeZone.GMT_ZONE
        var date = JalaliCalendar.toDate(1300, 1, 1)
        val end = JalaliCalendar.toDate(1500, 12, 29)
        val mismatches = mutableListOf<String>()
        var checked = 0
        while (!date.isAfter(end)) {
            icu.timeInMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            val expected = YearMonthDay(
                icu.get(Calendar.EXTENDED_YEAR),
                icu.get(Calendar.MONTH) + 1,
                icu.get(Calendar.DAY_OF_MONTH),
            )
            val actual = JalaliCalendar.toYmd(date)
            if (expected != actual && mismatches.size < 20) mismatches += "$date: ICU=$expected ours=$actual"
            checked++
            date = date.plusDays(1)
        }
        assertTrue(checked > 73_000, "checked $checked days")
        assertEquals(emptyList(), mismatches)
    }

    @Test
    fun `sanity - ICU agrees on a known date`() {
        val icu = Calendar.getInstance(ULocale("fa_IR@calendar=persian"))
        icu.timeZone = TimeZone.GMT_ZONE
        icu.timeInMillis = LocalDate.of(2026, 10, 13).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(1405, icu.get(Calendar.EXTENDED_YEAR))
        assertEquals(7, icu.get(Calendar.MONTH) + 1)
        assertEquals(21, icu.get(Calendar.DAY_OF_MONTH))
    }
}
