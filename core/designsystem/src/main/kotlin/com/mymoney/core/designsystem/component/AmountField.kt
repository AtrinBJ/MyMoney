package com.mymoney.core.designsystem.component

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDirection
import com.mymoney.core.designsystem.format.LocalFormats
import com.mymoney.core.domain.money.Digits

/**
 * Money input. Holds a canonical ASCII string ("1250.5"); shows it with the user's digits and live
 * thousands separators. Parsing to minor units is done by the domain AmountParser (no floating point).
 */
@Composable
fun AmountField(
    value: String,
    onValueChange: (String) -> Unit,
    currency: String,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supportingText: String? = null,
    imeAction: ImeAction = ImeAction.Next,
    textStyle: TextStyle? = null,
) {
    val formats = LocalFormats.current
    val display = remember(value, formats) { Digits.localize(group(value), formats.settings.digits) }
    var field by remember { mutableStateOf(TextFieldValue(display, TextRange(display.length))) }
    if (field.text != display) field = TextFieldValue(display, TextRange(display.length))

    OutlinedTextField(
        value = field,
        onValueChange = { tfv ->
            val canonical = canonicalize(tfv.text)
            if (canonical != null) {
                onValueChange(canonical)
                val shown = Digits.localize(group(canonical), formats.settings.digits)
                field = TextFieldValue(shown, TextRange(shown.length))
            }
        },
        label = { Text(label) },
        suffix = { Text(formats.money.label(currency), modifier = Modifier.padding(start = 6.dp)) },
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = imeAction),
        textStyle = (textStyle ?: androidx.compose.material3.LocalTextStyle.current).copy(textDirection = TextDirection.Ltr),
        modifier = modifier,
    )
}

/** Normalizes typed text to "digits[.digits]"; returns null to reject the keystroke. */
internal fun canonicalize(input: String): String? {
    val n = Digits.normalize(input).filterNot { it == ',' || it.isWhitespace() }
    if (n.isEmpty()) return ""
    if (!Regex("""\d*\.?\d*""").matches(n) || n.length > 22) return null
    // "007" → "7", ".5" → "0.5"; the decimal part is kept as typed.
    val intPart = n.substringBefore('.').trimStart('0').ifEmpty { "0" }
    val rest = if (n.contains('.')) "." + n.substringAfter('.') else ""
    return intPart + rest
}

/** "1234567.5" → "1,234,567.5" (ASCII; localized afterwards). */
internal fun group(canonical: String): String {
    if (canonical.isEmpty()) return ""
    val intPart = canonical.substringBefore('.')
    val hasDot = canonical.contains('.')
    val frac = canonical.substringAfter('.', "")
    val grouped = intPart.reversed().chunked(3).joinToString(",").reversed()
    return if (hasDot) "$grouped.$frac" else grouped
}
