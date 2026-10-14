package com.mymoney.app.feature.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.mymoney.app.R
import com.mymoney.app.navigation.AccountEditorRoute
import com.mymoney.app.ui.common.BackTopBar
import com.mymoney.app.ui.common.SaveAction
import com.mymoney.app.ui.common.DateField
import com.mymoney.app.ui.common.PickerField
import com.mymoney.app.ui.common.accountTypeLabel
import com.mymoney.app.ui.common.errorMessage
import com.mymoney.core.designsystem.component.AmountField
import com.mymoney.core.designsystem.component.ColorKeys
import com.mymoney.core.designsystem.component.IconBadge
import com.mymoney.core.designsystem.component.IconKeys
import com.mymoney.core.designsystem.component.LoadingSkeleton
import com.mymoney.core.designsystem.format.LocalFormats
import com.mymoney.core.designsystem.theme.MyMoneyTheme
import com.mymoney.core.domain.common.DomainError
import com.mymoney.core.domain.common.Outcome
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.model.Account
import com.mymoney.core.domain.model.AccountType
import com.mymoney.core.domain.money.AmountParser
import com.mymoney.core.domain.money.Currency
import com.mymoney.core.domain.repository.AccountRepository
import com.mymoney.core.domain.repository.CurrencyRepository
import com.mymoney.core.domain.settings.IrrUnit
import com.mymoney.core.domain.settings.SettingsRepository
import com.mymoney.core.domain.usecase.SaveAccountUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AccountEditorState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val id: String = "",
    val name: String = "",
    val type: AccountType = AccountType.BANK,
    val currency: String = "IRR",
    val institution: String = "",
    val opening: String = "",
    val startDate: LocalDate = LocalDate.now(),
    val note: String = "",
    val iconKey: String? = null,
    val colorKey: String? = "c0",
    val includeInNetWorth: Boolean = true,
    val includeInLiquid: Boolean = true,
    val creditLimit: String = "",
    val currencyLocked: Boolean = false,
    val currencies: List<Currency> = emptyList(),
    val irrUnit: IrrUnit = IrrUnit.TOMAN,
    val today: LocalDate = LocalDate.now(),
    val error: DomainError? = null,
    val saving: Boolean = false,
    val savedId: String? = null,
    val original: Account? = null,
)

@HiltViewModel
class AccountEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val accounts: AccountRepository,
    private val currencies: CurrencyRepository,
    private val settings: SettingsRepository,
    private val saveAccount: SaveAccountUseCase,
    private val clock: TimeProvider,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<AccountEditorRoute>()
    private val _state = MutableStateFlow(AccountEditorState())
    val state: StateFlow<AccountEditorState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val s = settings.settings.first()
            val curr = currencies.getAll()
            val today = clock.today()
            val existing = route.accountId?.let { accounts.get(it) }
            _state.value = if (existing == null) {
                AccountEditorState(
                    loading = false, currency = s.baseCurrency, startDate = today, today = today,
                    currencies = curr, irrUnit = s.irrUnit,
                )
            } else {
                val opening = accounts.openingBalance(existing.id)
                AccountEditorState(
                    loading = false, isNew = false, id = existing.id, name = existing.name, type = existing.type,
                    currency = existing.currency, institution = existing.institution.orEmpty(),
                    opening = display(opening, existing.currency, curr, s.irrUnit), startDate = existing.startDate,
                    note = existing.note.orEmpty(), iconKey = existing.iconKey, colorKey = existing.colorKey,
                    includeInNetWorth = existing.includeInNetWorth, includeInLiquid = existing.includeInLiquid,
                    creditLimit = existing.creditLimitMinor?.let { display(it, existing.currency, curr, s.irrUnit) }.orEmpty(),
                    currencyLocked = accounts.entryCount(existing.id) > (if (opening != 0L) 1 else 0),
                    currencies = curr, irrUnit = s.irrUnit, today = today, original = existing,
                )
            }
        }
    }

    fun update(transform: (AccountEditorState) -> AccountEditorState) = _state.update { transform(it).copy(error = null) }

    fun setType(type: AccountType) = _state.update { it.copy(type = type, includeInLiquid = type.defaultLiquid, error = null) }

    fun save() {
        val s = _state.value
        if (s.saving) return
        val digits = s.currencies.firstOrNull { it.code == s.currency }?.minorDigits ?: 2
        val opening = if (s.opening.isBlank()) 0L else when (val r = AmountParser.parse(s.opening, s.currency, digits, s.irrUnit, allowNegative = true)) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> { _state.update { it.copy(error = r.error) }; return }
        }
        val limit = if (s.type != AccountType.CREDIT_CARD || s.creditLimit.isBlank()) null else when (val r = AmountParser.parse(s.creditLimit, s.currency, digits, s.irrUnit)) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> { _state.update { it.copy(error = r.error) }; return }
        }
        val account = (s.original ?: Account("", "", s.type, s.currency, startDate = s.startDate)).copy(
            name = s.name, type = s.type, currency = s.currency, institution = s.institution.trim().ifEmpty { null },
            startDate = s.startDate, note = s.note.trim().ifEmpty { null }, iconKey = s.iconKey, colorKey = s.colorKey,
            includeInNetWorth = s.includeInNetWorth, includeInLiquid = s.includeInLiquid, creditLimitMinor = limit,
        )
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            when (val r = saveAccount(account, opening, s.isNew)) {
                is Outcome.Success -> _state.update { it.copy(saving = false, savedId = r.value) }
                is Outcome.Failure -> _state.update { it.copy(saving = false, error = r.error) }
            }
        }
    }

    private fun display(minor: Long, currency: String, curr: List<Currency>, unit: IrrUnit): String {
        val digits = curr.firstOrNull { it.code == currency }?.minorDigits ?: 2
        var v = BigDecimal.valueOf(minor, digits)
        if (currency == Currency.IRR && unit == IrrUnit.TOMAN) v = v.movePointLeft(1)
        return if (minor == 0L) "" else v.stripTrailingZeros().toPlainString()
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AccountEditorScreen(onDone: (savedId: String?) -> Unit, viewModel: AccountEditorViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.savedId) { s.savedId?.let { onDone(it) } }
    var pickCurrency by rememberSaveable { mutableStateOf(false) }
    val spacing = MyMoneyTheme.spacing
    val formats = LocalFormats.current

    Scaffold(topBar = { BackTopBar(stringResource(if (s.isNew) R.string.acc_new else R.string.acc_edit), { onDone(null) }) {
        if (!s.loading) SaveAction(!s.saving && s.name.isNotBlank(), viewModel::save)
    } }) { padding ->
        if (s.loading) {
            LoadingSkeleton(Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(spacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(spacing.m),
        ) {
            OutlinedTextField(s.name, { v -> viewModel.update { it.copy(name = v.take(60)) } }, label = { Text(stringResource(R.string.acc_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.acc_type), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                AccountType.userSelectable.forEach { t ->
                    FilterChip(selected = s.type == t, onClick = { viewModel.setType(t) }, label = { Text(stringResource(accountTypeLabel(t))) })
                }
            }
            PickerField(
                label = stringResource(R.string.acc_currency),
                value = "${s.currency} · ${formats.currencyName(s.currency)}",
                onClick = { if (!s.currencyLocked) pickCurrency = true },
                icon = { Icon(Icons.Outlined.ExpandMore, null) },
            )
            if (s.currencyLocked) Text(stringResource(R.string.acc_currency_locked), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(s.institution, { v -> viewModel.update { it.copy(institution = v.take(60)) } }, label = { Text(stringResource(R.string.acc_institution)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            AmountField(s.opening, { v -> viewModel.update { it.copy(opening = v) } }, s.currency, stringResource(R.string.acc_opening_balance), Modifier.fillMaxWidth(), supportingText = stringResource(R.string.acc_opening_hint))
            DateField(stringResource(R.string.acc_start_date), s.startDate, s.today, { d -> viewModel.update { it.copy(startDate = d) } }, Modifier.fillMaxWidth())
            if (s.type == AccountType.CREDIT_CARD) {
                AmountField(s.creditLimit, { v -> viewModel.update { it.copy(creditLimit = v) } }, s.currency, stringResource(R.string.acc_credit_limit), Modifier.fillMaxWidth())
            }
            Text(stringResource(R.string.acc_icon), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.s), verticalArrangement = Arrangement.spacedBy(spacing.s)) {
                ColorKeys.all.forEach { key ->
                    val selected = s.colorKey == key
                    IconBadge(
                        icon = s.iconKey?.let(IconKeys::icon) ?: IconKeys.forAccountType(s.type),
                        tint = ColorKeys.color(key),
                        modifier = Modifier.clickable { viewModel.update { it.copy(colorKey = key) } },
                        size = if (selected) 48.dp else 40.dp,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.acc_include_net_worth), Modifier.weight(1f))
                Switch(s.includeInNetWorth, { v -> viewModel.update { it.copy(includeInNetWorth = v) } })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.acc_include_liquid))
                    Text(stringResource(R.string.acc_include_liquid_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(s.includeInLiquid, { v -> viewModel.update { it.copy(includeInLiquid = v) } })
            }
            OutlinedTextField(s.note, { v -> viewModel.update { it.copy(note = v) } }, label = { Text(stringResource(R.string.editor_note)) }, modifier = Modifier.fillMaxWidth())
            s.error?.let { Text(errorMessage(it), color = MaterialTheme.colorScheme.error) }
            Button(onClick = viewModel::save, enabled = !s.saving && s.name.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_save))
            }
        }
    }

    if (pickCurrency) {
        ModalBottomSheet(onDismissRequest = { pickCurrency = false }) {
            LazyColumn {
                items(s.currencies.filter { it.isEnabled }, key = { it.code }) { c ->
                    ListItem(
                        modifier = Modifier.clickable { viewModel.update { it.copy(currency = c.code) }; pickCurrency = false },
                        headlineContent = { Text("${c.code} · ${formats.currencyName(c.code)}") },
                        trailingContent = { Text(c.symbol) },
                    )
                }
            }
        }
    }
}

