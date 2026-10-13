package com.mymoney.core.domain.settings

/** UI language. Stored per-app via the platform locale manager; mirrored here for formatting defaults. */
enum class AppLanguage(val tag: String) {
    FA("fa"),
    EN("en");

    companion object {
        fun fromTag(tag: String?): AppLanguage? = entries.firstOrNull { tag != null && tag.startsWith(it.tag) }
    }
}

enum class CalendarType { JALALI, GREGORIAN }

enum class DigitStyle { PERSIAN, LATIN }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Display/input unit for IRR amounts. Storage is always rial (FBR-22). */
enum class IrrUnit { RIAL, TOMAN }

/** First day of a week, used for weekly budgets and calendar grids. */
enum class WeekStart(val isoDayOfWeek: Int) {
    SATURDAY(6),
    SUNDAY(7),
    MONDAY(1),
}

/**
 * User preferences that are not financial records. Persisted in DataStore and included in backups.
 * Defaults follow docs/03-data-model.md §3.8.
 */
data class AppSettings(
    val onboardingDone: Boolean = false,
    val calendar: CalendarType = CalendarType.JALALI,
    val digits: DigitStyle = DigitStyle.PERSIAN,
    val weekStart: WeekStart = WeekStart.SATURDAY,
    val baseCurrency: String = "IRR",
    val irrUnit: IrrUnit = IrrUnit.TOMAN,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val hideAmounts: Boolean = false,
    val hideAmountsOnStart: Boolean = false,
    val secureScreen: Boolean = true,
    val lockEnabled: Boolean = false,
    val biometricEnabled: Boolean = false,
    val autoLockMinutes: Int = 1,
    val remindersEnabled: Boolean = false,
    val reminderHour: Int = 9,
    val hiddenDashboardCards: Set<String> = emptySet(),
    val lastBackupAtMillis: Long? = null,
    val backupReminderDays: Int = 30,
) {
    companion object {
        /** Locale-appropriate defaults applied when the user picks a language during onboarding. */
        fun defaultsFor(language: AppLanguage): AppSettings = when (language) {
            AppLanguage.FA -> AppSettings()
            AppLanguage.EN -> AppSettings(
                calendar = CalendarType.GREGORIAN,
                digits = DigitStyle.LATIN,
                weekStart = WeekStart.MONDAY,
            )
        }
    }
}
