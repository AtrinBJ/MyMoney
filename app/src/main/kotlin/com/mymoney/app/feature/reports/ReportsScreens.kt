package com.mymoney.app.feature.reports

import androidx.annotation.StringRes
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoney.app.R
import com.mymoney.app.data.FinanceSnapshot
import com.mymoney.app.feature.planning.BudgetRow
import com.mymoney.app.feature.planning.GoalRow
import com.mymoney.app.feature.planning.ruleKindLabel
import com.mymoney.app.feature.wealth.obligationKindLabel
import com.mymoney.app.ui.common.BackTopBar
import com.mymoney.app.ui.common.categoryLabel
import com.mymoney.core.designsystem.chart.BarChart
import com.mymoney.core.designsystem.chart.ChartSeries
import com.mymoney.core.designsystem.chart.Donut
import com.mymoney.core.designsystem.chart.DonutSlice
import com.mymoney.core.designsystem.chart.LineChart
import com.mymoney.core.designsystem.component.AmountColor
import com.mymoney.core.designsystem.component.ChangeBadge
import com.mymoney.core.designsystem.component.ColorKeys
import com.mymoney.core.designsystem.component.EstimateNote
import com.mymoney.core.designsystem.component.InfoBanner
import com.mymoney.core.designsystem.component.KpiValue
import com.mymoney.core.designsystem.component.LoadingSkeleton
import com.mymoney.core.designsystem.component.MoneyText
import com.mymoney.core.designsystem.component.ProgressBar
import com.mymoney.core.designsystem.component.SectionCard
import com.mymoney.core.designsystem.format.Formats
import com.mymoney.core.designsystem.format.LocalFormats
import com.mymoney.core.designsystem.theme.MyMoneyTheme
import com.mymoney.core.domain.calc.IncomeStatement
import com.mymoney.core.domain.calendar.DateRange
import com.mymoney.core.domain.insights.Insight
import com.mymoney.core.domain.insights.Severity
import com.mymoney.core.domain.model.ObligationDirection
import com.mymoney.core.domain.model.RuleKind
import com.mymoney.core.domain.money.Digits
import com.mymoney.core.domain.settings.AppLanguage
import java.util.Locale

private data class ReportInfo(val type: ReportType, @StringRes val title: Int, @StringRes val description: Int, val icon: ImageVector)

private val catalog = listOf(
    ReportInfo(ReportType.INCOME_EXPENSE, R.string.rpt_income_expense, R.string.rpt_income_expense_d, Icons.Filled.BarChart),
    ReportInfo(ReportType.YEARLY, R.string.rpt_yearly, R.string.rpt_yearly_d, Icons.Filled.CalendarMonth),
    ReportInfo(ReportType.CATEGORIES, R.string.rpt_categories, R.string.rpt_categories_d, Icons.Filled.PieChart),
    ReportInfo(ReportType.COMPARISON, R.string.rpt_comparison, R.string.rpt_comparison_d, Icons.Filled.CompareArrows),
    ReportInfo(ReportType.CASH_FLOW, R.string.rpt_cash_flow, R.string.rpt_cash_flow_d, Icons.Filled.SwapVert),
    ReportInfo(ReportType.BALANCES, R.string.rpt_balances, R.string.rpt_balances_d, Icons.Filled.ShowChart),
    ReportInfo(ReportType.FIXED_VARIABLE, R.string.rpt_fixed_variable, R.string.rpt_fixed_variable_d, Icons.Filled.DonutLarge),
    ReportInfo(ReportType.BUDGETS, R.string.rpt_budgets, R.string.rpt_budgets_d, Icons.Filled.Wallet),
    ReportInfo(ReportType.INCOME_SOURCES, R.string.rpt_income_sources, R.string.rpt_income_sources_d, Icons.AutoMirrored.Filled.TrendingUp),
    ReportInfo(ReportType.DEBTS, R.string.rpt_debts, R.string.rpt_debts_d, Icons.Filled.AccountBalance),
    ReportInfo(ReportType.NET_WORTH, R.string.rpt_net_worth, R.string.rpt_net_worth_d, Icons.Filled.Timeline),
    ReportInfo(ReportType.GOALS, R.string.rpt_goals, R.string.rpt_goals_d, Icons.Filled.Flag),
    ReportInfo(ReportType.RECURRING, R.string.rpt_recurring, R.string.rpt_recurring_d, Icons.Filled.Repeat),
)

/** Reports tab: the insights entry plus the report catalog (docs/05 §Reports). */
@Composable
fun ReportsHomeScreen(
    contentPadding: PaddingValues,
    onOpenReport: (ReportType) -> Unit,
    onOpenInsights: () -> Unit,
    viewModel: InsightsViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val spacing = MyMoneyTheme.spacing
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = spacing.screenPadding, end = spacing.screenPadding,
            top = contentPadding.calculateTopPadding() + spacing.s, bottom = contentPadding.calculateBottomPadding() + 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.s),
    ) {
        item(key = "insights") {
            SectionCard(
                title = stringResource(R.string.ins_title),
                actionLabel = stringResource(R.string.home_see_all),
                onAction = onOpenInsights,
                onClick = onOpenInsights,
            ) {
                val snapshot = s.snapshot
                when {
                    s.loading || snapshot == null -> Text(stringResource(R.string.ins_loading), style = MaterialTheme.typography.bodySmall)
                    s.insights.isEmpty() -> Text(stringResource(R.string.ins_none), style = MaterialTheme.typography.bodyMedium)
                    else -> s.insights.take(3).forEach { InsightLine(it, snapshot) }
                }
            }
        }
        items(catalog, key = { it.type.name }) { r ->
            SectionCard(onClick = { onOpenReport(r.type) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(r.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(spacing.m))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(r.title), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(r.description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun InsightLine(i: Insight, s: FinanceSnapshot) {
    val (title, _) = insightText(i, s)
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        SeverityIcon(i.severity)
        Spacer(Modifier.width(MyMoneyTheme.spacing.s))
        Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SeverityIcon(severity: Severity) {
    val (icon, tint) = when (severity) {
        Severity.ALERT -> Icons.Filled.Warning to MyMoneyTheme.finance.expense
        Severity.WARNING -> Icons.Filled.Warning to MyMoneyTheme.finance.warning
        Severity.INFO -> Icons.Filled.Lightbulb to MaterialTheme.colorScheme.primary
    }
    Icon(icon, contentDescription = stringResource(severityLabel(severity)), tint = tint, modifier = Modifier.size(20.dp))
}

@StringRes
private fun severityLabel(s: Severity) = when (s) {
    Severity.ALERT -> R.string.ins_sev_alert
    Severity.WARNING -> R.string.ins_sev_warning
    Severity.INFO -> R.string.ins_sev_info
}

/** Title and evidence for an insight; every claim shows the numbers behind it (FBR-29). */
@Composable
fun insightText(i: Insight, s: FinanceSnapshot): Pair<String, String> {
    val f = LocalFormats.current
    fun money(minor: Long, currency: String = s.base) = f.money.format(minor, currency)
    // Averages and projections are rounded so they do not look more exact than they are.
    fun approx(minor: Long, currency: String = s.base): String {
        val step = if ((s.currencies[currency]?.minorDigits ?: 2) == 0) 10_000L else 100L
        return money(Math.round(minor.toDouble() / step) * step, currency)
    }
    @Composable fun cat(id: String?) = categoryLabel(id?.let { s.categoriesById[it] })
    fun num(n: Number) = Digits.localize(n.toString(), f.settings.digits)
    return when (i) {
        is Insight.CategorySpike -> stringResource(R.string.ins_spike_t, cat(i.categoryId), num(i.increasePercent)) to
            stringResource(R.string.ins_spike_b, money(i.currentMinor), approx(i.averageMinor))
        is Insight.SpendingConcentration -> stringResource(R.string.ins_conc_t, cat(i.categoryId), num(i.sharePercent)) to
            stringResource(R.string.ins_conc_b)
        is Insight.HighSpendingMonth -> stringResource(R.string.ins_high_t, f.dates.monthYear(i.month.start)) to
            stringResource(R.string.ins_high_b, money(i.expenseMinor), approx(i.meanMinor))
        is Insight.SavingsRateDrop -> stringResource(R.string.ins_savings_t) to
            stringResource(R.string.ins_savings_b, pct(i.currentPercent, f), pct(i.averagePercent, f))
        is Insight.Subscriptions -> stringResource(R.string.ins_subs_t, approx(i.monthlyTotalMinor)) to
            (
                i.sharePercentOfExpenses?.let { stringResource(R.string.ins_subs_share, num(it)) + " " }.orEmpty() +
                    i.topRuleIds.mapNotNull { id -> s.rules.firstOrNull { it.id == id }?.name }.joinToString(if (f.language == AppLanguage.FA) "، " else ", ")
                )
        is Insight.BudgetAtRisk -> stringResource(R.string.ins_budget_risk_t, i.name) to
            stringResource(R.string.ins_budget_risk_b, approx(i.projectedMinor, i.currency), money(i.limitMinor, i.currency))
        is Insight.BudgetExceeded -> stringResource(R.string.ins_budget_over_t, i.name) to
            stringResource(R.string.ins_budget_over_b, money(i.usedMinor, i.currency), money(i.limitMinor, i.currency))
        is Insight.StaleValuation -> stringResource(R.string.ins_stale_t, i.name) to
            (i.daysOld?.let { stringResource(R.string.ins_stale_b, num(it)) } ?: stringResource(R.string.ins_stale_never))
        is Insight.BackupOverdue -> stringResource(R.string.ins_backup_t) to
            (i.daysSince?.let { stringResource(R.string.ins_backup_b, num(it)) } ?: stringResource(R.string.ins_backup_never))
        is Insight.OverduePayments -> stringResource(R.string.ins_overdue_t, num(i.count)) to
            stringResource(R.string.ins_overdue_b, money(i.totalMinor, i.currency))
    }
}

private fun pct(v: Double, f: Formats) = Digits.isolateLtr(Digits.localize(String.format(Locale.ROOT, "%.1f%%", v), f.settings.digits))

/** Where tapping an insight leads; null when there is nothing to open. */
sealed interface InsightTarget {
    data class Budget(val id: String) : InsightTarget
    data class Asset(val id: String) : InsightTarget
    data object Rules : InsightTarget
    data object Subscriptions : InsightTarget
    data object Categories : InsightTarget
    data object Backup : InsightTarget
}

private fun Insight.target(): InsightTarget? = when (this) {
    is Insight.BudgetAtRisk -> InsightTarget.Budget(budgetId)
    is Insight.BudgetExceeded -> InsightTarget.Budget(budgetId)
    is Insight.StaleValuation -> InsightTarget.Asset(assetId)
    is Insight.OverduePayments -> InsightTarget.Rules
    is Insight.Subscriptions -> InsightTarget.Subscriptions
    is Insight.CategorySpike, is Insight.SpendingConcentration -> InsightTarget.Categories
    is Insight.BackupOverdue -> InsightTarget.Backup
    else -> null
}

@Composable
fun InsightsScreen(onBack: () -> Unit, onOpen: (InsightTarget) -> Unit, viewModel: InsightsViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val spacing = MyMoneyTheme.spacing
    Scaffold(topBar = { BackTopBar(stringResource(R.string.ins_title), onBack) }) { padding ->
        val snapshot = s.snapshot
        if (s.loading || snapshot == null) {
            LoadingSkeleton(Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(spacing.screenPadding, spacing.s, spacing.screenPadding, 32.dp),
            verticalArrangement = Arrangement.spacedBy(spacing.cardGap),
        ) {
            item(key = "about") { EstimateNote(stringResource(R.string.ins_about)) }
            if (s.insights.isEmpty()) {
                item(key = "empty") { Text(stringResource(R.string.ins_none), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(spacing.l)) }
            }
            items(s.insights, key = { it.toString() }) { i ->
                val (title, body) = insightText(i, snapshot)
                val target = i.target()
                SectionCard(onClick = target?.let { t -> { onOpen(t) } }) {
                    Row(verticalAlignment = Alignment.Top) {
                        SeverityIcon(i.severity)
                        Spacer(Modifier.width(spacing.m))
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleSmall)
                            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Report detail
// ---------------------------------------------------------------------------------------------------------------

@Composable
fun ReportDetailScreen(onBack: () -> Unit, onOpenRates: () -> Unit, viewModel: ReportDetailViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val info = catalog.first { it.type == viewModel.type }
    val spacing = MyMoneyTheme.spacing
    Scaffold(topBar = { BackTopBar(stringResource(info.title), onBack) }) { padding ->
        val snapshot = s.snapshot
        val data = s.data
        if (s.loading || snapshot == null || data == null) {
            LoadingSkeleton(Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(spacing.screenPadding, spacing.s, spacing.screenPadding, 32.dp),
            verticalArrangement = Arrangement.spacedBy(spacing.cardGap),
        ) {
            item(key = "desc") { Text(stringResource(info.description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (s.type.hasKindToggle()) {
                item(key = "kind") {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(s.kind == PeriodKind.MONTH, { viewModel.setKind(PeriodKind.MONTH) }, SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.rpt_month)) }
                        SegmentedButton(s.kind == PeriodKind.YEAR, { viewModel.setKind(PeriodKind.YEAR) }, SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.rpt_year)) }
                    }
                }
            }
            if (s.type.hasNavigator()) {
                item(key = "nav") {
                    PeriodNavigator(periodLabel(data, s.kind), canNext = s.offset < if (s.type == ReportType.RECURRING) 12 else 0, onShift = viewModel::shift)
                }
            }
            reportBody(data, snapshot, onOpenRates)
        }
    }
}

private fun ReportType.hasKindToggle() = this in setOf(
    ReportType.CASH_FLOW, ReportType.COMPARISON, ReportType.FIXED_VARIABLE, ReportType.INCOME_SOURCES, ReportType.NET_WORTH, ReportType.RECURRING,
)

private fun ReportType.hasNavigator() = this != ReportType.DEBTS && this != ReportType.GOALS

@Composable
private fun periodLabel(d: ReportData, kind: PeriodKind): String {
    val f = LocalFormats.current
    fun one(r: DateRange) = if (kind == PeriodKind.YEAR) Digits.localize(f.calendar.toYmd(r.start).year.toString(), f.settings.digits) else f.dates.monthYear(r.start)
    fun span(a: DateRange, b: DateRange) = f.dates.monthYear(a.start) + " – " + f.dates.monthYear(b.endInclusive)
    return when (d) {
        is ReportData.Series -> if (d.periods.size > 1 && d.periods.first().lengthInDays > 40 * 6) {
            Digits.localize("${f.calendar.toYmd(d.periods.first().start).year} – ${f.calendar.toYmd(d.periods.last().start).year}", f.settings.digits)
        } else {
            span(d.periods.first(), d.periods.last())
        }
        is ReportData.CashFlow -> one(d.range)
        is ReportData.Comparison -> one(d.current)
        is ReportData.FixedVariable -> one(d.range)
        is ReportData.Budgets -> one(d.range)
        is ReportData.IncomeSources -> one(d.range)
        is ReportData.NetWorth -> one(d.range)
        is ReportData.Balances -> f.dates.monthYear(d.points.first()) + " – " + f.dates.monthYear(d.points.last())
        is ReportData.Recurring -> one(d.range)
        is ReportData.Debts, is ReportData.Goals -> ""
    }
}

@Composable
private fun PeriodNavigator(label: String, canNext: Boolean, onShift: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onShift(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.rpt_previous)) }
        Text(label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        IconButton(onClick = { onShift(1) }, enabled = canNext) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.rpt_next)) }
    }
}

/** Short chart-axis label: month name in English, month number in Persian (Persian month names are long). */
private fun axisMonth(f: Formats, r: DateRange): String {
    val ymd = f.calendar.toYmd(r.start)
    return if (f.language == AppLanguage.FA) Digits.localize(ymd.month.toString(), f.settings.digits) else f.dates.monthName(ymd.month, short = true)
}

private fun LazyListScope.missingBanner(statements: List<IncomeStatement>, onOpenRates: () -> Unit) {
    if (statements.none { it.missing.isNotEmpty() }) return
    item(key = "missing") {
        InfoBanner(stringResource(R.string.rpt_missing_rates), actionLabel = stringResource(R.string.rate_add), onAction = onOpenRates)
    }
}

private fun LazyListScope.empty(key: String = "empty") {
    item(key = key) {
        Row(Modifier.padding(MyMoneyTheme.spacing.l), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(MyMoneyTheme.spacing.s))
            Text(stringResource(R.string.rep_no_data), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AmountRow(label: String, minor: Long, currency: String, color: AmountColor = AmountColor.NEUTRAL, bold: Boolean = false, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = if (bold) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        trailing?.let { it(); Spacer(Modifier.width(MyMoneyTheme.spacing.s)) }
        MoneyText(minor, currency, Modifier.weight(1f, fill = false), style = if (bold) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun Dot(color: Color) = Spacer(Modifier.size(10.dp).clip(CircleShape).background(color))

private fun LazyListScope.reportBody(d: ReportData, s: FinanceSnapshot, onOpenRates: () -> Unit) {
    val base = s.base
    when (d) {
        is ReportData.Series -> {
            missingBanner(d.statements, onOpenRates)
            if (d.statements.all { it.incomeMinor == 0L && it.expenseMinor == 0L }) return empty()
            item(key = "chart") {
                val f = LocalFormats.current
                val yearly = d.periods.first().lengthInDays > 300
                val labels = d.periods.map { if (yearly) Digits.localize(f.calendar.toYmd(it.start).year.toString(), f.settings.digits) else axisMonth(f, it) }
                val income = stringResource(R.string.home_income)
                val expense = stringResource(R.string.home_expense)
                SectionCard {
                    BarChart(
                        labels,
                        listOf(
                            ChartSeries(income, d.statements.map { it.incomeMinor }, MyMoneyTheme.finance.income),
                            ChartSeries(expense, d.statements.map { it.expenseMinor }, MyMoneyTheme.finance.expense),
                        ),
                        summary = stringResource(R.string.rpt_chart_summary_ie, income, expense),
                    )
                }
            }
            item(key = "totals") {
                SectionCard(title = stringResource(R.string.rpt_total)) {
                    AmountRow(stringResource(R.string.home_income), d.statements.sumOf { it.incomeMinor }, base, AmountColor.INCOME)
                    AmountRow(stringResource(R.string.home_expense), d.statements.sumOf { it.expenseMinor }, base, AmountColor.EXPENSE)
                    AmountRow(stringResource(R.string.home_net), d.statements.sumOf { it.netMinor }, base, AmountColor.SIGNED, bold = true)
                }
            }
            item(key = "rows") {
                val f = LocalFormats.current
                SectionCard(title = stringResource(R.string.rpt_by_period)) {
                    d.periods.zip(d.statements).reversed().forEach { (r, st) ->
                        val label = if (r.lengthInDays > 300) Digits.localize(f.calendar.toYmd(r.start).year.toString(), f.settings.digits) else f.dates.monthYear(r.start)
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text(label, style = MaterialTheme.typography.labelLarge)
                            Row(Modifier.fillMaxWidth()) {
                                MoneyText(st.incomeMinor, base, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = AmountColor.INCOME, compact = true)
                                MoneyText(st.expenseMinor, base, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = AmountColor.EXPENSE, compact = true)
                                MoneyText(st.netMinor, base, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = AmountColor.SIGNED, compact = true)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }

        is ReportData.CashFlow -> {
            val c = d.statement
            if (c.missing.isNotEmpty()) item(key = "missing") { InfoBanner(stringResource(R.string.rpt_missing_rates), actionLabel = stringResource(R.string.rate_add), onAction = onOpenRates) }
            item(key = "cf") {
                SectionCard {
                    AmountRow(stringResource(R.string.rpt_cf_operating_in), c.operatingIn, base, AmountColor.INCOME)
                    AmountRow(stringResource(R.string.rpt_cf_operating_out), c.operatingOut, base, AmountColor.EXPENSE)
                    AmountRow(stringResource(R.string.rpt_cf_operating), c.operatingNet, base, AmountColor.SIGNED, bold = true)
                    HorizontalDivider()
                    AmountRow(stringResource(R.string.rpt_cf_investing), c.investingNet, base, AmountColor.SIGNED)
                    AmountRow(stringResource(R.string.rpt_cf_financing), c.financingNet, base, AmountColor.SIGNED)
                    if (c.transfersOther != 0L) AmountRow(stringResource(R.string.rpt_cf_other), c.transfersOther, base, AmountColor.SIGNED)
                    if (c.openingBalances != 0L) AmountRow(stringResource(R.string.rpt_cf_opening), c.openingBalances, base, AmountColor.SIGNED)
                    if (c.adjustments != 0L) AmountRow(stringResource(R.string.rpt_cf_adjustments), c.adjustments, base, AmountColor.SIGNED)
                    HorizontalDivider()
                    AmountRow(stringResource(R.string.rpt_cf_net), c.netChange, base, AmountColor.SIGNED, bold = true)
                }
            }
            item(key = "note") { EstimateNote(stringResource(R.string.rpt_cf_note)) }
        }

        is ReportData.Balances -> {
            if (d.total.all { it == 0L }) return empty()
            item(key = "chart") {
                val f = LocalFormats.current
                val total = stringResource(R.string.rpt_bal_total)
                val liquid = stringResource(R.string.home_liquid)
                SectionCard {
                    LineChart(
                        d.points.map { f.dates.dayMonth(it) },
                        listOf(ChartSeries(total, d.total, MaterialTheme.colorScheme.primary), ChartSeries(liquid, d.liquid, MyMoneyTheme.finance.transfer)),
                        summary = stringResource(R.string.rpt_chart_summary_bal, f.money.format(d.total.last(), base)),
                    )
                }
            }
            item(key = "kpi") {
                SectionCard {
                    Row(Modifier.fillMaxWidth()) {
                        KpiValue(stringResource(R.string.rpt_bal_total), d.total.last(), base, Modifier.weight(1f), footer = { ChangeBadge(d.total.last(), d.total.first(), higherIsGood = true) })
                        KpiValue(stringResource(R.string.home_liquid), d.liquid.last(), base, Modifier.weight(1f), footer = { ChangeBadge(d.liquid.last(), d.liquid.first(), higherIsGood = true) })
                    }
                    EstimateNote(stringResource(R.string.rpt_bal_note))
                }
            }
        }

        is ReportData.Comparison -> {
            missingBanner(listOf(d.cur, d.prev), onOpenRates)
            item(key = "kpi") {
                val f = LocalFormats.current
                SectionCard(title = stringResource(R.string.rpt_vs, f.dates.monthYear(d.previous.start).takeIf { d.previous.lengthInDays < 40 } ?: Digits.localize(f.calendar.toYmd(d.previous.start).year.toString(), f.settings.digits))) {
                    AmountRow(stringResource(R.string.home_income), d.cur.incomeMinor, base, AmountColor.INCOME, trailing = { ChangeBadge(d.cur.incomeMinor, d.prev.incomeMinor, higherIsGood = true) })
                    AmountRow(stringResource(R.string.home_expense), d.cur.expenseMinor, base, AmountColor.EXPENSE, trailing = { ChangeBadge(d.cur.expenseMinor, d.prev.expenseMinor, higherIsGood = false) })
                    AmountRow(stringResource(R.string.home_net), d.cur.netMinor, base, AmountColor.SIGNED, bold = true)
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text(stringResource(R.string.rpt_previous_period), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MoneyText(d.prev.netMinor, base, style = MaterialTheme.typography.bodySmall, color = AmountColor.SIGNED)
                    }
                }
            }
            if (d.rows.isEmpty()) return empty()
            item(key = "head") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    Text(stringResource(R.string.rpt_cmp_category), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.rpt_cmp_now_before), style = MaterialTheme.typography.labelMedium)
                }
            }
            items(d.rows, key = { "c-" + (it.first ?: "none") }) { (cat, cur, prev) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(categoryLabel(cat?.let { s.categoriesById[it] }), style = MaterialTheme.typography.bodyMedium)
                        ChangeBadge(cur, prev, higherIsGood = false)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        MoneyText(cur, base, style = MaterialTheme.typography.bodyMedium)
                        MoneyText(prev, base, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        is ReportData.FixedVariable -> {
            if (d.fixed + d.variable + d.untyped == 0L) return empty()
            item(key = "fv") {
                ShareCard(
                    stringResource(R.string.rpt_fv_type),
                    listOf(
                        Triple(stringResource(R.string.rpt_fv_fixed), d.fixed, MaterialTheme.colorScheme.primary),
                        Triple(stringResource(R.string.rpt_fv_variable), d.variable, MyMoneyTheme.finance.warning),
                        Triple(stringResource(R.string.rpt_fv_unset), d.untyped, MaterialTheme.colorScheme.outline),
                    ),
                    base,
                )
            }
            item(key = "eo") {
                ShareCard(
                    stringResource(R.string.rpt_fv_need),
                    listOf(
                        Triple(stringResource(R.string.rpt_fv_essential), d.essential, MyMoneyTheme.finance.income),
                        Triple(stringResource(R.string.rpt_fv_optional), d.optional, MyMoneyTheme.finance.expense),
                        Triple(stringResource(R.string.rpt_fv_unset), d.unmarked, MaterialTheme.colorScheme.outline),
                    ),
                    base,
                )
            }
            item(key = "note") { EstimateNote(stringResource(R.string.rpt_fv_note)) }
        }

        is ReportData.Budgets -> {
            if (d.statuses.isEmpty()) return empty()
            item(key = "budgets") {
                SectionCard {
                    d.statuses.forEach { BudgetRow(it) }
                }
            }
        }

        is ReportData.IncomeSources -> {
            if (d.rows.isEmpty()) return empty()
            item(key = "donut") {
                val palette = MyMoneyTheme.finance.chart
                ShareCard(
                    null,
                    d.rows.mapIndexed { i, (id, v) ->
                        val c = id?.let { s.categoriesById[it] }
                        Triple(categoryLabel(c), v, c?.colorKey?.let { ColorKeys.color(it) } ?: palette[i % palette.size])
                    },
                    base,
                )
            }
        }

        is ReportData.Debts -> {
            if (d.rows.isEmpty()) return empty()
            item(key = "totals") {
                SectionCard {
                    Row(Modifier.fillMaxWidth()) {
                        KpiValue(stringResource(R.string.ob_debts), d.rows.filter { it.obligation.direction != ObligationDirection.RECEIVABLE }.sumOf { s.toBase(it.outstanding, it.account.account.currency) ?: 0 }, base, Modifier.weight(1f), AmountColor.EXPENSE)
                        KpiValue(stringResource(R.string.ob_receivables), d.rows.filter { it.obligation.direction == ObligationDirection.RECEIVABLE }.sumOf { s.toBase(it.outstanding, it.account.account.currency) ?: 0 }, base, Modifier.weight(1f), AmountColor.INCOME)
                    }
                }
            }
            items(d.rows, key = { it.obligation.accountId }) { r ->
                val f = LocalFormats.current
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.account.account.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(obligationKindLabel(r.obligation.kind)) + (r.obligation.dueDate?.let { " · " + stringResource(R.string.ob_due_on, f.dates.medium(it)) } ?: ""),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        MoneyText(r.outstanding, r.account.account.currency, color = if (r.obligation.direction == ObligationDirection.RECEIVABLE) AmountColor.INCOME else AmountColor.EXPENSE)
                    }
                    ProgressBar(r.paidFraction, Modifier.padding(top = 6.dp), warnAt = 2f)
                    Text(
                        stringResource(R.string.rpt_debt_paid, Digits.localize((r.paidFraction * 100).toInt().toString(), f.settings.digits)),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        is ReportData.NetWorth -> {
            item(key = "chart") {
                val f = LocalFormats.current
                val label = stringResource(R.string.home_net_worth)
                SectionCard {
                    LineChart(
                        d.points.map { f.dates.dayMonth(it) },
                        listOf(ChartSeries(label, d.values, MaterialTheme.colorScheme.primary)),
                        summary = stringResource(R.string.rpt_chart_summary_nw, f.money.format(d.values.last(), base)),
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        KpiValue(label, d.values.last(), base, Modifier.weight(1f), AmountColor.SIGNED, footer = { ChangeBadge(d.values.last(), d.values.first(), higherIsGood = true) })
                    }
                }
            }
            item(key = "fx") {
                SectionCard(title = stringResource(R.string.rpt_fx_title)) {
                    if (d.fxEffectBase == null) {
                        Text(stringResource(R.string.rpt_fx_unknown), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        MoneyText(d.fxEffectBase, base, style = MaterialTheme.typography.titleMedium, color = AmountColor.SIGNED, sign = com.mymoney.core.domain.money.SignDisplay.ALWAYS)
                    }
                    Text(stringResource(R.string.rpt_fx_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            }
            item(key = "note") { EstimateNote(stringResource(R.string.rpt_nw_note)) }
        }

        is ReportData.Goals -> {
            if (d.goals.isEmpty()) return empty()
            item(key = "goals") {
                SectionCard {
                    d.goals.forEach { g ->
                        GoalRow(g) {}
                        g.projectedDate?.let {
                            EstimateNote(stringResource(R.string.rpt_goal_projected, LocalFormats.current.dates.medium(it)))
                        }
                    }
                }
            }
        }

        is ReportData.Recurring -> {
            if (d.rows.isEmpty()) return empty()
            item(key = "totals") {
                SectionCard {
                    Row(Modifier.fillMaxWidth()) {
                        KpiValue(stringResource(R.string.rpt_rec_out), d.monthlyOut, base, Modifier.weight(1f), AmountColor.EXPENSE)
                        KpiValue(stringResource(R.string.rpt_rec_in), d.monthlyIn, base, Modifier.weight(1f), AmountColor.INCOME)
                    }
                    Text(stringResource(R.string.rpt_rec_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            }
            items(d.rows, key = { it.rule.id }) { r ->
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.rule.name, style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(ruleKindLabel(r.rule.kind)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            MoneyText(r.monthlyBase, base, color = if (r.rule.kind == RuleKind.INCOME) AmountColor.INCOME else AmountColor.EXPENSE)
                            Text(stringResource(R.string.sub_per_month), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (r.expectedThisPeriod > 0) {
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.rpt_rec_period), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            MoneyText(r.paidThisPeriod, r.rule.currency, style = MaterialTheme.typography.bodySmall, withCurrency = false)
                            Text(" / ", style = MaterialTheme.typography.bodySmall)
                            MoneyText(r.expectedThisPeriod, r.rule.currency, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

/** Donut + legend rows with shares. */
@Composable
private fun ShareCard(title: String?, parts: List<Triple<String, Long, Color>>, currency: String) {
    val f = LocalFormats.current
    val visible = parts.filter { it.second > 0 }
    val total = visible.sumOf { it.second }.takeIf { it > 0 } ?: 1
    fun share(v: Long) = Digits.isolateLtr(Digits.localize(String.format(Locale.ROOT, "%.0f%%", v * 100.0 / total), f.settings.digits))
    SectionCard(title = title) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Donut(
                visible.map { DonutSlice(it.second, it.third) },
                summary = visible.joinToString("، ") { "${it.first} ${share(it.second)}" },
) { MoneyText(total, currency, style = MaterialTheme.typography.labelLarge, compact = true) }
        }
        visible.forEach { (label, v, color) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(color)
                Spacer(Modifier.width(MyMoneyTheme.spacing.s))
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(share(v), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(MyMoneyTheme.spacing.m))
                MoneyText(v, currency, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
