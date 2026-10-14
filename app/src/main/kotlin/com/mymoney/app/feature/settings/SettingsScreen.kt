package com.mymoney.app.feature.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.clickable
import androidx.compose.material3.ExperimentalMaterial3Api
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.mymoney.app.lock.PinSetupMode
import com.mymoney.app.reminders.AndroidNotifier
import com.mymoney.app.reminders.DailyMaintenanceWorker
import com.mymoney.core.domain.money.Digits
import java.util.Locale
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoney.app.R
import com.mymoney.core.designsystem.component.ChoiceRow
import com.mymoney.core.designsystem.component.SectionHeader
import com.mymoney.core.designsystem.component.SwitchRow
import com.mymoney.core.domain.settings.AppLanguage
import com.mymoney.core.domain.settings.AppSettings
import com.mymoney.core.domain.settings.CalendarType
import com.mymoney.core.domain.settings.DigitStyle
import com.mymoney.core.domain.settings.IrrUnit
import com.mymoney.core.domain.settings.ThemeMode
import com.mymoney.core.domain.settings.WeekStart

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenCurrencies: () -> Unit,
    onOpenRates: () -> Unit,
    onOpenDataHealth: () -> Unit,
    onPin: (PinSetupMode) -> Unit,
    onData: (Any) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsContent(
        state = state,
        onBack = onBack,
        onLanguage = viewModel::setLanguage,
        onUpdate = viewModel::update,
        links = SettingsLinks(onOpenCategories, onOpenCurrencies, onOpenRates, onOpenDataHealth, onPin, onData, viewModel::runDebug),
    )
}

/** Navigation entries shown in settings; empty lambdas in previews/tests. */
data class SettingsLinks(
    val categories: () -> Unit = {},
    val currencies: () -> Unit = {},
    val rates: () -> Unit = {},
    val dataHealth: () -> Unit = {},
    val pin: (PinSetupMode) -> Unit = {},
    /** Opens a data screen; the argument is its navigation route. */
    val data: (Any) -> Unit = {},
    val debug: (com.mymoney.app.DebugAction) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsContent(
    state: SettingsUiState,
    onBack: () -> Unit,
    onLanguage: (AppLanguage) -> Unit,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    links: SettingsLinks = SettingsLinks(),
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        val s = state.settings ?: return@Scaffold
        val cancel = stringResource(R.string.action_cancel)
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(stringResource(R.string.settings_section_general))
            ChoiceRow(
                title = stringResource(R.string.settings_language),
                options = AppLanguage.entries,
                selected = state.language,
                label = {
                    when (it) {
                        AppLanguage.FA -> stringResource(R.string.settings_language_fa)
                        AppLanguage.EN -> stringResource(R.string.settings_language_en)
                    }
                },
                onSelect = onLanguage,
                cancelLabel = cancel,
            )
            ChoiceRow(
                title = stringResource(R.string.settings_calendar),
                options = CalendarType.entries,
                selected = s.calendar,
                label = {
                    when (it) {
                        CalendarType.JALALI -> stringResource(R.string.settings_calendar_jalali)
                        CalendarType.GREGORIAN -> stringResource(R.string.settings_calendar_gregorian)
                    }
                },
                onSelect = { v -> onUpdate { it.copy(calendar = v) } },
                cancelLabel = cancel,
            )
            ChoiceRow(
                title = stringResource(R.string.settings_digits),
                options = DigitStyle.entries,
                selected = s.digits,
                label = {
                    when (it) {
                        DigitStyle.PERSIAN -> stringResource(R.string.settings_digits_persian)
                        DigitStyle.LATIN -> stringResource(R.string.settings_digits_latin)
                    }
                },
                onSelect = { v -> onUpdate { it.copy(digits = v) } },
                cancelLabel = cancel,
            )
            ChoiceRow(
                title = stringResource(R.string.settings_week_start),
                options = WeekStart.entries,
                selected = s.weekStart,
                label = {
                    when (it) {
                        WeekStart.SATURDAY -> stringResource(R.string.settings_week_saturday)
                        WeekStart.SUNDAY -> stringResource(R.string.settings_week_sunday)
                        WeekStart.MONDAY -> stringResource(R.string.settings_week_monday)
                    }
                },
                onSelect = { v -> onUpdate { it.copy(weekStart = v) } },
                cancelLabel = cancel,
            )
            ChoiceRow(
                title = stringResource(R.string.settings_theme),
                options = ThemeMode.entries,
                selected = s.themeMode,
                label = {
                    when (it) {
                        ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
                        ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
                        ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
                    }
                },
                onSelect = { v -> onUpdate { it.copy(themeMode = v) } },
                cancelLabel = cancel,
            )
            SwitchRow(
                title = stringResource(R.string.settings_dynamic_color),
                description = stringResource(R.string.settings_dynamic_color_desc),
                checked = s.dynamicColor,
                onCheckedChange = { v -> onUpdate { it.copy(dynamicColor = v) } },
                enabled = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S,
            )

            SectionHeader(stringResource(R.string.settings_section_data))
            LinkRow(stringResource(R.string.cat_title), links.categories)
            LinkRow(stringResource(R.string.cur_title), links.currencies)
            LinkRow(stringResource(R.string.rate_title), links.rates)
            LinkRow(stringResource(R.string.settings_data_health), links.dataHealth, stringResource(R.string.settings_data_health_d))
            LinkRow(stringResource(R.string.settings_backup), { links.data(com.mymoney.app.navigation.BackupRoute) }, stringResource(R.string.settings_backup_desc))
            LinkRow(stringResource(R.string.settings_export), { links.data(com.mymoney.app.navigation.ExportRoute) }, stringResource(R.string.settings_export_desc))
            LinkRow(stringResource(R.string.settings_import), { links.data(com.mymoney.app.navigation.ImportRoute) }, stringResource(R.string.settings_import_desc))
            LinkRow(stringResource(R.string.settings_trash), { links.data(com.mymoney.app.navigation.TrashRoute) })
            LinkRow(stringResource(R.string.settings_wipe), { links.data(com.mymoney.app.navigation.DeleteAllRoute) })

            SectionHeader(stringResource(R.string.settings_section_notifications))
            NotificationSettings(s, onUpdate, cancel)

            SectionHeader(stringResource(R.string.settings_section_currency))
            ChoiceRow(
                title = stringResource(R.string.settings_irr_unit),
                options = IrrUnit.entries,
                selected = s.irrUnit,
                label = {
                    when (it) {
                        IrrUnit.TOMAN -> stringResource(R.string.settings_irr_unit_toman)
                        IrrUnit.RIAL -> stringResource(R.string.settings_irr_unit_rial)
                    }
                },
                onSelect = { v -> onUpdate { it.copy(irrUnit = v) } },
                cancelLabel = cancel,
            )

            SectionHeader(stringResource(R.string.settings_section_privacy))
            SecuritySettings(state, s, onUpdate, links, cancel)

            SectionHeader(stringResource(R.string.about_title))
            LinkRow(stringResource(R.string.settings_about), { links.data(com.mymoney.app.navigation.AboutRoute) })

            if (state.debugActions.isNotEmpty()) {
                // Debug builds only; not translated on purpose.
                SectionHeader("Developer (debug build)")
                state.debugActions.forEach { a -> LinkRow(a.label, { links.debug(a) }) }
                state.debugMessage?.let { Text(it, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp)) }
            }
            SwitchRow(
                title = stringResource(R.string.settings_secure_screen),
                description = stringResource(R.string.settings_secure_screen_desc),
                checked = s.secureScreen,
                onCheckedChange = { v -> onUpdate { it.copy(secureScreen = v) } },
            )
            SwitchRow(
                title = stringResource(R.string.settings_hide_on_start),
                checked = s.hideAmountsOnStart,
                onCheckedChange = { v -> onUpdate { it.copy(hideAmountsOnStart = v) } },
            )
        }
    }
}

@Composable
private fun LinkRow(title: String, onClick: () -> Unit, description: String? = null) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = description?.let { { Text(it) } },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * Reminders switch. On Android 13+ the notification permission is requested only here, when the user turns
 * reminders on (docs/SECURITY.md §Permissions). If it is denied, reminders stay off.
 */
@Composable
private fun NotificationSettings(s: AppSettings, onUpdate: ((AppSettings) -> AppSettings) -> Unit, cancel: String) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onUpdate { it.copy(remindersEnabled = true) }
    }
    val blocked = s.remindersEnabled && !AndroidNotifier.canNotify(context)
    SwitchRow(
        title = stringResource(R.string.settings_reminders),
        description = stringResource(R.string.settings_reminders_desc),
        checked = s.remindersEnabled,
        onCheckedChange = { on ->
            val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            when {
                !on -> onUpdate { it.copy(remindersEnabled = false) }
                needsPermission -> launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else -> onUpdate { it.copy(remindersEnabled = true) }
            }
        },
    )
    if (blocked) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_notif_denied)) },
            trailingContent = {
                TextButton(onClick = {
                    (context.applicationContext as? com.mymoney.app.MyMoneyApp)?.lockManager?.allowNextBackground()
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text(stringResource(R.string.settings_open_system)) }
            },
        )
    }
    if (s.remindersEnabled) {
        ChoiceRow(
            title = stringResource(R.string.settings_reminder_hour),
            options = (6..22).toList(),
            selected = s.reminderHour,
            label = { h -> Digits.localize(String.format(Locale.ROOT, "%02d:00", h), s.digits) },
            onSelect = { h ->
                onUpdate { it.copy(reminderHour = h) }
                DailyMaintenanceWorker.schedule(context, h, hourChanged = true)
            },
            cancelLabel = cancel,
        )
    }
}

/** App lock settings (docs/SECURITY.md §App lock). Turning the lock off or changing the PIN asks for the PIN. */
@Composable
private fun SecuritySettings(state: SettingsUiState, s: AppSettings, onUpdate: ((AppSettings) -> AppSettings) -> Unit, links: SettingsLinks, cancel: String) {
    SwitchRow(
        title = stringResource(R.string.settings_app_lock),
        description = stringResource(R.string.settings_app_lock_desc),
        checked = state.pinSet,
        onCheckedChange = { on -> links.pin(if (on) PinSetupMode.SET else PinSetupMode.DISABLE) },
    )
    if (!state.pinSet) return
    LinkRow(stringResource(R.string.pin_change_title), { links.pin(PinSetupMode.CHANGE) })
    SwitchRow(
        title = stringResource(R.string.settings_biometric),
        description = stringResource(if (state.biometricAvailable) R.string.settings_biometric_desc else R.string.settings_biometric_unavailable),
        checked = s.biometricEnabled && state.biometricAvailable,
        enabled = state.biometricAvailable,
        onCheckedChange = { v -> onUpdate { it.copy(biometricEnabled = v) } },
    )
    ChoiceRow(
        title = stringResource(R.string.settings_auto_lock),
        options = listOf(0, 1, 5, 15, -1),
        selected = s.autoLockMinutes,
        label = { m ->
            when (m) {
                0 -> stringResource(R.string.settings_auto_lock_now)
                -1 -> stringResource(R.string.settings_auto_lock_restart)
                else -> stringResource(R.string.settings_auto_lock_minutes, Digits.localize(m.toString(), s.digits))
            }
        },
        onSelect = { v -> onUpdate { it.copy(autoLockMinutes = v) } },
        cancelLabel = cancel,
    )
}
