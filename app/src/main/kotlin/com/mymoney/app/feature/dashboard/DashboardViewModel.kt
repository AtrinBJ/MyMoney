package com.mymoney.app.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mymoney.app.data.FinanceSnapshot
import com.mymoney.app.data.FinanceSnapshotProvider
import com.mymoney.app.feature.planning.PlanningMath
import com.mymoney.app.feature.reports.ReportBuilder
import com.mymoney.app.feature.reports.available
import com.mymoney.core.data.db.dao.EntryListRow
import com.mymoney.core.data.repository.EntryFilter
import com.mymoney.core.data.repository.EntryQueries
import com.mymoney.core.domain.calc.BudgetStatus
import com.mymoney.core.domain.calc.DueStatus
import com.mymoney.core.domain.calc.Forecast
import com.mymoney.core.domain.calc.GoalProgress
import com.mymoney.core.domain.calc.IncomeStatement
import com.mymoney.core.domain.calc.IncomeStatementCalculator
import com.mymoney.core.domain.calc.MonthEndForecast
import com.mymoney.core.domain.calc.NetWorthSummary
import com.mymoney.core.domain.calc.Occurrence
import com.mymoney.core.domain.calendar.CalendarSystem
import com.mymoney.core.domain.calendar.DateRange
import com.mymoney.core.domain.calendar.PeriodType
import com.mymoney.core.domain.calendar.Periods
import com.mymoney.core.domain.insights.Insight
import com.mymoney.core.domain.model.RuleKind
import com.mymoney.core.domain.repository.LedgerAnalytics
import com.mymoney.core.domain.repository.LedgerRepository
import com.mymoney.core.domain.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class DashboardPeriod { THIS_MONTH, LAST_MONTH, THIS_YEAR }

/** Cards the user can show or hide (Settings → Dashboard cards). Stored by name in AppSettings.hiddenDashboardCards. */
enum class DashboardCard { NET_WORTH, CASH_FLOW, BUDGETS, UPCOMING, GOALS, INSIGHTS, FORECAST, RECENT }

data class DashboardState(
    val loading: Boolean = true,
    val period: DashboardPeriod = DashboardPeriod.THIS_MONTH,
    val range: DateRange? = null,
    val netWorth: NetWorthSummary? = null,
    val current: IncomeStatement? = null,
    val previous: IncomeStatement? = null,
    val recent: List<EntryListRow> = emptyList(),
    val hasAccounts: Boolean = false,
    val budgets: List<BudgetStatus> = emptyList(),
    val upcoming: List<Occurrence> = emptyList(),
    val goals: List<GoalProgress> = emptyList(),
    val insights: List<Insight> = emptyList(),
    val forecast: Forecast? = null,
    val snapshot: FinanceSnapshot? = null,
    val hidden: Set<DashboardCard> = emptySet(),
)

/**
 * Home dashboard. Every figure comes from the same snapshot and domain calculators the reports, planning
 * and wealth screens use (FBR-39); nothing is computed in Composables.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    provider: FinanceSnapshotProvider,
    private val settings: SettingsRepository,
    private val ledger: LedgerRepository,
    private val analytics: LedgerAnalytics,
    private val queries: EntryQueries,
) : ViewModel() {
    private val period = MutableStateFlow(DashboardPeriod.THIS_MONTH)
    private val reports = ReportBuilder(ledger, analytics)

    val state: StateFlow<DashboardState> = combine(provider.snapshot, period, ledger.observeChanges()) { s, p, _ -> s to p }
        .mapLatest { (s, p) ->
            val periods = Periods(CalendarSystem.of(s.settings.calendar), s.settings.weekStart)
            val (range, previous) = when (p) {
                DashboardPeriod.THIS_MONTH -> periods.rangeOf(PeriodType.MONTH, s.today).let { it to periods.previous(PeriodType.MONTH, it) }
                DashboardPeriod.LAST_MONTH -> periods.previous(PeriodType.MONTH, periods.rangeOf(PeriodType.MONTH, s.today))
                    .let { it to periods.previous(PeriodType.MONTH, it) }
                DashboardPeriod.THIS_YEAR -> periods.rangeOf(PeriodType.YEAR, s.today).let { it to periods.previous(PeriodType.YEAR, it) }
            }
            val hidden = s.settings.hiddenDashboardCards.mapNotNull { n -> DashboardCard.entries.firstOrNull { it.name == n } }.toSet()
            val cur = IncomeStatementCalculator.compute(ledger.categoryAmounts(range.start, range.endInclusive), s.rateBook, s.base)
            val prev = IncomeStatementCalculator.compute(ledger.categoryAmounts(previous.start, previous.endInclusive), s.rateBook, s.base)
            val upcoming = s.occurrences(DateRange(s.today.minusDays(90), s.today.plusDays(UPCOMING_DAYS)))
                .filter { it.status == DueStatus.OVERDUE || it.status == DueStatus.OPEN || it.status == DueStatus.PARTIAL }
                .sortedWith(compareBy<Occurrence> { it.status != DueStatus.OVERDUE }.thenBy { it.dueDate })
            DashboardState(
                loading = false,
                period = p,
                range = range,
                netWorth = s.netWorth,
                current = cur,
                previous = prev,
                recent = if (DashboardCard.RECENT in hidden) emptyList() else queries.list(EntryFilter(), 5),
                hasAccounts = s.accounts.any { !it.account.type.isObligation },
                budgets = if (DashboardCard.BUDGETS in hidden) emptyList() else PlanningMath.budgetStatuses(s, analytics, s.today),
                upcoming = upcoming,
                goals = PlanningMath.goalProgress(s).filter { it.remainingMinor > 0 },
                insights = if (DashboardCard.INSIGHTS in hidden) emptyList() else reports.insights(s).available(),
                forecast = if (DashboardCard.FORECAST in hidden) null else forecast(s, periods.rangeOf(PeriodType.MONTH, s.today).endInclusive, upcoming),
                snapshot = s,
                hidden = hidden,
            )
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())

    /**
     * FBR-28: liquid money now + scheduled income − scheduled payments due by month end + pending − typical
     * variable spending for the remaining days. Transfers between own accounts are left out.
     */
    private suspend fun forecast(s: FinanceSnapshot, monthEnd: java.time.LocalDate, due: List<Occurrence>): Forecast {
        val untilMonthEnd = due.filter { !it.dueDate.isAfter(monthEnd) && it.rule.kind != RuleKind.TRANSFER }
        val scheduledIn = untilMonthEnd.filter { it.rule.kind == RuleKind.INCOME }.sumOf { s.toBase(it.outstandingMinor, it.rule.currency) ?: 0 }
        val scheduledOut = untilMonthEnd.filter { it.rule.kind != RuleKind.INCOME }.sumOf { s.toBase(it.outstandingMinor, it.rule.currency) ?: 0 }
        val liquidIds = s.accounts.filter { it.account.includeInLiquid && !it.account.type.isObligation }.associateBy { it.account.id }
        val pending = analytics.pendingByAccount().entries.sumOf { (id, v) -> liquidIds[id]?.let { s.toBase(v, it.account.currency) } ?: 0 }

        val from = s.today.minusDays(MonthEndForecast.LOOKBACK_DAYS.toLong())
        val rows = analytics.variableSpendingRows(from, s.today.minusDays(1))
        val firstEntry = rows.minOfOrNull { it.date }
        val daily = if (firstEntry == null) {
            emptyList()
        } else {
            val byDay = rows.groupBy { it.date }.mapValues { (_, l) -> l.sumOf { -(s.rateBook.convert(it.amountMinor, it.currency, s.base, it.date)?.minor ?: 0) } }
            generateSequence(firstEntry) { it.plusDays(1) }.takeWhile { it.isBefore(s.today) }.map { byDay[it] ?: 0 }.toList()
        }
        // Round the estimate to 1,000 toman (IRR) or one whole unit (2-decimal currencies).
        val step = if ((s.currencies[s.base]?.minorDigits ?: 2) == 0) 10_000L else 100L
        return MonthEndForecast.compute(s.today, monthEnd, s.netWorth.liquidMinor, scheduledIn, scheduledOut, pending, daily, step)
    }

    fun setPeriod(p: DashboardPeriod) {
        period.value = p
    }

    fun setCardVisible(card: DashboardCard, visible: Boolean) {
        viewModelScope.launch {
            settings.update { it.copy(hiddenDashboardCards = if (visible) it.hiddenDashboardCards - card.name else it.hiddenDashboardCards + card.name) }
        }
    }

    companion object {
        const val UPCOMING_DAYS = 14L
    }
}
