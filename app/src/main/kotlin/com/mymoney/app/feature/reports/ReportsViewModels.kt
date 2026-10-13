package com.mymoney.app.feature.reports

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.mymoney.app.FeatureFlags
import com.mymoney.app.data.FinanceSnapshot
import com.mymoney.app.data.FinanceSnapshotProvider
import com.mymoney.app.navigation.ReportDetailRoute
import com.mymoney.core.domain.insights.Insight
import com.mymoney.core.domain.repository.LedgerAnalytics
import com.mymoney.core.domain.repository.LedgerRepository
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

/** Insights the app can act on today; features not yet available are left out (FeatureFlags). */
internal fun List<Insight>.available(): List<Insight> = filter { it !is Insight.BackupOverdue || FeatureFlags.BACKUP }

data class InsightsState(val loading: Boolean = true, val snapshot: FinanceSnapshot? = null, val insights: List<Insight> = emptyList())

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InsightsViewModel @Inject constructor(
    provider: FinanceSnapshotProvider,
    ledger: LedgerRepository,
    analytics: LedgerAnalytics,
) : ViewModel() {
    private val builder = ReportBuilder(ledger, analytics)
    val state: StateFlow<InsightsState> = provider.snapshot
        .mapLatest { s -> InsightsState(false, s, builder.insights(s).available()) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsState())
}

data class ReportUiState(
    val loading: Boolean = true,
    val type: ReportType,
    val kind: PeriodKind = PeriodKind.MONTH,
    val offset: Int = 0,
    val snapshot: FinanceSnapshot? = null,
    val data: ReportData? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReportDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    provider: FinanceSnapshotProvider,
    ledger: LedgerRepository,
    analytics: LedgerAnalytics,
) : ViewModel() {
    val type: ReportType = ReportType.valueOf(savedState.toRoute<ReportDetailRoute>().type)
    private val builder = ReportBuilder(ledger, analytics)
    private val params = MutableStateFlow(PeriodKind.MONTH to 0)

    val state: StateFlow<ReportUiState> = combine(provider.snapshot, params) { s, p -> s to p }
        .mapLatest { (s, p) ->
            ReportUiState(false, type, p.first, p.second, s, builder.build(s, type, p.first, p.second))
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportUiState(type = type))

    fun shift(delta: Int) {
        params.value = params.value.let { it.first to (it.second + delta).coerceAtMost(if (type == ReportType.RECURRING) 12 else 0) }
    }

    fun setKind(kind: PeriodKind) {
        params.value = kind to 0
    }
}
