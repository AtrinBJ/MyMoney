package com.mymoney.app.feature.reports

import com.mymoney.app.data.FinanceSnapshot
import com.mymoney.app.feature.planning.PlanningMath
import com.mymoney.app.feature.wealth.ObligationRow
import com.mymoney.app.feature.wealth.obligationRows
import com.mymoney.core.domain.calc.AssetCalculator
import com.mymoney.core.domain.calc.BudgetStatus
import com.mymoney.core.domain.calc.CashFlowCalculator
import com.mymoney.core.domain.calc.CashFlowStatement
import com.mymoney.core.domain.calc.FxRevaluation
import com.mymoney.core.domain.calc.GoalProgress
import com.mymoney.core.domain.calc.IncomeStatement
import com.mymoney.core.domain.calc.IncomeStatementCalculator
import com.mymoney.core.domain.calc.OversellException
import com.mymoney.core.domain.calc.Recurrence
import com.mymoney.core.domain.calendar.CalendarSystem
import com.mymoney.core.domain.calendar.DateRange
import com.mymoney.core.domain.calendar.PeriodType
import com.mymoney.core.domain.calendar.Periods
import com.mymoney.core.domain.insights.Insight
import com.mymoney.core.domain.insights.InsightEngine
import com.mymoney.core.domain.insights.InsightInputs
import com.mymoney.core.domain.model.CostType
import com.mymoney.core.domain.model.RecurringRule
import com.mymoney.core.domain.model.RuleKind
import com.mymoney.core.domain.model.RuleStatus
import com.mymoney.core.domain.repository.LedgerAnalytics
import com.mymoney.core.domain.repository.LedgerRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The 13 reports of docs/01 §15 (the category breakdown has its own screen). */
enum class ReportType {
    INCOME_EXPENSE, YEARLY, CASH_FLOW, BALANCES, CATEGORIES, COMPARISON, FIXED_VARIABLE,
    BUDGETS, INCOME_SOURCES, DEBTS, NET_WORTH, GOALS, RECURRING,
}

enum class PeriodKind { MONTH, YEAR }

sealed interface ReportData {
    data class Series(val periods: List<DateRange>, val statements: List<IncomeStatement>) : ReportData
    data class CashFlow(val range: DateRange, val statement: CashFlowStatement) : ReportData
    data class Balances(val points: List<LocalDate>, val total: List<Long>, val liquid: List<Long>) : ReportData
    data class Comparison(val current: DateRange, val previous: DateRange, val rows: List<Triple<String?, Long, Long>>, val cur: IncomeStatement, val prev: IncomeStatement) : ReportData
    data class FixedVariable(val range: DateRange, val fixed: Long, val variable: Long, val untyped: Long, val essential: Long, val optional: Long, val unmarked: Long) : ReportData
    data class Budgets(val range: DateRange, val statuses: List<BudgetStatus>) : ReportData
    data class IncomeSources(val range: DateRange, val rows: List<Pair<String?, Long>>, val total: Long) : ReportData
    data class Debts(val rows: List<ObligationRow>) : ReportData
    data class NetWorth(val points: List<LocalDate>, val values: List<Long>, val fxEffectBase: Long?, val range: DateRange) : ReportData
    data class Goals(val goals: List<GoalProgress>) : ReportData
    data class Recurring(val range: DateRange, val rows: List<RecurringRow>, val monthlyOut: Long, val monthlyIn: Long) : ReportData
}

data class RecurringRow(val rule: RecurringRule, val monthlyBase: Long, val expectedThisPeriod: Long, val paidThisPeriod: Long)

/**
 * Assembles report data from the ledger with the same calculators as the dashboard (FBR-39):
 * a total shown in a report equals the same total on the dashboard for the same period.
 */
class ReportBuilder(
    private val ledger: LedgerRepository,
    private val analytics: LedgerAnalytics,
) {
    fun periods(s: FinanceSnapshot) = Periods(CalendarSystem.of(s.settings.calendar), s.settings.weekStart)

    fun range(s: FinanceSnapshot, kind: PeriodKind, offset: Int): DateRange {
        val p = periods(s)
        val type = if (kind == PeriodKind.MONTH) PeriodType.MONTH else PeriodType.YEAR
        var r = p.rangeOf(type, s.today)
        repeat(-offset.coerceAtMost(0)) { r = p.previous(type, r) }
        repeat(offset.coerceAtLeast(0)) { r = p.next(type, r) }
        return r
    }

    /** Income statements for consecutive periods ending with the one containing [end], oldest first. */
    suspend fun statements(s: FinanceSnapshot, type: PeriodType, end: LocalDate, count: Int): Pair<List<DateRange>, List<IncomeStatement>> {
        val ranges = periods(s).lastN(type, end, count)
        val rows = ledger.categoryAmounts(ranges.first().start, ranges.last().endInclusive)
        return ranges to ranges.map { r -> IncomeStatementCalculator.compute(rows.filter { it.date in r }, s.rateBook, s.base) }
    }

    suspend fun build(s: FinanceSnapshot, type: ReportType, kind: PeriodKind, offset: Int): ReportData {
        val range = range(s, kind, offset)
        return when (type) {
            ReportType.INCOME_EXPENSE, ReportType.CATEGORIES -> statements(s, PeriodType.MONTH, range.endInclusive, 12).let { series(it) }
            ReportType.YEARLY -> statements(s, PeriodType.YEAR, range.endInclusive, 5).let { series(it) }
            ReportType.CASH_FLOW -> {
                val funds = s.accounts.filter { !it.account.type.isObligation }.map { it.account.id }.toSet()
                ReportData.CashFlow(range, CashFlowCalculator.compute(ledger.flows(range.start, range.endInclusive), s.accountsById.mapValues { it.value.account }, funds, s.rateBook, s.base))
            }
            ReportType.BALANCES -> {
                val points = periods(s).lastN(PeriodType.MONTH, minOf(range.endInclusive, s.today), 12).map { minOf(it.endInclusive, s.today) }
                val totals = mutableListOf<Long>()
                val liquid = mutableListOf<Long>()
                val balances = analytics.balancesAt(points)
                points.forEachIndexed { pi, d ->
                    val bal = balances[pi]
                    var t = 0L
                    var l = 0L
                    bal.forEach { (id, v) ->
                        val acc = s.accountsById[id]?.account ?: return@forEach
                        if (acc.type.isObligation) return@forEach
                        val b = s.rateBook.convert(v, acc.currency, s.base, d)?.minor ?: return@forEach
                        t += b
                        if (acc.includeInLiquid) l += b
                    }
                    totals += t
                    liquid += l
                }
                ReportData.Balances(points, totals, liquid)
            }
            ReportType.COMPARISON -> {
                val prev = periods(s).previous(if (kind == PeriodKind.MONTH) PeriodType.MONTH else PeriodType.YEAR, range)
                val rows = ledger.categoryAmounts(prev.start, range.endInclusive)
                val cur = IncomeStatementCalculator.compute(rows.filter { it.date in range }, s.rateBook, s.base)
                val pre = IncomeStatementCalculator.compute(rows.filter { it.date in prev }, s.rateBook, s.base)
                val cats = (cur.byCategory.keys + pre.byCategory.keys).map { rollUp(s, it) }.toSet()
                fun sumFor(st: IncomeStatement, cat: String?) = st.byCategory.filterKeys { rollUp(s, it) == cat }.values.sumOf { it.netExpenseMinor }
                val list = cats.map { Triple(it, sumFor(cur, it), sumFor(pre, it)) }.filter { it.second != 0L || it.third != 0L }.sortedByDescending { it.second }
                ReportData.Comparison(range, prev, list, cur, pre)
            }
            ReportType.FIXED_VARIABLE -> {
                var fixed = 0L; var variable = 0L; var untyped = 0L; var ess = 0L; var opt = 0L; var unmarked = 0L
                analytics.costTypeRows(range.start, range.endInclusive).forEach { r ->
                    val v = -(s.rateBook.convert(r.amountMinor, r.currency, s.base, r.date)?.minor ?: return@forEach)
                    when (r.costType) { CostType.FIXED -> fixed += v; CostType.VARIABLE -> variable += v; null -> untyped += v }
                    when (r.essential) { true -> ess += v; false -> opt += v; null -> unmarked += v }
                }
                ReportData.FixedVariable(range, fixed, variable, untyped, ess, opt, unmarked)
            }
            ReportType.BUDGETS -> ReportData.Budgets(range, PlanningMath.budgetStatuses(s, analytics, if (s.today in range) s.today else range.endInclusive))
            ReportType.INCOME_SOURCES -> {
                val st = IncomeStatementCalculator.compute(ledger.categoryAmounts(range.start, range.endInclusive), s.rateBook, s.base)
                val rows = st.byCategory.entries.groupBy({ rollUp(s, it.key) }, { it.value.incomeMinor }).mapValues { it.value.sum() }
                    .filterValues { it > 0 }.toList().sortedByDescending { it.second }
                ReportData.IncomeSources(range, rows, st.incomeMinor)
            }
            ReportType.DEBTS -> ReportData.Debts(s.obligationRows().sortedWith(compareBy({ it.outstanding == 0L }, { it.obligation.dueDate ?: LocalDate.MAX })))
            ReportType.NET_WORTH -> netWorth(s, range)
            ReportType.GOALS -> ReportData.Goals(PlanningMath.goalProgress(s))
            ReportType.RECURRING -> {
                val occ = s.occurrences(range)
                val rows = s.rules.filter { it.status == RuleStatus.ACTIVE }.map { r ->
                    val mine = occ.filter { it.rule.id == r.id }
                    RecurringRow(
                        r,
                        s.toBase(Recurrence.monthlyEquivalent(r, Recurrence.amountOn(r, s.today)), r.currency) ?: 0,
                        mine.sumOf { it.expectedMinor },
                        mine.sumOf { it.paidMinor },
                    )
                }.sortedByDescending { it.monthlyBase }
                ReportData.Recurring(range, rows, rows.filter { it.rule.kind != RuleKind.INCOME }.sumOf { it.monthlyBase }, rows.filter { it.rule.kind == RuleKind.INCOME }.sumOf { it.monthlyBase })
            }
        }
    }

    /** Periods before the first recorded income or expense are left out instead of shown as zeros. */
    private fun series(p: Pair<List<DateRange>, List<IncomeStatement>>): ReportData.Series {
        val first = p.second.indexOfFirst { it.incomeMinor != 0L || it.expenseMinor != 0L }.let { if (it < 0) p.second.size - 1 else it }
        return ReportData.Series(p.first.drop(first), p.second.drop(first))
    }

    private fun rollUp(s: FinanceSnapshot, id: String?): String? = id?.let { s.categoriesById[it]?.parentId ?: it }

    /** Net worth at month ends (accounts at that date + assets valued as of that date) and the FX effect (FBR-31). */
    private suspend fun netWorth(s: FinanceSnapshot, range: DateRange): ReportData.NetWorth {
        val points = periods(s).lastN(PeriodType.MONTH, minOf(range.endInclusive, s.today), 12).map { minOf(it.endInclusive, s.today) }
        val tradesBy = s.trades.groupBy { it.assetId }
        val valuationsBy = s.valuations.groupBy { it.assetId }
        val end = minOf(range.endInclusive, s.today)
        val balances = analytics.balancesAt(points + listOf(range.start.minusDays(1), end))
        val values = points.mapIndexed { pi, d ->
            var total = 0L
            balances[pi].forEach { (id, v) ->
                val acc = s.accountsById[id]?.account ?: return@forEach
                if (!acc.includeInNetWorth) return@forEach
                total += s.rateBook.convert(v, acc.currency, s.base, d)?.minor ?: 0
            }
            s.assets.filter { it.includeInNetWorth }.forEach { a ->
                val h = try {
                    AssetCalculator.holding(a, tradesBy[a.id].orEmpty(), valuationsBy[a.id].orEmpty(), s.currencies[a.currency]?.minorDigits ?: 2, asOf = d)
                } catch (_: OversellException) {
                    null
                }
                h?.valueMinor?.let { total += s.rateBook.convert(it, a.currency, s.base, d)?.minor ?: 0 }
            }
            total
        }
        // FX effect over the selected period for foreign-currency accounts.
        val startBal = balances[points.size]
        val endBal = balances[points.size + 1]
        val flows = ledger.flows(range.start, end).groupBy { it.accountId }
        var fx: Long? = 0L
        s.accounts.map { it.account }.filter { it.currency != s.base && it.includeInNetWorth }.forEach { acc ->
            val e = FxRevaluation.effect(
                acc.currency, s.base, range.start.minusDays(1), end, startBal[acc.id] ?: 0, endBal[acc.id] ?: 0,
                flows[acc.id].orEmpty().map { it.date to it.amountMinor }, s.rateBook,
            )
            fx = if (e == null || fx == null) null else fx!! + e
        }
        return ReportData.NetWorth(points, values, fx, range)
    }

    /** Inputs for the insight engine (FBR-29), shared by the insights screen and the dashboard. */
    suspend fun insights(s: FinanceSnapshot): List<Insight> {
        val (ranges, statements) = statements(s, PeriodType.MONTH, s.today, 13)
        val past = ranges.dropLast(1).zip(statements.dropLast(1)).filter { (_, st) -> st.incomeMinor != 0L || st.expenseMinor != 0L }
        val current = ranges.last() to statements.last()
        val subs = s.rules.filter { it.kind == RuleKind.SUBSCRIPTION }.map { it to (s.toBase(InsightEngine.monthlyOf(it, s.today), it.currency) ?: 0) }
        val overdue = s.occurrences(DateRange(s.today.minusDays(120), s.today.minusDays(1))).filter { it.status == com.mymoney.core.domain.calc.DueStatus.OVERDUE }
        return InsightEngine.compute(
            InsightInputs(
                today = s.today,
                baseCurrency = s.base,
                pastMonths = past,
                currentMonth = current,
                budgets = PlanningMath.budgetStatuses(s, analytics, s.today),
                subscriptions = subs,
                holdings = s.holdings,
                lastBackupAt = s.settings.lastBackupAtMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() },
                backupReminderDays = s.settings.backupReminderDays,
                hasAnyData = s.accounts.isNotEmpty(),
                overdueCount = overdue.size,
                overdueTotalMinor = overdue.sumOf { s.toBase(it.outstandingMinor, it.rule.currency) ?: 0 },
            ),
        )
    }
}
