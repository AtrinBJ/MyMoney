package com.mymoney.app.feature.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.clickable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsContent(
        state = state,
        onBack = onBack,
        onLanguage = viewModel::setLanguage,
        onUpdate = viewModel::update,
        links = SettingsLinks(onOpenCategories, onOpenCurrencies, onOpenRates),
    )
}

/** Navigation entries shown in settings; empty lambdas in previews/tests. */
data class SettingsLinks(
    val categories: () -> Unit = {},
    val currencies: () -> Unit = {},
    val rates: () -> Unit = {},
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
private fun LinkRow(title: String, onClick: () -> Unit) {
    androidx.compose.material3.ListItem(
        headlineContent = { Text(title) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
