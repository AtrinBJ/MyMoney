package com.mymoney.app.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mymoney.core.data.db.dao.EntryListRow
import com.mymoney.core.data.repository.EntryFilter
import com.mymoney.core.data.repository.EntryQueries
import com.mymoney.core.domain.calc.IncomeStatement
import com.mymoney.core.domain.calc.IncomeStatementCalculator
import com.mymoney.core.domain.calc.NetWorthCalculator
import com.mymoney.core.domain.calc.NetWorthSummary
import com.mymoney.core.domain.calendar.PeriodType
import com.mymoney.core.domain.calendar.Periods
import com.mymoney.core.domain.calc.RateBook
import com.mymoney.core.domain.calendar.CalendarSystem
import com.mymoney.core.domain.calendar.DateRange
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.repository.AccountRepository
import com.mymoney.core.domain.repository.CurrencyRepository
import com.mymoney.core.domain.repository.ExchangeRateRepository
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

enum class DashboardPeriod { THIS_MONTH, LAST_MONTH, THIS_YEAR }

data class DashboardState(
    val loading: Boolean = true,
    val period: DashboardPeriod = DashboardPeriod.THIS_MONTH,
    val range: DateRange? = null,
    val netWorth: NetWorthSummary? = null,
    val current: IncomeStatement? = null,
    val previous: IncomeStatement? = null,
    val recent: List<EntryListRow> = emptyList(),
    val hasAccounts: Boolean = false,
)

/**
 * Home dashboard. Every figure comes from the same domain calculators the reports use (FBR-39);
 * nothing is computed in Composables.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    accounts: AccountRepository,
    rates: ExchangeRateRepository,
    currencies: CurrencyRepository,
    settings: SettingsRepository,
    private val ledger: LedgerRepository,
    private val queries: EntryQueries,
    private val clock: TimeProvider,
) : ViewModel() {
    private val period = MutableStateFlow(DashboardPeriod.THIS_MONTH)

    val state: StateFlow<DashboardState> = combine(
        accounts.observeWithBalances(includeArchived = false),
        rates.observeAll(),
        currencies.observeAll(),
        settings.settings,
        combine(period, ledger.observeChanges()) { p, _ -> p },
    ) { balances, r, c, s, p -> Inputs(balances, r, c, s, p) }
        .mapLatest { i ->
            val today = clock.today()
            val currencyMap = i.currencies.associateBy { it.code }
            val book = RateBook(i.rates, currencyMap, i.settings.baseCurrency)
            val periods = Periods(CalendarSystem.of(i.settings.calendar), i.settings.weekStart)
            val (range, previous) = when (i.period) {
                DashboardPeriod.THIS_MONTH -> periods.rangeOf(PeriodType.MONTH, today).let { it to periods.previous(PeriodType.MONTH, it) }
                DashboardPeriod.LAST_MONTH -> periods.previous(PeriodType.MONTH, periods.rangeOf(PeriodType.MONTH, today))
                    .let { it to periods.previous(PeriodType.MONTH, it) }
                DashboardPeriod.THIS_YEAR -> periods.rangeOf(PeriodType.YEAR, today).let { it to periods.previous(PeriodType.YEAR, it) }
            }
            val cur = IncomeStatementCalculator.compute(ledger.categoryAmounts(range.start, range.endInclusive), book, i.settings.baseCurrency)
            val prev = IncomeStatementCalculator.compute(ledger.categoryAmounts(previous.start, previous.endInclusive), book, i.settings.baseCurrency)
            DashboardState(
                loading = false,
                period = i.period,
                range = range,
                netWorth = NetWorthCalculator.compute(i.balances, book, i.settings.baseCurrency, today),
                current = cur,
                previous = prev,
                recent = queries.list(EntryFilter(), 5),
                hasAccounts = i.balances.any { !it.account.type.isObligation },
            )
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())

    fun setPeriod(p: DashboardPeriod) {
        period.value = p
    }

    private data class Inputs(
        val balances: List<com.mymoney.core.domain.model.AccountWithBalance>,
        val rates: List<com.mymoney.core.domain.model.ExchangeRate>,
        val currencies: List<com.mymoney.core.domain.money.Currency>,
        val settings: com.mymoney.core.domain.settings.AppSettings,
        val period: DashboardPeriod,
    )
}
