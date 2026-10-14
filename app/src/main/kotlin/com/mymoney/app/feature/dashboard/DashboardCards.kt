package com.mymoney.app.feature.dashboard

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mymoney.app.R
import com.mymoney.app.data.FinanceSnapshot
import com.mymoney.app.feature.reports.insightText
import com.mymoney.core.designsystem.component.AmountColor
import com.mymoney.core.designsystem.component.EstimateNote
import com.mymoney.core.designsystem.component.MoneyText
import com.mymoney.core.designsystem.component.SectionCard
import com.mymoney.core.designsystem.component.SwitchRow
import com.mymoney.core.designsystem.format.LocalFormats
import com.mymoney.core.designsystem.theme.MyMoneyTheme
import com.mymoney.core.domain.calc.DueStatus
import com.mymoney.core.domain.calc.Forecast
import com.mymoney.core.domain.calc.Occurrence
import com.mymoney.core.domain.insights.Insight
import com.mymoney.core.domain.insights.Severity
import com.mymoney.core.domain.model.RuleKind
import com.mymoney.core.domain.money.Digits

/** Month-end estimate (FBR-28). Shown with its range and assumptions, never as a recorded figure. */
@Composable
internal fun ForecastCard(f: Forecast, base: String) {
    val formats = LocalFormats.current
    SectionCard(title = stringResource(R.string.dash_forecast_title, formats.dates.medium(f.until))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("≈ ", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.tertiary)
            MoneyText(f.projectedMinor, base, style = MaterialTheme.typography.headlineSmall, color = AmountColor.SIGNED)
        }
        if (f.hasVariableEstimate) {
            Text(
                stringResource(R.string.dash_forecast_range, formats.money.format(f.lowMinor, base), formats.money.format(f.highMinor, base)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Column(Modifier.padding(top = 8.dp)) {
            Line(R.string.dash_forecast_liquid, f.liquidNowMinor, base)
            if (f.scheduledInMinor != 0L) Line(R.string.dash_forecast_in, f.scheduledInMinor, base)
            if (f.scheduledOutMinor != 0L) Line(R.string.dash_forecast_out, -f.scheduledOutMinor, base)
            if (f.pendingMinor != 0L) Line(R.string.dash_forecast_pending, f.pendingMinor, base)
            f.variableMedianMinor?.let { Line(R.string.dash_forecast_variable, -it, base) }
        }
        EstimateNote(
            if (f.hasVariableEstimate) {
                stringResource(R.string.dash_forecast_note, Digits.localize(f.historyDays.toString(), formats.settings.digits))
            } else {
                stringResource(R.string.dash_forecast_no_history)
            },
        )
    }
}

@Composable
private fun Line(@StringRes label: Int, minor: Long, base: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        MoneyText(minor, base, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall, color = AmountColor.SIGNED)
    }
}

@Composable
internal fun InsightsCard(insights: List<Insight>, s: FinanceSnapshot, onOpen: () -> Unit) {
    SectionCard(title = stringResource(R.string.ins_title), actionLabel = stringResource(R.string.home_see_all), onAction = onOpen, onClick = onOpen) {
        insights.take(3).forEach { i ->
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val (icon, tint) = when (i.severity) {
                    Severity.ALERT -> Icons.Filled.Warning to MyMoneyTheme.finance.expense
                    Severity.WARNING -> Icons.Filled.Warning to MyMoneyTheme.finance.warning
                    Severity.INFO -> Icons.Filled.Lightbulb to MaterialTheme.colorScheme.primary
                }
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(MyMoneyTheme.spacing.s))
                Text(insightText(i, s).first, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun UpcomingCard(due: List<Occurrence>, onOpen: () -> Unit) {
    val formats = LocalFormats.current
    SectionCard(title = stringResource(R.string.dash_upcoming), actionLabel = stringResource(R.string.home_see_all), onAction = onOpen) {
        if (due.isEmpty()) {
            Text(stringResource(R.string.dash_upcoming_empty), style = MaterialTheme.typography.bodyMedium)
        }
        due.take(5).forEach { o ->
            val overdue = o.status == DueStatus.OVERDUE
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(o.rule.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        formats.dates.medium(o.dueDate) + if (overdue) " · " + stringResource(R.string.plan_overdue) else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (overdue) MyMoneyTheme.finance.expense else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MoneyText(o.outstandingMinor, o.rule.currency, Modifier.weight(1f, fill = false), color = if (o.rule.kind == RuleKind.INCOME) AmountColor.INCOME else AmountColor.NEUTRAL)
            }
        }
        if (due.size > 5) {
            Text(stringResource(R.string.dash_more, Digits.localize((due.size - 5).toString(), formats.settings.digits)), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@StringRes
private fun cardLabel(c: DashboardCard): Int = when (c) {
    DashboardCard.NET_WORTH -> R.string.home_net_worth
    DashboardCard.CASH_FLOW -> R.string.dash_card_flow
    DashboardCard.BUDGETS -> R.string.budget_title
    DashboardCard.UPCOMING -> R.string.dash_upcoming
    DashboardCard.GOALS -> R.string.goal_title
    DashboardCard.INSIGHTS -> R.string.ins_title
    DashboardCard.FORECAST -> R.string.dash_card_forecast
    DashboardCard.RECENT -> R.string.home_recent
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomizeSheet(hidden: Set<DashboardCard>, onToggle: (DashboardCard, Boolean) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(stringResource(R.string.dash_customize), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            DashboardCard.entries.forEach { c ->
                SwitchRow(title = stringResource(cardLabel(c)), checked = c !in hidden, onCheckedChange = { onToggle(c, it) })
            }
        }
    }
}
