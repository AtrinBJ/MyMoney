package com.mymoney.app.feature.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mymoney.app.data.FinanceSnapshot
import com.mymoney.app.data.FinanceSnapshotProvider
import com.mymoney.core.domain.calc.AssetHolding
import com.mymoney.core.domain.calc.NetWorthSummary
import com.mymoney.core.domain.model.ObligationDirection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class AccountsUiState(
    val loading: Boolean = true,
    val summary: NetWorthSummary? = null,
    val holdings: List<AssetHolding> = emptyList(),
    /** Asset value in base currency per asset id (null when no valuation or rate). */
    val holdingBase: Map<String, Long?> = emptyMap(),
    val debtsBase: Long = 0,
    val receivablesBase: Long = 0,
    val debtCount: Int = 0,
    val receivableCount: Int = 0,
    val staleAssets: Int = 0,
)

/** Wealth tab: net worth = accounts + assets − debts, each counted once (FBR-10). */
@HiltViewModel
class AccountsViewModel @Inject constructor(provider: FinanceSnapshotProvider) : ViewModel() {
    val state: StateFlow<AccountsUiState> = provider.snapshot.map(::toState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsUiState())

    private fun toState(s: FinanceSnapshot): AccountsUiState {
        val open = s.obligations.filter { ob -> (s.accountsById[ob.accountId]?.balanceMinor ?: 0) != 0L }
        fun total(dir: ObligationDirection) = open.filter { it.direction == dir }.sumOf { ob ->
            val a = s.accountsById[ob.accountId] ?: return@sumOf 0L
            s.toBase(kotlin.math.abs(a.balanceMinor), a.account.currency) ?: 0L
        }
        val holdings = s.holdings.filter { !it.asset.isArchived }
        return AccountsUiState(
            loading = false,
            summary = s.netWorth,
            holdings = holdings,
            holdingBase = holdings.associate { h -> h.asset.id to h.valueMinor?.let { s.toBase(it, h.asset.currency) } },
            debtsBase = total(ObligationDirection.PAYABLE),
            receivablesBase = total(ObligationDirection.RECEIVABLE),
            debtCount = open.count { it.direction == ObligationDirection.PAYABLE },
            receivableCount = open.count { it.direction == ObligationDirection.RECEIVABLE },
            staleAssets = holdings.count { it.quantity.signum() > 0 && it.isStale(s.today) },
        )
    }
}
