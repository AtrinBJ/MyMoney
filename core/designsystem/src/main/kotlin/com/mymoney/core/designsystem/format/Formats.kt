package com.mymoney.core.designsystem.format

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import com.mymoney.core.domain.calendar.CalendarSystem
import com.mymoney.core.domain.calendar.DateText
import com.mymoney.core.domain.calendar.Periods
import com.mymoney.core.domain.money.Currency
import com.mymoney.core.domain.money.MoneyFormatter
import com.mymoney.core.domain.settings.AppLanguage
import com.mymoney.core.domain.settings.AppSettings

/**
 * Everything the UI needs to show money and dates consistently, derived once from settings
 * and provided through [LocalFormats]. Formatting logic itself lives in the domain (tested there).
 */
@Immutable
class Formats(
    val settings: AppSettings,
    val language: AppLanguage,
    val currencies: Map<String, Currency>,
) {
    val money = MoneyFormatter(language, settings.digits, settings.irrUnit, currencies)
    val calendar: CalendarSystem = CalendarSystem.of(settings.calendar)
    val dates = DateText(calendar, language, settings.digits)
    val periods = Periods(calendar, settings.weekStart)
    val hideAmounts: Boolean get() = settings.hideAmounts
    val baseCurrency: String get() = settings.baseCurrency

    fun minorDigits(currency: String): Int = currencies[currency]?.minorDigits ?: 2

    fun currencyName(code: String): String = currencies[code]?.let {
        if (language == AppLanguage.FA) it.nameFa else it.nameEn
    } ?: code
}

val LocalFormats = staticCompositionLocalOf<Formats> { error("Formats not provided") }
