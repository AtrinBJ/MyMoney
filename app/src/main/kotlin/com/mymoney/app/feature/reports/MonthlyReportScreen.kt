package com.mymoney.app.feature.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mymoney.app.R
import com.mymoney.app.ui.common.categoryLabel
import com.mymoney.core.designsystem.chart.Donut
import com.mymoney.core.designsystem.chart.DonutSlice
import com.mymoney.core.designsystem.component.AmountColor
import com.mymoney.core.designsystem.component.ColorKeys
import com.mymoney.core.designsystem.component.InfoBanner
import com.mymoney.core.designsystem.component.KpiValue
import com.mymoney.core.designsystem.component.LoadingSkeleton
import com.mymoney.core.designsystem.component.MoneyText
import com.mymoney.core.designsystem.component.SectionCard
import com.mymoney.core.designsystem.format.LocalFormats
import com.mymoney.core.designsystem.theme.MyMoneyTheme
import com.mymoney.core.domain.calc.IncomeStatement
import com.mymoney.core.domain.calc.IncomeStatementCalculator
import com.mymoney.core.domain.calc.RateBook
import com.mymoney.core.domain.calendar.CalendarSystem
import com.mymoney.core.domain.calendar.DateRange
import com.mymoney.core.domain.calendar.PeriodType
import com.mymoney.core.domain.calendar.Periods
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.model.Category
import com.mymoney.core.domain.money.Digits
import com.mymoney.core.domain.repository.CategoryRepository
import com.mymoney.core.domain.repository.CurrencyRepository
import com.mymoney.core.domain.repository.ExchangeRateRepository
import com.mymoney.core.domain.repository.LedgerRepository
import com.mymoney.core.domain.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
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

/** A category row rolled up to its top-level parent. */
data class CategoryShare(val category: Category?, val minor: Long, val share: Double)

data class MonthlyReportState(
    val loading: Boolean = true,
    val range: DateRange? = null,
    val statement: IncomeStatement? = null,
    val expenses: List<CategoryShare> = emptyList(),
    val incomes: List<CategoryShare> = emptyList(),
    val baseCurrency: String = "IRR",
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MonthlyReportViewModel @Inject constructor(
    private val ledger: LedgerRepository,
    categories: CategoryRepository,
    rates: ExchangeRateRepository,
    currencies: CurrencyRepository,
    settings: SettingsRepository,
    clock: TimeProvider,
) : ViewModel() {
    private val offset = MutableStateFlow(0)
    private val today = clock.today()

    private data class Inputs(
        val offset: Int,
        val categories: List<Category>,
        val rates: List<com.mymoney.core.domain.model.ExchangeRate>,
        val currencies: List<com.mymoney.core.domain.money.Currency>,
        val settings: com.mymoney.core.domain.settings.AppSettings,
    )

    val state: StateFlow<MonthlyReportState> = combine(
        combine(offset, ledger.observeChanges()) { o, _ -> o },
        categories.observe(includeArchived = true),
        rates.observeAll(),
        currencies.observeAll(),
        settings.settings,
    ) { o, cats, r, c, s -> Inputs(o, cats, r, c, s) }
        .mapLatest { i ->
            val cal = CalendarSystem.of(i.settings.calendar)
            val periods = Periods(cal, i.settings.weekStart)
            val month = periods.rangeOf(PeriodType.MONTH, cal.plusMonths(today, i.offset, 1))
            val book = RateBook(i.rates, i.currencies.associateBy { it.code }, i.settings.baseCurrency)
            val statement = IncomeStatementCalculator.compute(ledger.categoryAmounts(month.start, month.endInclusive), book, i.settings.baseCurrency)
            val byId = i.categories.associateBy { it.id }
            // Subcategories roll up into their parent so the chart shows top-level spending.
            fun rollUp(id: String?): String? = id?.let { byId[it]?.parentId ?: it }
            val expense = statement.byCategory.entries.groupBy({ rollUp(it.key) }, { it.value.netExpenseMinor })
                .mapValues { it.value.sum() }.filterValues { it != 0L }
            val income = statement.byCategory.entries.groupBy({ rollUp(it.key) }, { it.value.incomeMinor })
                .mapValues { it.value.sum() }.filterValues { it != 0L }
            fun shares(m: Map<String?, Long>): List<CategoryShare> {
                val total = m.values.sum().toDouble().takeIf { it != 0.0 } ?: 1.0
                return m.entries.sortedByDescending { it.value }.map { CategoryShare(it.key?.let(byId::get), it.value, it.value / total) }
            }
            MonthlyReportState(false, month, statement, shares(expense), shares(income), i.settings.baseCurrency)
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MonthlyReportState())

    fun shift(delta: Int) {
        offset.value += delta
    }
}

@Composable
fun MonthlyReportScreen(contentPadding: PaddingValues, onOpenRates: () -> Unit, viewModel: MonthlyReportViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val formats = LocalFormats.current
    val spacing = MyMoneyTheme.spacing
    var showIncome by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (s.loading || s.statement == null) {
        LoadingSkeleton(Modifier.padding(contentPadding))
        return
    }
    val st = s.statement!!
    val list = if (showIncome) s.incomes else s.expenses
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = spacing.screenPadding, end = spacing.screenPadding,
            top = contentPadding.calculateTopPadding() + spacing.s, bottom = contentPadding.calculateBottomPadding() + 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.cardGap),
    ) {
        item(key = "nav") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { viewModel.shift(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.action_previous_month)) }
                Text(
                    s.range?.let { formats.dates.monthYear(it.start) }.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { viewModel.shift(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.action_next_month)) }
            }
        }
        item(key = "kpi") {
            SectionCard(title = stringResource(R.string.rep_month_income_expense)) {
                Row(Modifier.fillMaxWidth()) {
                    KpiValue(stringResource(R.string.home_income), st.incomeMinor, s.baseCurrency, Modifier.weight(1f), AmountColor.INCOME)
                    KpiValue(stringResource(R.string.home_expense), st.expenseMinor, s.baseCurrency, Modifier.weight(1f), AmountColor.EXPENSE)
                }
                KpiValue(stringResource(R.string.home_net), st.netMinor, s.baseCurrency, Modifier.padding(top = spacing.m), AmountColor.SIGNED)
                st.savingsRatePercent?.let {
                    Text(
                        stringResource(R.string.rep_savings_rate) + ": " + Digits.isolateLtr(Digits.localize(String.format(Locale.ROOT, "%.1f%%", it), formats.settings.digits)),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = spacing.s),
                    )
                }
            }
        }
        if (st.missing.isNotEmpty()) {
            item(key = "missing") {
                InfoBanner(stringResource(R.string.home_missing_rates, st.missing.joinToString("، ") { formats.money.format(it.amountMinor, it.currency) }), actionLabel = stringResource(R.string.rate_add), onAction = onOpenRates)
            }
        }
        item(key = "toggle") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(!showIncome, { showIncome = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.home_expense)) }
                SegmentedButton(showIncome, { showIncome = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.home_income)) }
            }
        }
        if (list.isEmpty()) {
            item(key = "empty") { Text(stringResource(R.string.rep_no_data), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(spacing.l)) }
        } else {
            item(key = "donut") {
                val palette = MyMoneyTheme.finance.chart
                val slices = list.mapIndexed { i, c -> DonutSlice(c.minor, c.category?.colorKey?.let { ColorKeys.color(it) } ?: palette[i % palette.size]) }
                val labels = list.take(5).map { "${categoryLabel(it.category)} ${percent(it.share, formats.settings.digits)}" }
                val summary = labels.joinToString("، ")
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Donut(slices, summary = summary) {
                        MoneyText(list.sumOf { it.minor }, s.baseCurrency, style = MaterialTheme.typography.titleSmall, compact = true)
                    }
                }
            }
            items(list, key = { (if (showIncome) "i-" else "e-") + (it.category?.id ?: "none") }) { row ->
                val color = row.category?.colorKey?.let { ColorKeys.color(it) } ?: MaterialTheme.colorScheme.outline
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Spacer(Modifier.size(12.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(spacing.s))
                    Text(categoryLabel(row.category), modifier = Modifier.weight(1f))
                    Text(percent(row.share, formats.settings.digits), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(spacing.m))
                    MoneyText(row.minor, s.baseCurrency)
                }
            }
        }
    }
}

private fun percent(share: Double, digits: com.mymoney.core.domain.settings.DigitStyle): String =
    Digits.isolateLtr(Digits.localize(String.format(Locale.ROOT, "%.1f%%", share * 100), digits))
