package com.mymoney.app.feature.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoney.app.R
import com.mymoney.app.ui.common.accountTypeLabel
import com.mymoney.app.ui.common.AccountRowContent
import com.mymoney.core.designsystem.component.AmountColor
import com.mymoney.core.designsystem.component.EmptyState
import com.mymoney.core.designsystem.component.InfoBanner
import com.mymoney.core.designsystem.component.KpiValue
import com.mymoney.core.designsystem.component.LoadingSkeleton
import com.mymoney.core.designsystem.component.MoneyText
import com.mymoney.core.designsystem.component.SectionCard
import com.mymoney.core.designsystem.format.LocalFormats
import com.mymoney.core.designsystem.theme.MyMoneyTheme
import com.mymoney.core.domain.calc.AccountValue
import com.mymoney.core.domain.calc.NetWorthSummary

@Composable
fun AccountsScreen(
    contentPadding: PaddingValues,
    onOpenAccount: (String) -> Unit,
    onAddAccount: () -> Unit,
    onOpenRates: () -> Unit,
    onOpenAsset: (String) -> Unit = {},
    onAddAsset: () -> Unit = {},
    onOpenObligations: (receivables: Boolean) -> Unit = {},
    viewModel: AccountsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val summary = state.summary
    when {
        state.loading || summary == null -> LoadingSkeleton(Modifier.padding(contentPadding))
        summary.accounts.none { !it.account.account.type.isObligation } -> EmptyState(
            icon = Icons.Outlined.AccountBalance,
            title = stringResource(R.string.acc_empty_title),
            message = stringResource(R.string.acc_empty_message),
            actionLabel = stringResource(R.string.acc_add),
            onAction = onAddAccount,
            modifier = Modifier.padding(contentPadding),
        )
        else -> AccountsList(state, summary, contentPadding, onOpenAccount, onAddAccount, onOpenRates, onOpenAsset, onAddAsset, onOpenObligations)
    }
}

@Composable
private fun AccountsList(
    state: AccountsUiState,
    summary: NetWorthSummary,
    contentPadding: PaddingValues,
    onOpenAccount: (String) -> Unit,
    onAddAccount: () -> Unit,
    onOpenRates: () -> Unit,
    onOpenAsset: (String) -> Unit,
    onAddAsset: () -> Unit,
    onOpenObligations: (Boolean) -> Unit,
) {
    var showArchived by rememberSaveable { mutableStateOf(false) }
    val spacing = MyMoneyTheme.spacing
    val formats = LocalFormats.current
    val active = summary.accounts.filter { !it.account.account.isArchived && !it.account.account.type.isObligation }
    val archived = summary.accounts.filter { it.account.account.isArchived && !it.account.account.type.isObligation }
    val grouped = active.groupBy { it.account.account.type }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = spacing.screenPadding, end = spacing.screenPadding,
            top = contentPadding.calculateTopPadding() + spacing.s, bottom = contentPadding.calculateBottomPadding() + 96.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.cardGap),
    ) {
        item(key = "nw") {
            SectionCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                Text(stringResource(R.string.home_net_worth), style = MaterialTheme.typography.labelLarge)
                MoneyText(summary.netWorthMinor, summary.baseCurrency, style = MaterialTheme.typography.displaySmall)
                Row(Modifier.fillMaxWidth().padding(top = spacing.m)) {
                    KpiValue(stringResource(R.string.home_assets), summary.totalAssetsMinor, summary.baseCurrency, Modifier.weight(1f), AmountColor.INCOME)
                    KpiValue(stringResource(R.string.home_liabilities), summary.totalLiabilitiesMinor, summary.baseCurrency, Modifier.weight(1f), AmountColor.EXPENSE)
                }
            }
        }
        if (summary.missing.isNotEmpty()) {
            item(key = "missing") {
                InfoBanner(
                    stringResource(R.string.home_missing_rates, summary.missing.joinToString("، ") { formats.money.format(it.amountMinor, it.currency) }),
                    actionLabel = stringResource(R.string.rate_add),
                    onAction = onOpenRates,
                )
            }
        } else if (summary.anyApproximate) {
            item(key = "approx") { InfoBanner(stringResource(R.string.home_approximate_rates), actionLabel = stringResource(R.string.rate_title), onAction = onOpenRates) }
        }
        grouped.forEach { (type, list) ->
            item(key = "h-$type") {
                Text(stringResource(accountTypeLabel(type)), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
            item(key = "g-$type") {
                SectionCard(modifier = Modifier) {
                    list.forEach { AccountListRow(it, summary.baseCurrency) { onOpenAccount(it.account.account.id) } }
                }
            }
        }
        item(key = "add") {
            TextButton(onClick = onAddAccount) { Text(stringResource(R.string.acc_add)) }
        }
        item(key = "assets") {
            SectionCard(title = stringResource(R.string.asset_title), actionLabel = stringResource(R.string.action_add), onAction = onAddAsset) {
                if (state.holdings.isEmpty()) Text(stringResource(R.string.asset_empty), style = MaterialTheme.typography.bodyMedium)
                if (state.staleAssets > 0) {
                    InfoBanner(stringResource(R.string.asset_stale_count, state.staleAssets))
                }
                state.holdings.forEach { h ->
                    ListItem(
                        modifier = Modifier.clickable { onOpenAsset(h.asset.id) },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        headlineContent = { Text(h.asset.name) },
                        supportingContent = {
                            Text(com.mymoney.core.domain.money.Digits.localize(h.quantity.toPlainString(), formats.settings.digits) + " " + h.asset.unit)
                        },
                        trailingContent = {
                            Column(horizontalAlignment = Alignment.End) {
                                val v = h.valueMinor
                                if (v == null) Text(stringResource(R.string.asset_no_value), style = MaterialTheme.typography.bodySmall, color = MyMoneyTheme.finance.warning)
                                else MoneyText(v, h.asset.currency)
                                h.unrealizedMinor?.let { MoneyText(it, h.asset.currency, style = MaterialTheme.typography.bodySmall, color = AmountColor.SIGNED, sign = com.mymoney.core.domain.money.SignDisplay.ALWAYS) }
                            }
                        },
                    )
                }
            }
        }
        item(key = "obligations") {
            SectionCard(title = stringResource(R.string.ob_title)) {
                ListItem(
                    modifier = Modifier.clickable { onOpenObligations(false) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    headlineContent = { Text(stringResource(R.string.ob_debts)) },
                    supportingContent = { Text(stringResource(R.string.ob_open_count, state.debtCount)) },
                    trailingContent = { MoneyText(state.debtsBase, summary.baseCurrency, color = AmountColor.EXPENSE) },
                )
                ListItem(
                    modifier = Modifier.clickable { onOpenObligations(true) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    headlineContent = { Text(stringResource(R.string.ob_receivables)) },
                    supportingContent = { Text(stringResource(R.string.ob_open_count, state.receivableCount)) },
                    trailingContent = { MoneyText(state.receivablesBase, summary.baseCurrency, color = AmountColor.INCOME) },
                )
            }
        }
        if (archived.isNotEmpty()) {
            item(key = "arch-h") {
                TextButton(onClick = { showArchived = !showArchived }) {
                    Text(stringResource(R.string.acc_archived_section, archived.size))
                }
            }
            if (showArchived) {
                items(archived, key = { "a-" + it.account.account.id }) { AccountListRow(it, summary.baseCurrency) { onOpenAccount(it.account.account.id) } }
            }
        }
    }
}

@Composable
private fun AccountListRow(v: AccountValue, base: String, onClick: () -> Unit) {
    val acc = v.account.account
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        headlineContent = { AccountRowContent(acc.name, acc.type, acc.iconKey, acc.colorKey) },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                MoneyText(v.account.balanceMinor, acc.currency, color = if (v.account.balanceMinor < 0) AmountColor.EXPENSE else AmountColor.NEUTRAL)
                val baseMinor = v.baseMinor
                if (acc.currency != base && baseMinor != null) {
                    MoneyText(baseMinor, base, style = MaterialTheme.typography.bodySmall, color = AmountColor.NEUTRAL)
                }
                if (v.account.pendingMinor != 0L) {
                    Text(stringResource(R.string.tx_pending), style = MaterialTheme.typography.labelSmall, color = MyMoneyTheme.finance.warning)
                }
            }
        },
    )
}
