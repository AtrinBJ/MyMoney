package com.mymoney.core.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.mymoney.core.domain.settings.AppSettings
import com.mymoney.core.domain.settings.CalendarType
import com.mymoney.core.domain.settings.IrrUnit
import com.mymoney.core.domain.settings.ThemeMode
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Verifies the AppSettings <-> Preferences mapping. Uses an in-memory DataStore because DataStore's file
 * backend cannot replace an existing file on Windows hosts (File.renameTo); on Android it is unaffected.
 */
class DataStoreSettingsRepositoryTest {

    private class InMemoryPreferencesStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(state.value).also { state.value = it } }
    }

    @Test
    fun `defaults when nothing stored`() = runTest {
        assertEquals(AppSettings(), DataStoreSettingsRepository(InMemoryPreferencesStore()).settings.first())
    }

    @Test
    fun `update persists every field`() = runTest {
        val repo = DataStoreSettingsRepository(InMemoryPreferencesStore())
        val changed = AppSettings(
            onboardingDone = true,
            calendar = CalendarType.GREGORIAN,
            baseCurrency = "EUR",
            irrUnit = IrrUnit.RIAL,
            themeMode = ThemeMode.DARK,
            secureScreen = false,
            hiddenDashboardCards = setOf("goals", "insights"),
            lastBackupAtMillis = 1_700_000_000_000,
            autoLockMinutes = 5,
        )
        repo.update { changed }
        assertEquals(changed, repo.settings.first())

        repo.update { it.copy(lastBackupAtMillis = null) }
        assertEquals(null, repo.settings.first().lastBackupAtMillis)
    }

    @Test
    fun `unknown enum values fall back to defaults`() = runTest {
        val store = InMemoryPreferencesStore()
        store.updateData { p ->
            p.toMutablePreferences().apply { set(DataStoreSettingsRepository.THEME, "NEON") }
        }
        assertEquals(ThemeMode.SYSTEM, DataStoreSettingsRepository(store).settings.first().themeMode)
    }
}
