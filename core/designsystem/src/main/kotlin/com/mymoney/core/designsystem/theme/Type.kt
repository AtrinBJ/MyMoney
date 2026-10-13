package com.mymoney.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mymoney.core.designsystem.R

/** Vazirmatn UI (SIL OFL 1.1) — covers Persian and Latin, so both languages share one typographic voice. */
val Vazirmatn = FontFamily(
    Font(R.font.vazirmatn_ui_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_ui_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_ui_semibold, FontWeight.SemiBold),
    Font(R.font.vazirmatn_ui_bold, FontWeight.Bold),
)

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = Vazirmatn,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

internal val MyMoneyTypography = Typography(
    displayLarge = style(48, 56, FontWeight.SemiBold),
    displayMedium = style(40, 48, FontWeight.SemiBold),
    displaySmall = style(32, 40, FontWeight.SemiBold),
    headlineLarge = style(28, 36, FontWeight.SemiBold),
    headlineMedium = style(26, 34, FontWeight.SemiBold),
    headlineSmall = style(24, 32, FontWeight.SemiBold),
    titleLarge = style(20, 28, FontWeight.SemiBold),
    titleMedium = style(16, 24, FontWeight.Medium),
    titleSmall = style(14, 20, FontWeight.Medium),
    bodyLarge = style(16, 26, FontWeight.Normal),
    bodyMedium = style(14, 22, FontWeight.Normal),
    bodySmall = style(12, 18, FontWeight.Normal),
    labelLarge = style(14, 20, FontWeight.Medium),
    labelMedium = style(12, 16, FontWeight.Medium),
    labelSmall = style(11, 16, FontWeight.Medium),
)
