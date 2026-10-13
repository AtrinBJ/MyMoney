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
    /** Median daily variable spending × remaining days; null when there is too little history. */
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
    ): Forecast {
        val remainingDays = (until.toEpochDay() - today.toEpochDay()).coerceAtLeast(0)
        val enough = dailyVariableSpend.size >= MIN_HISTORY_DAYS
        val sorted = dailyVariableSpend.sorted()
        fun pct(p: Double): Long = if (sorted.isEmpty()) 0 else sorted[((sorted.size - 1) * p).toInt()]
        return Forecast(
            asOf = today,
            until = until,
            liquidNowMinor = liquidNowMinor,
            scheduledInMinor = scheduledInMinor,
            scheduledOutMinor = scheduledOutMinor,
            pendingMinor = pendingMinor,
            variableMedianMinor = if (enough) pct(0.5) * remainingDays else null,
            variableLowMinor = if (enough) pct(0.25) * remainingDays else null,
            variableHighMinor = if (enough) pct(0.75) * remainingDays else null,
            historyDays = dailyVariableSpend.size,
        )
    }
}
