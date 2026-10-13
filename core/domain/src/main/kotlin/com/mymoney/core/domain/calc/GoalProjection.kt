package com.mymoney.core.domain.calc

import com.mymoney.core.domain.model.GoalContribution
import com.mymoney.core.domain.model.GoalMode
import com.mymoney.core.domain.model.SavingsGoal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

data class GoalProgress(
    val goal: SavingsGoal,
    val currentMinor: Long,
    val remainingMinor: Long,
    val fraction: Float,
    /** Needed per month to reach the target by its date; null without a date or when already reached. */
    val requiredPerMonthMinor: Long?,
    /** Average net monthly contribution over the last [AVERAGE_MONTHS] months (ALLOCATION mode). */
    val averagePerMonthMinor: Long?,
    /** Projected completion date at the average pace; null when the pace is ≤ 0 or unknown. Estimate. */
    val projectedDate: LocalDate?,
    val onTrack: Boolean?,
) {
    companion object {
        const val AVERAGE_MONTHS = 6
    }
}

/**
 * Goal arithmetic (FBR-23). Progress is virtual allocations (ALLOCATION) or the balance of linked
 * accounts (LINKED_ACCOUNTS); allocations never move money. Projections are labeled estimates.
 */
object GoalProjection {
    fun progress(
        goal: SavingsGoal,
        contributions: List<GoalContribution>,
        linkedBalanceMinor: Long?,
        today: LocalDate,
    ): GoalProgress {
        val current = when (goal.mode) {
            GoalMode.ALLOCATION -> contributions.sumOf { it.amountMinor }
            GoalMode.LINKED_ACCOUNTS -> linkedBalanceMinor ?: 0
        }
        val remaining = (goal.targetMinor - current).coerceAtLeast(0)
        val fraction = if (goal.targetMinor <= 0) 0f else (current.toFloat() / goal.targetMinor).coerceIn(0f, 1f)

        val required = goal.targetDate?.takeIf { remaining > 0 }?.let { target ->
            val months = ChronoUnit.MONTHS.between(today.withDayOfMonth(1), target.withDayOfMonth(1)).coerceAtLeast(1)
            ceil(remaining.toDouble() / months).toLong()
        }

        val since = today.minusMonths(GoalProgress.AVERAGE_MONTHS.toLong())
        val recent = contributions.filter { it.date.isAfter(since) && !it.date.isAfter(today) }
        val average = if (goal.mode == GoalMode.ALLOCATION && contributions.isNotEmpty()) {
            val firstDate = contributions.minOf { it.date }
            val spanMonths = ChronoUnit.MONTHS.between(maxOf(firstDate, since), today).coerceAtLeast(1)
            recent.sumOf { it.amountMinor } / spanMonths
        } else {
            null
        }
        val projected = when {
            remaining == 0L -> today
            average == null || average <= 0 -> null
            else -> today.plusMonths(ceil(remaining.toDouble() / average).toLong().coerceAtMost(1200))
        }
        val onTrack = if (goal.targetDate == null || projected == null) null else !projected.isAfter(goal.targetDate)
        return GoalProgress(goal, current, remaining, fraction, required, average, projected, onTrack)
    }
}
