package com.mymoney.app.feature.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mymoney.core.domain.calc.NetWorthCalculator
import com.mymoney.core.domain.calc.NetWorthSummary
import com.mymoney.core.domain.calc.RateBook
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.repository.AccountRepository
import com.mymoney.core.domain.repository.CurrencyRepository
import com.mymoney.core.domain.repository.ExchangeRateRepository
import com.mymoney.core.domain.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

data class AccountsUiState(
    val loading: Boolean = true,
    val summary: NetWorthSummary? = null,
    val showArchived: Boolean = false,
)

/** Net worth from live balances (FBR-10). Recomputed only when balances, rates or settings change. */
@HiltViewModel
class AccountsViewModel @Inject constructor(
    accounts: AccountRepository,
    rates: ExchangeRateRepository,
    currencies: CurrencyRepository,
    settings: SettingsRepository,
    clock: TimeProvider,
) : ViewModel() {
    val state: StateFlow<AccountsUiState> = combine(
        accounts.observeWithBalances(includeArchived = true),
        rates.observeAll(),
        currencies.observeAll(),
        settings.settings,
    ) { list, r, c, s ->
        val book = RateBook(r, c.associateBy { it.code }, s.baseCurrency)
        AccountsUiState(
            loading = false,
            summary = NetWorthCalculator.compute(list, book, s.baseCurrency, clock.today()),
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsUiState())
}
