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
    /** The app lock is on exactly when a PIN is stored. */
    val pinSet: Boolean = false,
    val biometricAvailable: Boolean = false,
    /** Developer actions (debug builds only). */
    val debugActions: List<com.mymoney.app.DebugAction> = emptyList(),
    val debugMessage: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val localeController: LocaleController,
    private val pins: com.mymoney.core.data.security.PinStore,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val debugTools: com.mymoney.app.DebugTools,
) : ViewModel() {
    private val debugMessage = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    fun runDebug(action: com.mymoney.app.DebugAction) {
        debugMessage.value = "…"
        viewModelScope.launch { debugMessage.value = runCatching { action.run() }.getOrElse { it.toString() } }
    }


    val state: StateFlow<SettingsUiState> = kotlinx.coroutines.flow.combine(repository.settings, debugMessage) { s, m -> s to m }
        .map { (it, message) ->
            SettingsUiState(
                settings = it,
                language = localeController.current(),
                pinSet = pins.hasPin(),
                biometricAvailable = com.mymoney.app.lock.biometricAvailable(context),
                debugActions = debugTools.actions,
                debugMessage = message,
            )
        }
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
