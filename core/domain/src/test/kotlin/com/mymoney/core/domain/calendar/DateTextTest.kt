package com.mymoney.core.domain.calendar

import com.mymoney.core.domain.settings.AppLanguage
import com.mymoney.core.domain.settings.DigitStyle
import java.time.LocalDate
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class DateTextTest {
    private val day = LocalDate.of(2026, 10, 13)

    @Test
    fun `persian jalali`() {
        val t = DateText(JalaliCalendar, AppLanguage.FA, DigitStyle.PERSIAN)
        assertEquals("۱۴۰۵/۰۷/۲۱", t.short(day))
        assertEquals("۲۱ مهر ۱۴۰۵", t.medium(day))
        assertEquals("سه‌شنبه ۲۱ مهر ۱۴۰۵", t.long(day))
        assertEquals("مهر ۱۴۰۵", t.monthYear(day))
    }

    @Test
    fun `english gregorian`() {
        val t = DateText(GregorianCalendar, AppLanguage.EN, DigitStyle.LATIN)
        assertEquals("2026/10/13", t.short(day))
        assertEquals("13 October 2026", t.medium(day))
        assertEquals("Tuesday, 13 October 2026", t.long(day))
        assertEquals("13 Oct", t.dayMonth(day))
    }

    @Test
    fun `english with jalali calendar`() {
        val t = DateText(JalaliCalendar, AppLanguage.EN, DigitStyle.LATIN)
        assertEquals("21 Mehr 1405", t.medium(day))
    }
}
