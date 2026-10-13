package com.mymoney.core.domain.calc

import java.time.LocalDate

/** FBR-28 projection. Always shown as an estimate with its assumptions. */
data class Forecast(
    val asOf: LocalDate,
    val until: LocalDate,
    val liquidNowMinor: Long,
    val scheduledInMinor: Long,
    val scheduledOutMinor: Long,
    val pendingMinor: Long,
    /** Typical (median) weekly-rate variable spending × remaining days; null when there is too little history. */
    val variableMedianMinor: Long?,
    val variableLowMinor: Long?,
    val variableHighMinor: Long?,
    val historyDays: Int,
) {
    private val base: Long get() = liquidNowMinor + scheduledInMinor - scheduledOutMinor + pendingMinor
    val projectedMinor: Long get() = base - (variableMedianMinor ?: 0)
    /** Pessimistic: higher variable spending (75th percentile). */
    val lowMinor: Long get() = base - (variableHighMinor ?: 0)
    /** Optimistic: lower variable spending (25th percentile). */
    val highMinor: Long get() = base - (variableLowMinor ?: 0)
    val hasVariableEstimate: Boolean get() = variableMedianMinor != null
}

object MonthEndForecast {
    const val MIN_HISTORY_DAYS = 30
    const val LOOKBACK_DAYS = 90
    const val WINDOW_DAYS = 7

    /**
     * [dailyVariableSpend] = per-day spending (positive) of non-recurring expenses minus refunds for the last
     * [LOOKBACK_DAYS] days, one value per calendar day (zeros included). [scheduled*] are outstanding amounts of
     * planned items due by [until] (overdue included).
     */
    fun compute(
        today: LocalDate,
        until: LocalDate,
        liquidNowMinor: Long,
        scheduledInMinor: Long,
        scheduledOutMinor: Long,
        pendingMinor: Long,
        dailyVariableSpend: List<Long>,
        /** Estimates are rounded to this step so they do not look more precise than they are. */
        roundTo: Long = 1,
    ): Forecast {
        val remainingDays = (until.toEpochDay() - today.toEpochDay()).coerceAtLeast(0)
        val enough = dailyVariableSpend.size >= MIN_HISTORY_DAYS
        // Spending is lumpy (many zero days), so a daily median is often 0. Use the spread of rolling
        // 7-day totals instead: median/25th/75th percentile of the per-day rate over each week.
        val weekly = if (enough) dailyVariableSpend.windowed(WINDOW_DAYS).map { it.sum().toDouble() / WINDOW_DAYS }.sorted() else emptyList()
        fun pct(p: Double): Long = if (weekly.isEmpty()) 0 else Math.round(weekly[((weekly.size - 1) * p).toInt()] * remainingDays / roundTo) * roundTo
        return Forecast(
            asOf = today,
            until = until,
            liquidNowMinor = liquidNowMinor,
            scheduledInMinor = scheduledInMinor,
            scheduledOutMinor = scheduledOutMinor,
            pendingMinor = pendingMinor,
            variableMedianMinor = if (enough) pct(0.5) else null,
            variableLowMinor = if (enough) pct(0.25) else null,
            variableHighMinor = if (enough) pct(0.75) else null,
            historyDays = dailyVariableSpend.size,
        )
    }
}
