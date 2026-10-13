package com.mymoney.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mymoney.core.domain.settings.AppSettings
import com.mymoney.core.domain.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class MainUiState(
    /** Null until DataStore has been read once; the UI shows nothing (no flicker of wrong theme). */
    val settings: AppSettings? = null,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
) : ViewModel() {
    val state: StateFlow<MainUiState> = settingsRepository.settings
        .map { MainUiState(settings = it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MainUiState())
}
