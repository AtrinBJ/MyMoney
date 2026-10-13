package com.mymoney.app.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoney.app.R
import com.mymoney.app.ui.common.EntryRowItem
import com.mymoney.core.designsystem.component.AmountColor
import com.mymoney.core.designsystem.component.ChangeBadge
import com.mymoney.core.designsystem.component.EmptyState
import com.mymoney.core.designsystem.component.InfoBanner
import com.mymoney.core.designsystem.component.KpiValue
import com.mymoney.core.designsystem.component.LoadingSkeleton
import com.mymoney.core.designsystem.component.MoneyText
import com.mymoney.core.designsystem.component.SectionCard
import com.mymoney.core.designsystem.format.LocalFormats
import com.mymoney.core.designsystem.theme.MyMoneyTheme
import com.mymoney.core.domain.money.Digits
import java.util.Locale

@Composable
fun DashboardScreen(
    contentPadding: PaddingValues,
    onAddAccount: () -> Unit,
    onOpenEntry: (String) -> Unit,
    onSeeAllTransactions: () -> Unit,
    onOpenWealth: () -> Unit,
    onOpenReports: () -> Unit,
    onOpenRates: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val formats = LocalFormats.current
    val spacing = MyMoneyTheme.spacing
    when {
        s.loading -> LoadingSkeleton(Modifier.padding(contentPadding))
        !s.hasAccounts -> EmptyState(
            icon = Icons.Outlined.AccountBalanceWallet,
            title = stringResource(R.string.home_empty_title),
            message = stringResource(R.string.home_empty_message),
            actionLabel = stringResource(R.string.acc_add),
            onAction = onAddAccount,
            modifier = Modifier.padding(contentPadding),
        )
        else -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = spacing.screenPadding, end = spacing.screenPadding,
                top = contentPadding.calculateTopPadding() + spacing.s,
                bottom = contentPadding.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.cardGap),
        ) {
            val nw = s.netWorth!!
            val base = nw.baseCurrency
            item(key = "nw") {
                SectionCard(containerColor = MaterialTheme.colorScheme.primaryContainer, onClick = onOpenWealth) {
                    Text(stringResource(R.string.home_net_worth), style = MaterialTheme.typography.labelLarge)
                    MoneyText(nw.netWorthMinor, base, style = MaterialTheme.typography.displaySmall)
                    Row(Modifier.fillMaxWidth().padding(top = spacing.m)) {
                        KpiValue(stringResource(R.string.home_liquid), nw.liquidMinor, base, Modifier.weight(1f))
                        KpiValue(stringResource(R.string.home_liabilities), nw.totalLiabilitiesMinor, base, Modifier.weight(1f), AmountColor.EXPENSE)
                    }
                }
            }
            if (nw.missing.isNotEmpty() || s.current?.missing?.isNotEmpty() == true) {
                item(key = "missing") {
                    val all = (nw.missing + s.current!!.missing).groupBy { it.currency }.keys.joinToString("، ")
                    InfoBanner(stringResource(R.string.home_missing_rates, all), actionLabel = stringResource(R.string.rate_add), onAction = onOpenRates)
                }
            }
            item(key = "period") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                    items(DashboardPeriod.entries) { p ->
                        FilterChip(
                            selected = s.period == p,
                            onClick = { viewModel.setPeriod(p) },
                            label = {
                                Text(
                                    stringResource(
                                        when (p) {
                                            DashboardPeriod.THIS_MONTH -> R.string.period_this_month
                                            DashboardPeriod.LAST_MONTH -> R.string.period_last_month
                                            DashboardPeriod.THIS_YEAR -> R.string.period_this_year
                                        },
                                    ),
                                )
                            },
                        )
                    }
                }
            }
            val cur = s.current!!
            val prev = s.previous!!
            item(key = "flow") {
                SectionCard(
                    title = s.range?.let { r -> formats.dates.medium(r.start) + " – " + formats.dates.medium(r.endInclusive) },
                    actionLabel = stringResource(R.string.nav_reports),
                    onAction = onOpenReports,
                ) {
                    Row(Modifier.fillMaxWidth()) {
                        KpiValue(stringResource(R.string.home_income), cur.incomeMinor, base, Modifier.weight(1f), AmountColor.INCOME) {
                            ChangeBadge(cur.incomeMinor, prev.incomeMinor, higherIsGood = true)
                        }
                        KpiValue(stringResource(R.string.home_expense), cur.expenseMinor, base, Modifier.weight(1f), AmountColor.EXPENSE) {
                            ChangeBadge(cur.expenseMinor, prev.expenseMinor, higherIsGood = false)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = spacing.m)) {
                        KpiValue(stringResource(R.string.home_net), cur.netMinor, base, Modifier.weight(1f), AmountColor.SIGNED)
                        cur.savingsRatePercent?.let { rate ->
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.rep_savings_rate), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    Digits.isolateLtr(Digits.localize(String.format(Locale.ROOT, "%.1f%%", rate), formats.settings.digits)),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.home_vs_previous),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = spacing.s),
                    )
                }
            }
            item(key = "recent-h") {
                SectionCard(title = stringResource(R.string.home_recent), actionLabel = stringResource(R.string.home_see_all), onAction = onSeeAllTransactions) {
                    if (s.recent.isEmpty()) {
                        Text(stringResource(R.string.tx_empty_message), style = MaterialTheme.typography.bodyMedium)
                    }
                    s.recent.forEach { row -> EntryRowItem(row, onClick = { onOpenEntry(row.groupId) }) }
                }
            }
        }
    }
}
