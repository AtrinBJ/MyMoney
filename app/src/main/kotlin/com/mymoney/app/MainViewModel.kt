package com.mymoney.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mymoney.core.domain.money.Currency
import com.mymoney.core.domain.repository.CurrencyRepository
import com.mymoney.core.domain.settings.AppSettings
import com.mymoney.core.domain.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MainUiState(
    /** Null until DataStore has been read once; the UI shows nothing (no flicker of wrong theme). */
    val settings: AppSettings? = null,
    val currencies: Map<String, Currency> = emptyMap(),
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    currencyRepository: CurrencyRepository,
) : ViewModel() {
    val state: StateFlow<MainUiState> = combine(settingsRepository.settings, currencyRepository.observeAll()) { s, c ->
        MainUiState(settings = s, currencies = c.associateBy { it.code })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MainUiState())

    init {
        // "Start with amounts hidden": applied once per process start.
        viewModelScope.launch {
            if (settingsRepository.settings.first().hideAmountsOnStart) {
                settingsRepository.update { it.copy(hideAmounts = true) }
            }
        }
    }

    fun toggleHideAmounts() {
        viewModelScope.launch { settingsRepository.update { it.copy(hideAmounts = !it.hideAmounts) } }
    }
}
