package com.mymoney.app.feature.datahealth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mymoney.app.R
import com.mymoney.app.data.FinanceSnapshotProvider
import com.mymoney.app.feature.planning.PlanningMath
import com.mymoney.app.ui.common.BackTopBar
import com.mymoney.core.designsystem.component.LoadingSkeleton
import com.mymoney.core.designsystem.component.SectionCard
import com.mymoney.core.designsystem.format.LocalFormats
import com.mymoney.core.designsystem.theme.MyMoneyTheme
import com.mymoney.core.domain.calc.DueStatus
import com.mymoney.core.domain.calendar.DateRange
import com.mymoney.core.domain.model.AccountType
import com.mymoney.core.domain.model.ObligationStatus
import com.mymoney.core.domain.money.Digits
import com.mymoney.core.domain.repository.LedgerAnalytics
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

enum class HealthCheck { MISSING_RATES, APPROXIMATE_RATES, NEGATIVE_CASH, DUPLICATES, OVERDUE, SETTLED_OBLIGATIONS, ASSET_HISTORY, STALE_VALUATIONS, PENDING, BUDGET_RATES }

enum class HealthLevel { OK, INFO, ISSUE }

/** Result of one check. [names] are the items concerned; [targetId] the first one to open. */
data class HealthResult(val check: HealthCheck, val level: HealthLevel, val count: Int = 0, val names: List<String> = emptyList(), val targetId: String? = null)

/** Where tapping a check leads. */
enum class HealthAction { RATES, ACCOUNT, TRANSACTIONS, PLANNING, OBLIGATION, ASSET, NONE }

fun HealthCheck.action(): HealthAction = when (this) {
    HealthCheck.MISSING_RATES, HealthCheck.APPROXIMATE_RATES, HealthCheck.BUDGET_RATES -> HealthAction.RATES
    HealthCheck.NEGATIVE_CASH -> HealthAction.ACCOUNT
    HealthCheck.DUPLICATES, HealthCheck.PENDING -> HealthAction.TRANSACTIONS
    HealthCheck.OVERDUE -> HealthAction.PLANNING
    HealthCheck.SETTLED_OBLIGATIONS -> HealthAction.OBLIGATION
    HealthCheck.ASSET_HISTORY, HealthCheck.STALE_VALUATIONS -> HealthAction.ASSET
}

data class DataHealthState(val loading: Boolean = true, val results: List<HealthResult> = emptyList())

/** Read-only consistency checks over the ledger (docs/05 §Data health). Nothing is changed automatically. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DataHealthViewModel @Inject constructor(provider: FinanceSnapshotProvider, analytics: LedgerAnalytics) : ViewModel() {
    val state: StateFlow<DataHealthState> = provider.snapshot.mapLatest { s ->
        val results = mutableListOf<HealthResult>()
        fun add(check: HealthCheck, names: List<Pair<String, String>>, issue: Boolean = true) {
            results += if (names.isEmpty()) {
                HealthResult(check, HealthLevel.OK)
            } else {
                HealthResult(check, if (issue) HealthLevel.ISSUE else HealthLevel.INFO, names.size, names.map { it.second }, names.first().first)
            }
        }
        val missing = s.netWorth.missing.map { it.currency }.distinct()
        add(HealthCheck.MISSING_RATES, missing.map { it to it })
        add(HealthCheck.APPROXIMATE_RATES, if (s.netWorth.anyApproximate) listOf("" to "") else emptyList(), issue = false)
        val cashLike = setOf(AccountType.CASH, AccountType.BANK, AccountType.SAVINGS, AccountType.DIGITAL_WALLET)
        add(HealthCheck.NEGATIVE_CASH, s.accounts.filter { it.account.type in cashLike && it.balanceMinor < 0 && !it.account.isArchived }.map { it.account.id to it.account.name })
        val dup = analytics.possibleDuplicateCount(s.today.minusDays(90))
        add(HealthCheck.DUPLICATES, List(dup) { "" to "" }, issue = false)
        val overdue = s.occurrences(DateRange(s.today.minusDays(120), s.today.minusDays(1))).filter { it.status == DueStatus.OVERDUE }
        add(HealthCheck.OVERDUE, overdue.map { it.rule.id to it.rule.name })
        add(
            HealthCheck.SETTLED_OBLIGATIONS,
            s.obligations.filter { it.status == ObligationStatus.OPEN && s.accountsById[it.accountId]?.balanceMinor == 0L }
                .map { it.accountId to (s.accountsById[it.accountId]?.account?.name ?: it.counterparty) },
            issue = false,
        )
        val held = s.holdings.map { it.asset.id }.toSet()
        add(HealthCheck.ASSET_HISTORY, s.assets.filter { it.id !in held }.map { it.id to it.name })
        add(
            HealthCheck.STALE_VALUATIONS,
            s.holdings.filter { !it.asset.isArchived && it.quantity.signum() > 0 && it.isStale(s.today) }.map { it.asset.id to it.asset.name },
            issue = false,
        )
        val pending = analytics.pendingByAccount().filterValues { it != 0L }.keys
        add(HealthCheck.PENDING, pending.mapNotNull { id -> s.accountsById[id]?.let { id to it.account.name } }, issue = false)
        add(HealthCheck.BUDGET_RATES, PlanningMath.budgetStatuses(s, analytics, s.today).filter { it.missing.isNotEmpty() }.map { it.budget.id to it.budget.name })
        DataHealthState(false, results.sortedByDescending { it.level.ordinal })
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DataHealthState())
}

@Composable
fun DataHealthScreen(onBack: () -> Unit, onAction: (HealthAction, String?) -> Unit, viewModel: DataHealthViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val spacing = MyMoneyTheme.spacing
    Scaffold(topBar = { BackTopBar(stringResource(R.string.health_title), onBack) }) { padding ->
        if (s.loading) {
            LoadingSkeleton(Modifier.padding(padding))
            return@Scaffold
        }
        val issues = s.results.count { it.level == HealthLevel.ISSUE }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(spacing.screenPadding, spacing.s, spacing.screenPadding, 32.dp),
            verticalArrangement = Arrangement.spacedBy(spacing.s),
        ) {
            item(key = "summary") {
                val f = LocalFormats.current
                Text(
                    if (issues == 0) stringResource(R.string.health_all_ok) else stringResource(R.string.health_issues, Digits.localize(issues.toString(), f.settings.digits)),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = spacing.s),
                )
            }
            items(s.results, key = { it.check.name }) { r ->
                val action = r.check.action()
                SectionCard(onClick = if (r.level != HealthLevel.OK && action != HealthAction.NONE) ({ onAction(action, r.targetId) }) else null) {
                    Row(verticalAlignment = Alignment.Top) {
                        val (icon, tint) = when (r.level) {
                            HealthLevel.OK -> Icons.Filled.CheckCircle to MyMoneyTheme.finance.income
                            HealthLevel.INFO -> Icons.Filled.Info to MaterialTheme.colorScheme.primary
                            HealthLevel.ISSUE -> Icons.Filled.Warning to MyMoneyTheme.finance.warning
                        }
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(spacing.m))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(r.check.title()), style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (r.level == HealthLevel.OK) stringResource(R.string.health_ok) else detail(r),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun detail(r: HealthResult): String {
    val f = LocalFormats.current
    val n = Digits.localize(r.count.toString(), f.settings.digits)
    val names = r.names.filter { it.isNotBlank() }.take(3).joinToString("، ")
    val base = stringResource(r.check.detail(), n)
    return if (names.isEmpty()) base else "$base: $names"
}

private fun HealthCheck.title(): Int = when (this) {
    HealthCheck.MISSING_RATES -> R.string.health_missing_rates
    HealthCheck.APPROXIMATE_RATES -> R.string.health_approx_rates
    HealthCheck.NEGATIVE_CASH -> R.string.health_negative
    HealthCheck.DUPLICATES -> R.string.health_duplicates
    HealthCheck.OVERDUE -> R.string.health_overdue
    HealthCheck.SETTLED_OBLIGATIONS -> R.string.health_settled
    HealthCheck.ASSET_HISTORY -> R.string.health_asset_history
    HealthCheck.STALE_VALUATIONS -> R.string.health_stale
    HealthCheck.PENDING -> R.string.health_pending
    HealthCheck.BUDGET_RATES -> R.string.health_budget_rates
}

private fun HealthCheck.detail(): Int = when (this) {
    HealthCheck.MISSING_RATES -> R.string.health_missing_rates_d
    HealthCheck.APPROXIMATE_RATES -> R.string.health_approx_rates_d
    HealthCheck.NEGATIVE_CASH -> R.string.health_negative_d
    HealthCheck.DUPLICATES -> R.string.health_duplicates_d
    HealthCheck.OVERDUE -> R.string.health_overdue_d
    HealthCheck.SETTLED_OBLIGATIONS -> R.string.health_settled_d
    HealthCheck.ASSET_HISTORY -> R.string.health_asset_history_d
    HealthCheck.STALE_VALUATIONS -> R.string.health_stale_d
    HealthCheck.PENDING -> R.string.health_pending_d
    HealthCheck.BUDGET_RATES -> R.string.health_budget_rates_d
}
