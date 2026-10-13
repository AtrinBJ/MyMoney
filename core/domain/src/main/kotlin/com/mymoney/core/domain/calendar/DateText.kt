package com.mymoney.core.domain.calendar

import com.mymoney.core.domain.money.Digits
import com.mymoney.core.domain.settings.AppLanguage
import com.mymoney.core.domain.settings.CalendarType
import com.mymoney.core.domain.settings.DigitStyle
import java.time.DayOfWeek
import java.time.LocalDate

/** Date display in the chosen calendar, language and digits. Pure Kotlin so CSV/PDF and UI agree. */
class DateText(
    private val calendar: CalendarSystem,
    private val language: AppLanguage,
    private val digits: DigitStyle,
) {
    /** 1405/07/21 or 2026/10/13 */
    fun short(date: LocalDate): String {
        val ymd = calendar.toYmd(date)
        return Digits.localize("%04d/%02d/%02d".format(ymd.year, ymd.month, ymd.day), digits)
    }

    /** ۲۱ مهر ۱۴۰۵ / 13 October 2026 */
    fun medium(date: LocalDate): String {
        val ymd = calendar.toYmd(date)
        return Digits.localize("${ymd.day} ${monthName(ymd.month)} ${ymd.year}", digits)
    }

    /** دوشنبه ۲۱ مهر ۱۴۰۵ / Monday, 13 October 2026 */
    fun long(date: LocalDate): String {
        val sep = if (language == AppLanguage.FA) " " else ", "
        return weekdayName(date.dayOfWeek) + sep + medium(date)
    }

    /** مهر ۱۴۰۵ / October 2026 */
    fun monthYear(date: LocalDate): String {
        val ymd = calendar.toYmd(date)
        return Digits.localize("${monthName(ymd.month)} ${ymd.year}", digits)
    }

    /** ۲۱ مهر / 13 Oct — compact, for list headers and chart axes. */
    fun dayMonth(date: LocalDate): String {
        val ymd = calendar.toYmd(date)
        return Digits.localize("${ymd.day} ${monthName(ymd.month, short = true)}", digits)
    }

    fun monthName(month: Int, short: Boolean = false): String {
        val names = when (calendar.type) {
            CalendarType.JALALI -> if (language == AppLanguage.FA) JALALI_FA else JALALI_EN
            CalendarType.GREGORIAN -> if (language == AppLanguage.FA) GREGORIAN_FA else GREGORIAN_EN
        }
        val name = names[month - 1]
        return if (short && language == AppLanguage.EN) name.take(3) else name
    }

    fun weekdayName(day: DayOfWeek, short: Boolean = false): String {
        val name = if (language == AppLanguage.FA) WEEKDAYS_FA[day.value - 1] else WEEKDAYS_EN[day.value - 1]
        return if (!short) name else if (language == AppLanguage.FA) name.take(1) else name.take(3)
    }

    companion object {
        val JALALI_FA = listOf(
            "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
            "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند",
        )
        val JALALI_EN = listOf(
            "Farvardin", "Ordibehesht", "Khordad", "Tir", "Mordad", "Shahrivar",
            "Mehr", "Aban", "Azar", "Dey", "Bahman", "Esfand",
        )
        val GREGORIAN_FA = listOf(
            "ژانویه", "فوریه", "مارس", "آوریل", "مه", "ژوئن",
            "ژوئیه", "اوت", "سپتامبر", "اکتبر", "نوامبر", "دسامبر",
        )
        val GREGORIAN_EN = listOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December",
        )

        // Indexed by ISO day-of-week − 1 (Monday first).
        val WEEKDAYS_FA = listOf("دوشنبه", "سه‌شنبه", "چهارشنبه", "پنجشنبه", "جمعه", "شنبه", "یکشنبه")
        val WEEKDAYS_EN = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    }
}
