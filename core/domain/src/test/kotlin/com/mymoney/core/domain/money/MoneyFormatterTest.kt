package com.mymoney.core.domain.money

import com.mymoney.core.domain.settings.AppLanguage
import com.mymoney.core.domain.settings.DigitStyle
import com.mymoney.core.domain.settings.IrrUnit
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class MoneyFormatterTest {
    private val currencies = listOf(
        Currency("IRR", 0, "ریال", "ریال", "Rial"),
        Currency("USD", 2, "$", "دلار", "US Dollar"),
        Currency("EUR", 2, "€", "یورو", "Euro"),
        Currency("CHF", 2, "CHF", "فرانک", "Swiss Franc"),
    ).associateBy { it.code }

    private fun strip(s: String) = s.replace(Digits.LRI.toString(), "").replace(Digits.PDI.toString(), "")

    @Test
    fun `persian toman with persian digits`() {
        val f = MoneyFormatter(AppLanguage.FA, DigitStyle.PERSIAN, IrrUnit.TOMAN, currencies)
        assertEquals("۱۲۵٬۰۰۰ تومان", strip(f.format(1_250_000, "IRR")))
        assertEquals("−۱۲۵٬۰۰۰٫۵ تومان", strip(f.format(-1_250_005, "IRR")))
        assertEquals("۰ تومان", strip(f.format(0, "IRR")))
    }

    @Test
    fun `rial display is exact and unscaled`() {
        val f = MoneyFormatter(AppLanguage.FA, DigitStyle.LATIN, IrrUnit.RIAL, currencies)
        assertEquals("1,250,005 ریال", strip(f.format(1_250_005, "IRR")))
    }

    @Test
    fun `english prefix symbols keep the sign first`() {
        val f = MoneyFormatter(AppLanguage.EN, DigitStyle.LATIN, IrrUnit.TOMAN, currencies)
        assertEquals("$1,250.00", strip(f.format(125_000, "USD")))
        assertEquals("−€0.05", strip(f.format(-5, "EUR")))
        assertEquals("+€10.00", strip(f.format(1_000, "EUR", SignDisplay.ALWAYS)))
        assertEquals("12.50 CHF", strip(f.format(1_250, "CHF")))
        assertEquals("125,000 Toman", strip(f.format(1_250_000, "IRR")))
    }

    @Test
    fun `numeric part is LTR isolated, label outside`() {
        val f = MoneyFormatter(AppLanguage.FA, DigitStyle.PERSIAN, IrrUnit.TOMAN, currencies)
        val s = f.format(-10, "IRR")
        assertEquals("${Digits.LRI}−۱${Digits.PDI} تومان", s)
    }

    @Test
    fun `compact form`() {
        val fa = MoneyFormatter(AppLanguage.FA, DigitStyle.PERSIAN, IrrUnit.TOMAN, currencies)
        assertEquals("۱٫۲ میلیون", strip(fa.formatNumber(12_000_000, "IRR", compact = true)))
        val en = MoneyFormatter(AppLanguage.EN, DigitStyle.LATIN, IrrUnit.TOMAN, currencies)
        assertEquals("$3.5K", strip(en.format(350_000, "USD", compact = true)))
    }

    @Test
    fun `property - toman display then parse gives back the same rials (FBR-22)`() = runTest {
        val f = MoneyFormatter(AppLanguage.FA, DigitStyle.PERSIAN, IrrUnit.TOMAN, currencies)
        checkAll(Arb.long(-MoneyMath.MAX_ABS_MINOR..MoneyMath.MAX_ABS_MINOR)) { rial ->
            val shown = strip(f.formatNumber(rial, "IRR"))
            val parsed = AmountParser.parse(shown, "IRR", 0, IrrUnit.TOMAN, allowNegative = true)
            assertEquals(com.mymoney.core.domain.common.Outcome.Success(rial), parsed)
        }
    }
}
