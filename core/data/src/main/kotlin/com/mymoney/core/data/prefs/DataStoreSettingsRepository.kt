package com.mymoney.core.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.mymoney.core.domain.settings.AppSettings
import com.mymoney.core.domain.settings.CalendarType
import com.mymoney.core.domain.settings.DigitStyle
import com.mymoney.core.domain.settings.IrrUnit
import com.mymoney.core.domain.settings.SettingsRepository
import com.mymoney.core.domain.settings.ThemeMode
import com.mymoney.core.domain.settings.WeekStart
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Singleton
class DataStoreSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map(::read)
        .distinctUntilChanged()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs -> write(prefs, transform(read(prefs))) }
    }

    internal companion object Keys {
        val ONBOARDING_DONE = booleanPreferencesKey("onboardingDone")
        val CALENDAR = stringPreferencesKey("calendarSystem")
        val DIGITS = stringPreferencesKey("digitStyle")
        val WEEK_START = stringPreferencesKey("firstDayOfWeek")
        val BASE_CURRENCY = stringPreferencesKey("baseCurrency")
        val IRR_UNIT = stringPreferencesKey("irrDisplayUnit")
        val THEME = stringPreferencesKey("themeMode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamicColor")
        val GLASS = booleanPreferencesKey("glassEffects")
        val HIDE_AMOUNTS = booleanPreferencesKey("hideAmounts")
        val HIDE_ON_START = booleanPreferencesKey("hideAmountsOnStart")
        val SECURE_SCREEN = booleanPreferencesKey("secureScreen")
        val LOCK_ENABLED = booleanPreferencesKey("lockEnabled")
        val BIOMETRIC = booleanPreferencesKey("biometricEnabled")
        val AUTO_LOCK_MIN = intPreferencesKey("autoLockMinutes")
        val REMINDERS = booleanPreferencesKey("remindersEnabled")
        val REMINDER_HOUR = intPreferencesKey("reminderHour")
        val HIDDEN_CARDS = stringSetPreferencesKey("dashboardHiddenCards")
        val LAST_BACKUP = longPreferencesKey("lastBackupAt")
        val BACKUP_REMINDER_DAYS = intPreferencesKey("backupReminderDays")

        private inline fun <reified E : Enum<E>> Preferences.enum(key: Preferences.Key<String>, default: E): E =
            this[key]?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

        fun read(p: Preferences): AppSettings {
            val d = AppSettings()
            return AppSettings(
                onboardingDone = p[ONBOARDING_DONE] ?: d.onboardingDone,
                calendar = p.enum(CALENDAR, d.calendar),
                digits = p.enum(DIGITS, d.digits),
                weekStart = p.enum(WEEK_START, d.weekStart),
                baseCurrency = p[BASE_CURRENCY] ?: d.baseCurrency,
                irrUnit = p.enum(IRR_UNIT, d.irrUnit),
                themeMode = p.enum(THEME, d.themeMode),
                dynamicColor = p[DYNAMIC_COLOR] ?: d.dynamicColor,
                glassEffects = p[GLASS] ?: d.glassEffects,
                hideAmounts = p[HIDE_AMOUNTS] ?: d.hideAmounts,
                hideAmountsOnStart = p[HIDE_ON_START] ?: d.hideAmountsOnStart,
                secureScreen = p[SECURE_SCREEN] ?: d.secureScreen,
                lockEnabled = p[LOCK_ENABLED] ?: d.lockEnabled,
                biometricEnabled = p[BIOMETRIC] ?: d.biometricEnabled,
                autoLockMinutes = p[AUTO_LOCK_MIN] ?: d.autoLockMinutes,
                remindersEnabled = p[REMINDERS] ?: d.remindersEnabled,
                reminderHour = p[REMINDER_HOUR] ?: d.reminderHour,
                hiddenDashboardCards = p[HIDDEN_CARDS] ?: d.hiddenDashboardCards,
                lastBackupAtMillis = p[LAST_BACKUP],
                backupReminderDays = p[BACKUP_REMINDER_DAYS] ?: d.backupReminderDays,
            )
        }

        fun write(p: MutablePreferences, s: AppSettings) {
            p[ONBOARDING_DONE] = s.onboardingDone
            p[CALENDAR] = s.calendar.name
            p[DIGITS] = s.digits.name
            p[WEEK_START] = s.weekStart.name
            p[BASE_CURRENCY] = s.baseCurrency
            p[IRR_UNIT] = s.irrUnit.name
            p[THEME] = s.themeMode.name
            p[DYNAMIC_COLOR] = s.dynamicColor
            p[GLASS] = s.glassEffects
            p[HIDE_AMOUNTS] = s.hideAmounts
            p[HIDE_ON_START] = s.hideAmountsOnStart
            p[SECURE_SCREEN] = s.secureScreen
            p[LOCK_ENABLED] = s.lockEnabled
            p[BIOMETRIC] = s.biometricEnabled
            p[AUTO_LOCK_MIN] = s.autoLockMinutes
            p[REMINDERS] = s.remindersEnabled
            p[REMINDER_HOUR] = s.reminderHour
            p[HIDDEN_CARDS] = s.hiddenDashboardCards
            val lastBackup = s.lastBackupAtMillis
            if (lastBackup != null) p[LAST_BACKUP] = lastBackup else p.remove(LAST_BACKUP)
            p[BACKUP_REMINDER_DAYS] = s.backupReminderDays
        }
    }
}
