@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.mymoney.app.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mymoney.app.R
import com.mymoney.app.locale.LocaleController
import com.mymoney.app.ui.common.accountTypeLabel
import com.mymoney.app.ui.common.errorMessage
import com.mymoney.core.designsystem.component.AmountField
import com.mymoney.core.designsystem.format.LocalFormats
import com.mymoney.core.designsystem.theme.MyMoneyTheme
import com.mymoney.core.domain.common.DomainError
import com.mymoney.core.domain.common.Outcome
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.model.Account
import com.mymoney.core.domain.model.AccountType
import com.mymoney.core.domain.money.AmountParser
import com.mymoney.core.domain.money.Currency
import com.mymoney.core.domain.repository.CurrencyRepository
import com.mymoney.core.domain.settings.AppLanguage
import com.mymoney.core.domain.settings.AppSettings
import com.mymoney.core.domain.settings.CalendarType
import com.mymoney.core.domain.settings.DigitStyle
import com.mymoney.core.domain.settings.IrrUnit
import com.mymoney.core.domain.settings.SettingsRepository
import com.mymoney.core.domain.usecase.SaveAccountUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OnboardingState(
    val step: Int = 0,
    val currencies: List<Currency> = emptyList(),
    val accountName: String = "",
    val accountType: AccountType = AccountType.BANK,
    val opening: String = "",
    val error: DomainError? = null,
    val busy: Boolean = false,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val currencies: CurrencyRepository,
    private val localeController: LocaleController,
    private val saveAccount: SaveAccountUseCase,
    private val clock: TimeProvider,
) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()
    val settingsFlow = settings.settings

    init {
        viewModelScope.launch { _state.update { it.copy(currencies = currencies.getAll()) } }
    }

    fun language(): AppLanguage = localeController.current()

    fun setLanguage(lang: AppLanguage) {
        viewModelScope.launch {
            val d = AppSettings.defaultsFor(lang)
            settings.update { it.copy(calendar = d.calendar, digits = d.digits, weekStart = d.weekStart) }
            localeController.set(lang) // recreates the activity; the step survives in this ViewModel
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) = viewModelScope.launch { settings.update(transform) }
    fun setStep(step: Int) = _state.update { it.copy(step = step, error = null) }
    fun setAccountName(v: String) = _state.update { it.copy(accountName = v.take(60), error = null) }
    fun setAccountType(v: AccountType) = _state.update { it.copy(accountType = v) }
    fun setOpening(v: String) = _state.update { it.copy(opening = v, error = null) }

    fun createAccountAndContinue() {
        val s = _state.value
        if (s.busy) return
        viewModelScope.launch {
            val st = settings.settings.first()
            val digits = s.currencies.firstOrNull { it.code == st.baseCurrency }?.minorDigits ?: 2
            val opening = if (s.opening.isBlank()) 0L else when (val r = AmountParser.parse(s.opening, st.baseCurrency, digits, st.irrUnit, allowNegative = true)) {
                is Outcome.Success -> r.value
                is Outcome.Failure -> { _state.update { it.copy(error = r.error) }; return@launch }
            }
            _state.update { it.copy(busy = true) }
            val account = Account("", s.accountName, s.accountType, st.baseCurrency, startDate = clock.today())
            when (val r = saveAccount(account, opening, isNew = true)) {
                is Outcome.Success -> _state.update { it.copy(busy = false, step = 4) }
                is Outcome.Failure -> _state.update { it.copy(busy = false, error = r.error) }
            }
        }
    }

    fun finish() = viewModelScope.launch { settings.update { it.copy(onboardingDone = true) } }
}

private const val STEPS = 5

@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settingsFlow.collectAsStateWithLifecycle(AppSettings())
    val spacing = MyMoneyTheme.spacing
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(spacing.xl),
        verticalArrangement = Arrangement.spacedBy(spacing.l),
    ) {
        LinearProgressIndicator(progress = { (s.step + 1f) / STEPS }, modifier = Modifier.fillMaxWidth())
        when (s.step) {
            0 -> Welcome(viewModel)
            1 -> CurrencyStep(settings, s, viewModel)
            2 -> CalendarStep(settings, viewModel)
            3 -> FirstAccountStep(settings, s, viewModel)
            else -> DoneStep(viewModel)
        }
    }
}

@Composable
private fun StepTitle(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall)
    Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Welcome(vm: OnboardingViewModel) {
    StepTitle(stringResource(R.string.onb_welcome_title), stringResource(R.string.onb_welcome_body))
    Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.labelLarge)
    val current = vm.language()
    Row(horizontalArrangement = Arrangement.spacedBy(MyMoneyTheme.spacing.s)) {
        FilterChip(current == AppLanguage.FA, { vm.setLanguage(AppLanguage.FA) }, { Text("فارسی") })
        FilterChip(current == AppLanguage.EN, { vm.setLanguage(AppLanguage.EN) }, { Text("English") })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text("  " + stringResource(R.string.onb_privacy_note), style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(8.dp))
    Button(onClick = { vm.setStep(1) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_next)) }
}

@Composable
private fun CurrencyStep(settings: AppSettings, s: OnboardingState, vm: OnboardingViewModel) {
    val formats = LocalFormats.current
    StepTitle(stringResource(R.string.onb_currency_title), stringResource(R.string.onb_currency_body))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(MyMoneyTheme.spacing.s)) {
        s.currencies.filter { it.isEnabled }.take(9).forEach { c ->
            FilterChip(settings.baseCurrency == c.code, { vm.update { it.copy(baseCurrency = c.code) } }, { Text("${c.code} · ${formats.currencyName(c.code)}") })
        }
    }
    if (settings.baseCurrency == Currency.IRR) {
        Text(stringResource(R.string.settings_irr_unit), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(MyMoneyTheme.spacing.s)) {
            FilterChip(settings.irrUnit == IrrUnit.TOMAN, { vm.update { it.copy(irrUnit = IrrUnit.TOMAN) } }, { Text(stringResource(R.string.settings_irr_unit_toman)) })
            FilterChip(settings.irrUnit == IrrUnit.RIAL, { vm.update { it.copy(irrUnit = IrrUnit.RIAL) } }, { Text(stringResource(R.string.settings_irr_unit_rial)) })
        }
        Text(stringResource(R.string.onb_irr_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    NavRow(vm, back = 0, next = 2)
}

@Composable
private fun CalendarStep(settings: AppSettings, vm: OnboardingViewModel) {
    StepTitle(stringResource(R.string.onb_calendar_title), stringResource(R.string.onb_calendar_body))
    Row(horizontalArrangement = Arrangement.spacedBy(MyMoneyTheme.spacing.s)) {
        FilterChip(settings.calendar == CalendarType.JALALI, { vm.update { it.copy(calendar = CalendarType.JALALI) } }, { Text(stringResource(R.string.settings_calendar_jalali)) })
        FilterChip(settings.calendar == CalendarType.GREGORIAN, { vm.update { it.copy(calendar = CalendarType.GREGORIAN) } }, { Text(stringResource(R.string.settings_calendar_gregorian)) })
    }
    Text(stringResource(R.string.settings_digits), style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(MyMoneyTheme.spacing.s)) {
        FilterChip(settings.digits == DigitStyle.PERSIAN, { vm.update { it.copy(digits = DigitStyle.PERSIAN) } }, { Text(stringResource(R.string.settings_digits_persian)) })
        FilterChip(settings.digits == DigitStyle.LATIN, { vm.update { it.copy(digits = DigitStyle.LATIN) } }, { Text(stringResource(R.string.settings_digits_latin)) })
    }
    NavRow(vm, back = 1, next = 3)
}

@Composable
private fun FirstAccountStep(settings: AppSettings, s: OnboardingState, vm: OnboardingViewModel) {
    StepTitle(stringResource(R.string.onb_first_account_title), stringResource(R.string.onb_first_account_body))
    OutlinedTextField(s.accountName, vm::setAccountName, label = { Text(stringResource(R.string.acc_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    FlowRow(horizontalArrangement = Arrangement.spacedBy(MyMoneyTheme.spacing.s)) {
        AccountType.userSelectable.forEach { t -> FilterChip(s.accountType == t, { vm.setAccountType(t) }, { Text(stringResource(accountTypeLabel(t))) }) }
    }
    AmountField(s.opening, vm::setOpening, settings.baseCurrency, stringResource(R.string.acc_opening_balance), Modifier.fillMaxWidth(), supportingText = stringResource(R.string.acc_opening_hint))
    s.error?.let { Text(errorMessage(it), color = MaterialTheme.colorScheme.error) }
    Button(onClick = vm::createAccountAndContinue, enabled = s.accountName.isNotBlank() && !s.busy, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.action_next))
    }
    Row {
        TextButton(onClick = { vm.setStep(2) }) { Text(stringResource(R.string.action_back)) }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { vm.setStep(4) }) { Text(stringResource(R.string.onb_skip_account)) }
    }
}

@Composable
private fun DoneStep(vm: OnboardingViewModel) {
    StepTitle(stringResource(R.string.onb_done_title), stringResource(R.string.onb_done_body))
    Button(onClick = { vm.finish() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.onb_start)) }
}

@Composable
private fun NavRow(vm: OnboardingViewModel, back: Int, next: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { vm.setStep(back) }) { Text(stringResource(R.string.action_back)) }
        Spacer(Modifier.weight(1f))
        Button(onClick = { vm.setStep(next) }) { Text(stringResource(R.string.action_next)) }
    }
}
