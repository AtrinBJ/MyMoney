package com.mymoney.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mymoney.app.locale.LocaleController
import com.mymoney.core.domain.settings.AppLanguage
import com.mymoney.core.domain.settings.AppSettings
import com.mymoney.core.domain.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings? = null,
    val language: AppLanguage = AppLanguage.FA,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val localeController: LocaleController,
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = repository.settings
        .map { SettingsUiState(settings = it, language = localeController.current()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repository.update(transform) }
    }

    /** Changing language recreates the activity; formatting defaults follow the language. */
    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch {
            val defaults = AppSettings.defaultsFor(language)
            repository.update {
                it.copy(calendar = defaults.calendar, digits = defaults.digits, weekStart = defaults.weekStart)
            }
            localeController.set(language)
        }
    }
}
