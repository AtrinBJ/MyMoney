package com.mymoney.core.domain.insights

import com.mymoney.core.domain.calc.AssetHolding
import com.mymoney.core.domain.calc.BudgetStatus
import com.mymoney.core.domain.calc.IncomeStatement
import com.mymoney.core.domain.calc.Recurrence
import com.mymoney.core.domain.calendar.DateRange
import com.mymoney.core.domain.model.RecurringRule
import com.mymoney.core.domain.model.RuleKind
import com.mymoney.core.domain.model.RuleStatus
import java.time.LocalDate
import kotlin.math.sqrt

/**
 * Locally computed, explainable observations (FBR-29). Each carries the numbers that justify it;
 * none gives investment advice or judges the user without data.
 */
sealed interface Insight {
    val severity: Severity

    data class CategorySpike(val categoryId: String?, val currentMinor: Long, val averageMinor: Long, val increasePercent: Int) : Insight {
        override val severity = Severity.WARNING
    }
    data class SpendingConcentration(val categoryId: String?, val sharePercent: Int) : Insight {
        override val severity = Severity.INFO
    }
    data class HighSpendingMonth(val month: DateRange, val expenseMinor: Long, val meanMinor: Long) : Insight {
        override val severity = Severity.INFO
    }
    data class SavingsRateDrop(val currentPercent: Double, val averagePercent: Double) : Insight {
        override val severity = Severity.WARNING
    }
    data class Subscriptions(val monthlyTotalMinor: Long, val sharePercentOfExpenses: Int?, val topRuleIds: List<String>) : Insight {
        override val severity = Severity.INFO
    }
    data class BudgetAtRisk(val budgetId: String, val name: String, val projectedMinor: Long, val limitMinor: Long, val currency: String) : Insight {
        override val severity = Severity.WARNING
    }
    data class BudgetExceeded(val budgetId: String, val name: String, val usedMinor: Long, val limitMinor: Long, val currency: String) : Insight {
        override val severity = Severity.ALERT
    }
    data class StaleValuation(val assetId: String, val name: String, val daysOld: Long?) : Insight {
        override val severity = Severity.INFO
    }
    data class BackupOverdue(val daysSince: Long?) : Insight {
        override val severity = Severity.WARNING
    }
    data class OverduePayments(val count: Int, val totalMinor: Long, val currency: String) : Insight {
        override val severity = Severity.ALERT
    }
}

enum class Severity { INFO, WARNING, ALERT }

/** Inputs prepared by the caller from repositories (all money in base currency). */
data class InsightInputs(
    val today: LocalDate,
    val baseCurrency: String,
    /** Income statements for consecutive full months, oldest first (excluding the current month). */
    val pastMonths: List<Pair<DateRange, IncomeStatement>>,
    /** Current month so far. */
    val currentMonth: Pair<DateRange, IncomeStatement>?,
    val budgets: List<BudgetStatus>,
    val subscriptions: List<Pair<RecurringRule, Long>>,
    val holdings: List<AssetHolding>,
    val lastBackupAt: LocalDate?,
    val backupReminderDays: Int,
    val hasAnyData: Boolean,
    val overdueCount: Int = 0,
    val overdueTotalMinor: Long = 0,
)

object InsightEngine {
    fun compute(i: InsightInputs): List<Insight> {
        val out = mutableListOf<Insight>()
        val cur = i.currentMonth

        // Category spikes: current month normalized to a full month vs the 3-month average.
        if (cur != null && i.pastMonths.size >= 3) {
            val (range, st) = cur
            val elapsed = (i.today.toEpochDay() - range.startEpochDay + 1).coerceIn(1, range.lengthInDays)
            val scale = range.lengthInDays.toDouble() / elapsed
            val last3 = i.pastMonths.takeLast(3).map { it.second }
            val avgTotal = last3.map { it.expenseMinor }.average()
            st.byCategory.forEach { (cat, total) ->
                if (elapsed < 10) return@forEach // too early in the month for a fair comparison
                val currentNormalized = (total.netExpenseMinor * scale).toLong()
                val avg = last3.map { it.byCategory[cat]?.netExpenseMinor ?: 0 }.average()
                if (avg > 0 && currentNormalized > avg * 1.3 && (currentNormalized - avg) >= avgTotal * 0.05) {
                    out += Insight.CategorySpike(cat, currentNormalized, avg.toLong(), ((currentNormalized / avg - 1) * 100).toInt())
                }
            }
        }

        // Concentration in the last full month.
        i.pastMonths.lastOrNull()?.second?.let { st ->
            val totalExpense = st.expenseMinor
            if (totalExpense > 0) {
                st.byCategory.maxByOrNull { it.value.netExpenseMinor }?.let { (cat, t) ->
                    val share = t.netExpenseMinor * 100 / totalExpense
                    if (share > 40) out += Insight.SpendingConcentration(cat, share.toInt())
                }
            }
        }

        // High-spending month among the last 12 (mean + 1σ), needs 6 months.
        if (i.pastMonths.size >= 6) {
            val values = i.pastMonths.takeLast(12).map { it.second.expenseMinor.toDouble() }
            val mean = values.average()
            val sd = sqrt(values.map { (it - mean) * (it - mean) }.average())
            val last = i.pastMonths.last()
            if (sd > 0 && last.second.expenseMinor > mean + sd) {
                out += Insight.HighSpendingMonth(last.first, last.second.expenseMinor, mean.toLong())
            }
            // Savings-rate drop vs the 6-month average.
            val rates = i.pastMonths.takeLast(6).mapNotNull { it.second.savingsRatePercent }
            val lastRate = last.second.savingsRatePercent
            if (rates.size >= 6 && lastRate != null && lastRate < rates.average() - 10) {
                out += Insight.SavingsRateDrop(lastRate, rates.average())
            }
        }

        // Subscriptions (active only), with share of average monthly expenses.
        val subs = i.subscriptions.filter { it.first.kind == RuleKind.SUBSCRIPTION && it.first.status == RuleStatus.ACTIVE }
        if (subs.isNotEmpty()) {
            val monthly = subs.sumOf { it.second }
            val avgExpense = i.pastMonths.takeLast(3).map { it.second.expenseMinor }.takeIf { it.isNotEmpty() }?.average()
            val share = avgExpense?.takeIf { it > 0 }?.let { (monthly * 100 / it).toInt() }
            out += Insight.Subscriptions(monthly, share, subs.sortedByDescending { it.second }.take(3).map { it.first.id })
        }

        i.budgets.forEach { b ->
            when {
                b.usedMinor > b.limitMinor && b.limitMinor > 0 ->
                    out += Insight.BudgetExceeded(b.budget.id, b.budget.name, b.usedMinor, b.limitMinor, b.budget.currency)
                else -> b.projectedUse(i.today)?.let { proj ->
                    if (proj > b.limitMinor && b.limitMinor > 0) {
                        out += Insight.BudgetAtRisk(b.budget.id, b.budget.name, proj, b.limitMinor, b.budget.currency)
                    }
                }
            }
        }

        i.holdings.filter { !it.asset.isArchived && it.quantity.signum() > 0 && it.isStale(i.today) }.forEach { h ->
            out += Insight.StaleValuation(h.asset.id, h.asset.name, h.valuation?.date?.let { i.today.toEpochDay() - it.toEpochDay() })
        }

        if (i.hasAnyData) {
            val days = i.lastBackupAt?.let { i.today.toEpochDay() - it.toEpochDay() }
            if (days == null || days > i.backupReminderDays) out += Insight.BackupOverdue(days)
        }
        if (i.overdueCount > 0) out += Insight.OverduePayments(i.overdueCount, i.overdueTotalMinor, i.baseCurrency)

        return out.sortedByDescending { it.severity.ordinal }
    }

    /** Monthly equivalent of a subscription in its own currency (FBR-35). */
    fun monthlyOf(rule: RecurringRule, today: LocalDate): Long = Recurrence.monthlyEquivalent(rule, Recurrence.amountOn(rule, today))
}
