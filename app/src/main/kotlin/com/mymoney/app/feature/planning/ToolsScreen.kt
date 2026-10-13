@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.mymoney.app.feature.planning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mymoney.app.R
import com.mymoney.app.data.FinanceSnapshotProvider
import com.mymoney.app.ui.common.BackTopBar
import com.mymoney.app.ui.common.categoryLabel
import com.mymoney.app.ui.common.parseAmountOrNull
import com.mymoney.core.designsystem.component.AmountField
import com.mymoney.core.designsystem.component.EstimateNote
import com.mymoney.core.designsystem.component.KpiValue
import com.mymoney.core.designsystem.component.MoneyText
import com.mymoney.core.designsystem.component.SectionCard
import com.mymoney.core.designsystem.format.LocalFormats
import com.mymoney.core.designsystem.theme.MyMoneyTheme
import com.mymoney.core.domain.calc.Amortization
import com.mymoney.core.domain.calc.IncomeStatement
import com.mymoney.core.domain.calc.IncomeStatementCalculator
import com.mymoney.core.domain.calendar.PeriodType
import com.mymoney.core.domain.model.InterestMethod
import com.mymoney.core.domain.money.Digits
import com.mymoney.core.domain.repository.LedgerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

data class ToolsState(
    val base: String = "IRR",
    /** Average of the last 3 full months (income statement per month). */
    val lastMonths: List<IncomeStatement> = emptyList(),
    val categories: Map<String?, com.mymoney.core.domain.model.Category?> = emptyMap(),
    val days: Long = 0,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class ToolsViewModel @Inject constructor(provider: FinanceSnapshotProvider, ledger: LedgerRepository) : ViewModel() {
    val state: StateFlow<ToolsState> = provider.snapshot.mapLatest { s ->
        val periods = com.mymoney.core.domain.calendar.Periods(com.mymoney.core.domain.calendar.CalendarSystem.of(s.settings.calendar), s.settings.weekStart)
        val months = periods.lastN(PeriodType.MONTH, s.today, 4).dropLast(1) // last 3 full months
        val statements = months.map { IncomeStatementCalculator.compute(ledger.categoryAmounts(it.start, it.endInclusive), s.rateBook, s.base) }
        ToolsState(s.base, statements, s.categoriesById.mapKeys { it.key as String? }, months.sumOf { it.lengthInDays })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ToolsState())
}

@Composable
fun ToolsScreen(onBack: () -> Unit, viewModel: ToolsViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val spacing = MyMoneyTheme.spacing
    Scaffold(topBar = { BackTopBar(stringResource(R.string.tools_title), onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(spacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(spacing.cardGap),
        ) {
            LoanCalculator(s.base)
            SpendingStats(s)
            CutScenario(s)
        }
    }
}

@Composable
private fun LoanCalculator(base: String) {
    val formats = LocalFormats.current
    var principal by rememberSaveable { mutableStateOf("") }
    var rate by rememberSaveable { mutableStateOf("") }
    var months by rememberSaveable { mutableStateOf("12") }
    var method by rememberSaveable { mutableStateOf(InterestMethod.ANNUITY) }
    val p = formats.parseAmountOrNull(principal, base)
    val r = Digits.normalize(rate).toBigDecimalOrNull()?.takeIf { it.signum() >= 0 && it < BigDecimal(1000) }
    val n = Digits.normalize(months).toIntOrNull()?.takeIf { it in 1..600 }
    val schedule = remember(p, r, n, method) { if (p != null && p > 0 && r != null && n != null) Amortization.schedule(method, p, r, n) else null }
    SectionCard(title = stringResource(R.string.tools_loan)) {
        AmountField(principal, { principal = it }, base, stringResource(R.string.tools_principal), Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(rate, { rate = it.take(6) }, label = { Text(stringResource(R.string.tools_rate)) }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(months, { months = Digits.normalize(it).filter(Char::isDigit).take(3) }, label = { Text(stringResource(R.string.tools_months)) }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(method == InterestMethod.ANNUITY, { method = InterestMethod.ANNUITY }, { Text(stringResource(R.string.tools_annuity)) })
            FilterChip(method == InterestMethod.SIMPLE, { method = InterestMethod.SIMPLE }, { Text(stringResource(R.string.tools_simple)) })
        }
        schedule?.let { sch ->
            Row(Modifier.padding(top = 8.dp)) {
                KpiValue(stringResource(R.string.tools_installment), sch.installments.first().paymentMinor, base, Modifier.weight(1f))
                KpiValue(stringResource(R.string.tools_total_interest), sch.totalInterestMinor, base, Modifier.weight(1f))
            }
            KpiValue(stringResource(R.string.tools_total_paid), sch.totalPaymentMinor, base, Modifier.padding(top = 8.dp))
            Text(stringResource(R.string.tools_schedule), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            sch.installments.take(24).forEach { inst ->
                Row(Modifier.fillMaxWidth()) {
                    Text(Digits.localize(inst.number.toString(), formats.settings.digits), Modifier.weight(0.4f), style = MaterialTheme.typography.bodySmall)
                    MoneyText(inst.principalMinor, base, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, withCurrency = false)
                    MoneyText(inst.interestMinor, base, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, withCurrency = false)
                    MoneyText(inst.balanceAfterMinor, base, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, withCurrency = false)
                }
            }
            Text(stringResource(R.string.tools_schedule_cols), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SpendingStats(s: ToolsState) {
    val formats = LocalFormats.current
    if (s.lastMonths.isEmpty()) return
    val income = s.lastMonths.sumOf { it.incomeMinor }
    val expense = s.lastMonths.sumOf { it.expenseMinor }
    SectionCard(title = stringResource(R.string.tools_stats)) {
        Row {
            KpiValue(stringResource(R.string.tools_avg_month), expense / s.lastMonths.size, s.base, Modifier.weight(1f))
            KpiValue(stringResource(R.string.tools_avg_day), if (s.days > 0) expense / s.days else 0, s.base, Modifier.weight(1f))
        }
        val rate = if (income > 0) (income - expense) * 100.0 / income else null
        Text(
            stringResource(R.string.rep_savings_rate) + ": " + (rate?.let { Digits.isolateLtr(Digits.localize(String.format(Locale.ROOT, "%.1f%%", it), formats.settings.digits)) } ?: "—"),
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(stringResource(R.string.tools_last3), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CutScenario(s: ToolsState) {
    if (s.lastMonths.isEmpty()) return
    val byCat = s.lastMonths.flatMap { it.byCategory.entries }.groupBy({ it.key }, { it.value.netExpenseMinor })
        .mapValues { it.value.sum() / s.lastMonths.size }.filterValues { it > 0 }.entries.sortedByDescending { it.value }.take(8)
    if (byCat.isEmpty()) return
    var selected by rememberSaveable { mutableStateOf(byCat.first().key) }
    var percent by remember { mutableFloatStateOf(20f) }
    val monthly = byCat.firstOrNull { it.key == selected }?.value ?: 0
    val saving = (monthly * percent / 100).toLong()
    val formats = LocalFormats.current
    SectionCard(title = stringResource(R.string.tools_cut)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            byCat.forEach { (cat, _) -> FilterChip(selected == cat, { selected = cat }, { Text(categoryLabel(s.categories[cat])) }) }
        }
        Text(Digits.localize("${percent.toInt()}%", formats.settings.digits))
        Slider(percent, { percent = it }, valueRange = 5f..100f, steps = 18)
        Row {
            KpiValue(stringResource(R.string.tools_saving_month), saving, s.base, Modifier.weight(1f))
            KpiValue(stringResource(R.string.tools_saving_year), saving * 12, s.base, Modifier.weight(1f))
        }
        EstimateNote(stringResource(R.string.tools_cut_note))
    }
}
