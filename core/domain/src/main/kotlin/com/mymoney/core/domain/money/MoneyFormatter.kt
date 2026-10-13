package com.mymoney.core.domain.money

import com.mymoney.core.domain.settings.AppLanguage
import com.mymoney.core.domain.settings.DigitStyle
import com.mymoney.core.domain.settings.IrrUnit
import java.math.BigDecimal
import java.math.RoundingMode

/** How the sign of an amount is shown. */
enum class SignDisplay { NEGATIVE_ONLY, ALWAYS, NEVER }

/**
 * Locale-independent, testable money formatting.
 *
 * The sign and digits are wrapped in LTR isolates so the minus sign and separators stay in place inside
 * RTL text. Words (currency names, magnitude words) stay outside the isolate so in Persian they read after
 * the number. English uses a prefix symbol for `$ € £ ¥ …` ("−$1,250.00").
 */
class MoneyFormatter(
    private val language: AppLanguage,
    private val digits: DigitStyle,
    private val irrUnit: IrrUnit,
    private val currencies: Map<String, Currency>,
) {
    private data class Parts(val sign: String, val number: String, val magnitude: String)

    fun format(
        minor: Long,
        currency: String,
        sign: SignDisplay = SignDisplay.NEGATIVE_ONLY,
        withCurrency: Boolean = true,
        compact: Boolean = false,
    ): String {
        val p = parts(minor, currency, sign, compact)
        if (!withCurrency) return assemble(p, prefixSymbol = "")
        val label = label(currency)
        return if (usesPrefixSymbol(currency)) {
            assemble(p, prefixSymbol = label)
        } else {
            assemble(p, prefixSymbol = "") + " " + label
        }
    }

    /** Number with sign and separators, without the currency label. */
    fun formatNumber(
        minor: Long,
        currency: String,
        sign: SignDisplay = SignDisplay.NEGATIVE_ONLY,
        compact: Boolean = false,
    ): String = assemble(parts(minor, currency, sign, compact), prefixSymbol = "")

    /** Value in the unit the user sees (toman for IRR when chosen), exact — never rounded. */
    fun displayMajor(minor: Long, currency: String): BigDecimal {
        val digitsOf = currencies[currency]?.minorDigits ?: 2
        val major = BigDecimal.valueOf(minor, digitsOf)
        return if (currency == Currency.IRR && irrUnit == IrrUnit.TOMAN) {
            major.movePointLeft(1).stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
        } else {
            major
        }
    }

    fun label(currency: String): String = when (currency) {
        Currency.IRR -> when (irrUnit) {
            IrrUnit.TOMAN -> if (language == AppLanguage.FA) "تومان" else "Toman"
            IrrUnit.RIAL -> if (language == AppLanguage.FA) "ریال" else "Rial"
        }
        else -> currencies[currency]?.symbol?.takeIf { it.isNotBlank() } ?: currency
    }

    private fun usesPrefixSymbol(currency: String): Boolean =
        language == AppLanguage.EN && currency != Currency.IRR &&
            currencies[currency]?.symbol?.let { it.length <= 2 && !it.first().isLetter() } == true

    private fun parts(minor: Long, currency: String, sign: SignDisplay, compact: Boolean): Parts {
        val abs = displayMajor(minor, currency).abs()
        val (number, magnitude) = if (compact) compact(abs) else group(abs) to ""
        val signText = when {
            sign == SignDisplay.NEVER -> ""
            minor < 0 -> Digits.MINUS.toString()
            minor > 0 && sign == SignDisplay.ALWAYS -> "+"
            else -> ""
        }
        return Parts(signText, number, magnitude)
    }

    private fun assemble(p: Parts, prefixSymbol: String): String {
        val core = Digits.isolateLtr(p.sign + prefixSymbol + Digits.localize(p.number, digits))
        return when {
            p.magnitude.isEmpty() -> core
            language == AppLanguage.FA -> "$core ${p.magnitude}"
            else -> Digits.isolateLtr(p.sign + prefixSymbol + Digits.localize(p.number, digits) + p.magnitude)
        }
    }

    private fun group(value: BigDecimal): String {
        val plain = value.toPlainString()
        val intPart = plain.substringBefore('.')
        val frac = plain.substringAfter('.', "")
        val grouped = intPart.reversed().chunked(3).joinToString(",").reversed()
        return if (frac.isEmpty()) grouped else "$grouped.$frac"
    }

    /** Short form for charts: number + magnitude (K/M/B/T or هزار/میلیون/میلیارد/همت). */
    private fun compact(value: BigDecimal): Pair<String, String> {
        val fa = language == AppLanguage.FA
        val thresholds = listOf(
            BigDecimal("1000000000000") to (if (fa) "همت" else "T"),
            BigDecimal("1000000000") to (if (fa) "میلیارد" else "B"),
            BigDecimal("1000000") to (if (fa) "میلیون" else "M"),
            BigDecimal("1000") to (if (fa) "هزار" else "K"),
        )
        for ((t, word) in thresholds) {
            if (value >= t) {
                val v = value.divide(t, 1, RoundingMode.HALF_EVEN).stripTrailingZeros()
                return v.toPlainString() to word
            }
        }
        return group(value.setScale(0, RoundingMode.HALF_EVEN)) to ""
    }
}
