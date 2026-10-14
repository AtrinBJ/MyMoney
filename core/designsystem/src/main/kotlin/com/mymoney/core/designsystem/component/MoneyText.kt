package com.mymoney.core.designsystem.component

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.mymoney.core.designsystem.format.LocalFormats
import com.mymoney.core.designsystem.theme.MyMoneyTheme
import com.mymoney.core.domain.money.Digits
import com.mymoney.core.domain.money.SignDisplay

enum class AmountColor { NEUTRAL, SIGNED, INCOME, EXPENSE, TRANSFER }

/**
 * The only way the app shows money. Applies the user's digits, IRR unit and the privacy mask (docs/06 §6.5).
 * Color is never the only signal: the sign is always part of the text.
 */
@Composable
fun MoneyText(
    minor: Long,
    currency: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: AmountColor = AmountColor.NEUTRAL,
    sign: SignDisplay = SignDisplay.NEGATIVE_ONLY,
    compact: Boolean = false,
    withCurrency: Boolean = true,
) {
    val formats = LocalFormats.current
    val finance = MyMoneyTheme.finance
    val text = if (formats.hideAmounts) {
        MASK + if (withCurrency) " " + formats.money.label(currency) else ""
    } else {
        formats.money.format(minor, currency, sign, withCurrency, compact)
    }
    val resolved: Color = when (color) {
        AmountColor.NEUTRAL -> LocalContentColor.current
        AmountColor.INCOME -> finance.income
        AmountColor.EXPENSE -> finance.expense
        AmountColor.TRANSFER -> finance.transfer
        AmountColor.SIGNED -> when {
            minor > 0 -> finance.income
            minor < 0 -> finance.expense
            else -> LocalContentColor.current
        }
    }
    val spoken = text.replace(Digits.LRI.toString(), "").replace(Digits.PDI.toString(), "")
    // Amounts are never truncated (docs/06 §6.5). First the text shrinks to fit one line (down to 9sp); if it
    // still does not fit (huge number + large font + narrow column) it wraps, but only after a digit-group
    // separator, so every digit stays visible and groups are never split.
    val max = if (style.fontSize.isSpecified) style.fontSize else 14.sp
    var wrap by remember(text, style) { mutableStateOf(false) }
    val described = modifier.semantics { contentDescription = spoken }
    if (!wrap) {
        BasicText(
            text = text,
            modifier = described,
            style = style.merge(color = resolved),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            autoSize = TextAutoSize.StepBased(minFontSize = minOf(9f, max.value).sp, maxFontSize = max, stepSize = 0.5.sp),
            onTextLayout = { if (it.hasVisualOverflow) wrap = true },
        )
    } else {
        BasicText(
            text = text.replace(Regex("([,٬])"), "$1​"),
            modifier = described,
            style = style.merge(color = resolved),
            softWrap = true,
        )
    }
}

private const val MASK = "••••••"
