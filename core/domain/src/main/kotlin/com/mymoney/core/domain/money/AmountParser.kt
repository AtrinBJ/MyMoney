package com.mymoney.core.domain.money

import com.mymoney.core.domain.common.AmountProblem
import com.mymoney.core.domain.common.DomainError
import com.mymoney.core.domain.common.Outcome
import com.mymoney.core.domain.common.failure
import com.mymoney.core.domain.common.success
import com.mymoney.core.domain.settings.IrrUnit
import java.math.BigDecimal

/**
 * Parses user-typed amounts into minor units without floating point.
 *
 * Accepts Persian, Arabic-Indic and Latin digits; `,` `٬` spaces and apostrophes as grouping;
 * `.` or `٫` as the decimal point; a leading `-`/`−` or surrounding parentheses for negatives.
 * IRR typed in toman is multiplied by exactly 10 (FBR-22) and may carry one decimal (0.5 toman = 5 rial).
 */
object AmountParser {

    fun parse(
        input: String,
        currency: String,
        minorDigits: Int,
        irrUnit: IrrUnit = IrrUnit.RIAL,
        allowNegative: Boolean = false,
    ): Outcome<Long> {
        var text = Digits.normalize(input).trim()
        if (text.isEmpty()) return DomainError.Required("amount").failure()

        var negative = false
        if (text.startsWith("(") && text.endsWith(")")) {
            negative = true
            text = text.substring(1, text.length - 1).trim()
        }
        if (text.startsWith("-")) {
            negative = !negative
            text = text.substring(1).trim()
        } else if (text.startsWith("+")) {
            text = text.substring(1).trim()
        }
        text = text.filterNot { it == ',' || it == ' ' || it == ' ' || it == '\'' || it == ' ' }

        if (text.isEmpty() || !NUMBER.matches(text)) {
            return DomainError.InvalidAmount(AmountProblem.UNPARSABLE).failure()
        }
        if (negative && !allowNegative) return DomainError.InvalidAmount(AmountProblem.NEGATIVE_NOT_ALLOWED).failure()

        val tomanMode = currency == Currency.IRR && irrUnit == IrrUnit.TOMAN
        val allowedDecimals = if (tomanMode) 1 else minorDigits
        val value = BigDecimal(text)
        if (value.stripTrailingZeros().scale() > allowedDecimals) {
            return DomainError.InvalidAmount(AmountProblem.TOO_MANY_DECIMALS).failure()
        }
        val inStorageMajor = if (tomanMode) value.movePointRight(1) else value
        val minorBig = inStorageMajor.movePointRight(minorDigits).setScale(0)
        if (minorBig.abs() > BigDecimal.valueOf(MoneyMath.MAX_ABS_MINOR)) {
            return DomainError.InvalidAmount(AmountProblem.TOO_LARGE).failure()
        }
        val minor = minorBig.longValueExact()
        return (if (negative) -minor else minor).success()
    }

    private val NUMBER = Regex("""\d+(\.\d*)?|\.\d+""")
}
