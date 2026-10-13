package com.mymoney.core.domain.settings

import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<AppSettings>

    /** Atomically applies [transform] to the current settings. */
    suspend fun update(transform: (AppSettings) -> AppSettings)
}
