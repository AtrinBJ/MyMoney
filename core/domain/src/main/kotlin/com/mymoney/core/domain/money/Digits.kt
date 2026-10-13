package com.mymoney.core.domain.money

import com.mymoney.core.domain.settings.DigitStyle

/** Digit and separator handling for Persian and Latin number display/input. */
object Digits {
    private const val PERSIAN_ZERO = '۰'
    private const val ARABIC_INDIC_ZERO = '٠'

    /** Persian thousands separator (٬, U+066C) and decimal separator (٫, U+066B). */
    const val FA_GROUP = '٬'
    const val FA_DECIMAL = '٫'

    /** Unicode minus (U+2212) for display; ASCII '-' is also accepted on input. */
    const val MINUS = '−'

    /** Bidi isolates: keep sign and digits together in RTL text. */
    const val LRI = '⁦'
    const val PDI = '⁩'

    fun localize(text: String, style: DigitStyle): String = when (style) {
        DigitStyle.LATIN -> text
        DigitStyle.PERSIAN -> buildString(text.length) {
            text.forEach { c ->
                append(
                    when (c) {
                        in '0'..'9' -> PERSIAN_ZERO + (c - '0')
                        ',' -> FA_GROUP
                        '.' -> FA_DECIMAL
                        else -> c
                    },
                )
            }
        }
    }

    /** Converts Persian/Arabic-Indic digits and separators to ASCII; leaves everything else. */
    fun normalize(text: String): String = buildString(text.length) {
        text.forEach { c ->
            append(
                when (c) {
                    in PERSIAN_ZERO..(PERSIAN_ZERO + 9) -> '0' + (c - PERSIAN_ZERO)
                    in ARABIC_INDIC_ZERO..(ARABIC_INDIC_ZERO + 9) -> '0' + (c - ARABIC_INDIC_ZERO)
                    FA_DECIMAL -> '.'
                    FA_GROUP, '،' -> ',' // Persian thousands separator and Arabic comma
                    MINUS -> '-'
                    else -> c
                },
            )
        }
    }

    fun isolateLtr(text: String): String = "$LRI$text$PDI"
}
