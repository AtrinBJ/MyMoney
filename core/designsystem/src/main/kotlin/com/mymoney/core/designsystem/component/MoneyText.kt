package com.mymoney.core.designsystem.component

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    Text(
        text = text,
        modifier = modifier.semantics { contentDescription = spoken },
        style = style,
        color = resolved,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private const val MASK = "••••••"
