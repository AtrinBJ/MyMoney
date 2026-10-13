package com.mymoney.app.ui.common

import com.mymoney.core.designsystem.format.Formats
import com.mymoney.core.domain.common.Outcome
import com.mymoney.core.domain.money.AmountParser
import com.mymoney.core.domain.money.Currency
import com.mymoney.core.domain.settings.IrrUnit
import java.math.BigDecimal

/** Parses an AmountField string in the user's display unit (toman/rial) to minor units (FBR-22). */
fun Formats.parseAmount(text: String, currency: String, allowNegative: Boolean = false): Outcome<Long> =
    AmountParser.parse(text, currency, minorDigits(currency), settings.irrUnit, allowNegative)

fun Formats.parseAmountOrNull(text: String, currency: String, allowNegative: Boolean = false): Long? =
    (parseAmount(text, currency, allowNegative) as? Outcome.Success)?.value

/** Minor units → canonical editable string in the unit the user types. Empty for zero when [blankZero]. */
fun Formats.editableAmount(minor: Long, currency: String, blankZero: Boolean = false): String {
    if (minor == 0L && blankZero) return ""
    var v = BigDecimal.valueOf(minor, minorDigits(currency))
    if (currency == Currency.IRR && settings.irrUnit == IrrUnit.TOMAN) v = v.movePointLeft(1)
    return v.stripTrailingZeros().toPlainString()
}
