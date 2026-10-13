package com.mymoney.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Semantic money colors. Color is never the only signal: amounts also carry a sign and an icon. */
@Immutable
data class FinanceColors(
    val income: Color,
    val expense: Color,
    val transfer: Color,
    val warning: Color,
    val incomeContainer: Color,
    val expenseContainer: Color,
    val transferContainer: Color,
    val warningContainer: Color,
    /** Categorical palette for charts and category/account color keys. */
    val chart: List<Color>,
)

internal val LightFinanceColors = FinanceColors(
    income = Color(0xFF1B7F4C),
    expense = Color(0xFFC2413A),
    transfer = Color(0xFF2F6FBF),
    warning = Color(0xFFA35C00),
    incomeContainer = Color(0xFFD4F5E1),
    expenseContainer = Color(0xFFFFDAD6),
    transferContainer = Color(0xFFD8E6FF),
    warningContainer = Color(0xFFFFDDB8),
    chart = listOf(
        Color(0xFF0F6E5F), Color(0xFF4A5BB0), Color(0xFFB4690E), Color(0xFFB03A6B),
        Color(0xFF2F7FA8), Color(0xFF6E8B1F), Color(0xFF8A4FBF), Color(0xFFC2413A),
        Color(0xFF00897B), Color(0xFF5D6B78),
    ),
)

internal val DarkFinanceColors = FinanceColors(
    income = Color(0xFF6DDB9B),
    expense = Color(0xFFFF8A80),
    transfer = Color(0xFF9CC3FF),
    warning = Color(0xFFFFB86B),
    incomeContainer = Color(0xFF0F3D26),
    expenseContainer = Color(0xFF5C1A15),
    transferContainer = Color(0xFF16345C),
    warningContainer = Color(0xFF4F2E00),
    chart = listOf(
        Color(0xFF5FD3B8), Color(0xFFBAC3FF), Color(0xFFF5BD48), Color(0xFFFF9EC4),
        Color(0xFF8FD0F5), Color(0xFFC3E27A), Color(0xFFD7B2FF), Color(0xFFFF8A80),
        Color(0xFF6FE0D2), Color(0xFFB7C4D1),
    ),
)

val LocalFinanceColors = staticCompositionLocalOf { LightFinanceColors }
