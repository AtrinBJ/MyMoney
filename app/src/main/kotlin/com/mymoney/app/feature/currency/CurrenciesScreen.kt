@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.mymoney.app.feature.currency

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mymoney.app.R
import com.mymoney.app.ui.common.BackTopBar
import com.mymoney.app.ui.common.DateField
import com.mymoney.app.ui.common.errorMessage
import com.mymoney.core.designsystem.format.LocalFormats
import com.mymoney.core.designsystem.theme.MyMoneyTheme
import com.mymoney.core.domain.common.DomainError
import com.mymoney.core.domain.common.IdGenerator
import com.mymoney.core.domain.common.Outcome
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.model.ExchangeRate
import com.mymoney.core.domain.model.RateSource
import com.mymoney.core.domain.money.Currency
import com.mymoney.core.domain.money.Digits
import com.mymoney.core.domain.repository.CurrencyRepository
import com.mymoney.core.domain.repository.ExchangeRateRepository
import com.mymoney.core.domain.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class CurrencyViewModel @Inject constructor(
    private val currencies: CurrencyRepository,
    private val rates: ExchangeRateRepository,
    private val settings: SettingsRepository,
    private val ids: IdGenerator,
    private val clock: TimeProvider,
) : ViewModel() {
    val today: LocalDate = clock.today()
    val all: StateFlow<List<Currency>> = currencies.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val rateList: StateFlow<List<ExchangeRate>> = rates.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val baseCurrency: StateFlow<String> = settings.settings.map { it.baseCurrency }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "IRR")
    private val _error = MutableStateFlow<DomainError?>(null)
    val error: StateFlow<DomainError?> = _error.asStateFlow()

    fun setBase(code: String) = viewModelScope.launch { settings.update { it.copy(baseCurrency = code) } }
    fun setEnabled(code: String, enabled: Boolean) = viewModelScope.launch { currencies.setEnabled(code, enabled) }

    fun addCustom(code: String, digits: Int, symbol: String, nameFa: String, nameEn: String, onDone: () -> Unit) {
        viewModelScope.launch {
            val c = runCatching { Currency(code.trim().uppercase(), digits, symbol.ifBlank { code }, nameFa.ifBlank { code }, nameEn.ifBlank { code }, isCustom = true) }
                .getOrElse { _error.value = DomainError.InvalidName("code"); return@launch }
            when (val r = currencies.addCustom(c)) {
                is Outcome.Success -> { _error.value = null; onDone() }
                is Outcome.Failure -> _error.value = r.error
            }
        }
    }

    /** Manual rate: 1 [base] = [value] [quote] on [date] (FBR-21). */
    fun addRate(base: String, quote: String, value: String, date: LocalDate, onDone: () -> Unit) {
        viewModelScope.launch {
            val rate = runCatching { BigDecimal(Digits.normalize(value).filterNot { it == ',' || it.isWhitespace() }) }.getOrNull()
            if (rate == null || rate.signum() <= 0) {
                _error.value = DomainError.InvalidRate("value")
                return@launch
            }
            when (val r = rates.save(ExchangeRate(ids.newId(), base, quote, rate, date, RateSource.MANUAL, createdAt = clock.nowMillis()))) {
                is Outcome.Success -> { _error.value = null; onDone() }
                is Outcome.Failure -> _error.value = r.error
            }
        }
    }

    fun deleteRate(id: String) = viewModelScope.launch { rates.delete(id) }
    fun clearError() { _error.value = null }
}

@Composable
fun CurrenciesScreen(onBack: () -> Unit, onOpenRates: () -> Unit, viewModel: CurrencyViewModel = hiltViewModel()) {
    val all by viewModel.all.collectAsStateWithLifecycle()
    val base by viewModel.baseCurrency.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    val formats = LocalFormats.current
    Scaffold(
        topBar = {
            BackTopBar(stringResource(R.string.cur_title), onBack) {
                TextButton(onClick = onOpenRates) { Text(stringResource(R.string.rate_title)) }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text(stringResource(R.string.cur_add_custom)) })
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { Text(stringResource(R.string.cur_base_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp)) }
            items(all, key = { it.code }) { c ->
                ListItem(
                    modifier = Modifier.clickable { viewModel.setBase(c.code) },
                    leadingContent = { RadioButton(selected = c.code == base, onClick = { viewModel.setBase(c.code) }) },
                    headlineContent = { Text("${c.code} · ${formats.currencyName(c.code)}") },
                    supportingContent = { Text(stringResource(R.string.cur_digits_value, c.minorDigits) + " · " + c.symbol) },
                    trailingContent = { Switch(checked = c.isEnabled, onCheckedChange = { v -> viewModel.setEnabled(c.code, v) }, enabled = c.code != base) },
                )
            }
        }
    }
    if (adding) AddCurrencyDialog(viewModel) { adding = false; viewModel.clearError() }
}

@Composable
private fun AddCurrencyDialog(viewModel: CurrencyViewModel, onDismiss: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    var digits by rememberSaveable { mutableStateOf(2) }
    var symbol by rememberSaveable { mutableStateOf("") }
    var fa by rememberSaveable { mutableStateOf("") }
    var en by rememberSaveable { mutableStateOf("") }
    val error by viewModel.error.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cur_add_custom)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(MyMoneyTheme.spacing.s)) {
                OutlinedTextField(code, { code = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(8) }, label = { Text(stringResource(R.string.cur_code)) }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(MyMoneyTheme.spacing.xs)) {
                    (0..4).forEach { d -> FilterChip(digits == d, { digits = d }, { Text(d.toString()) }) }
                }
                Text(stringResource(R.string.cur_digits), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(symbol, { symbol = it.take(5) }, label = { Text(stringResource(R.string.cur_symbol)) }, singleLine = true)
                OutlinedTextField(fa, { fa = it.take(40) }, label = { Text(stringResource(R.string.cur_name_fa)) }, singleLine = true)
                OutlinedTextField(en, { en = it.take(40) }, label = { Text(stringResource(R.string.cur_name_en)) }, singleLine = true)
                error?.let { Text(errorMessage(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { viewModel.addCustom(code, digits, symbol, fa, en, onDismiss) }, enabled = code.length >= 3) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun RatesScreen(onBack: () -> Unit, viewModel: CurrencyViewModel = hiltViewModel()) {
    val rates by viewModel.rateList.collectAsStateWithLifecycle()
    val base by viewModel.baseCurrency.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    val formats = LocalFormats.current
    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.rate_title), onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text(stringResource(R.string.rate_add)) })
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { Text(stringResource(R.string.rate_explain), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp)) }
            if (rates.isEmpty()) item { Text(stringResource(R.string.rate_empty), modifier = Modifier.padding(16.dp)) }
            items(rates, key = { it.id }) { r ->
                ListItem(
                    headlineContent = {
                        Text(
                            Digits.isolateLtr("1 ${r.base} = " + Digits.localize(r.rate.toPlainString(), formats.settings.digits) + " ${r.quote}"),
                        )
                    },
                    supportingContent = {
                        Text(
                            formats.dates.medium(r.date) + " · " + stringResource(
                                when (r.source) {
                                    RateSource.MANUAL -> R.string.rate_source_manual
                                    RateSource.TRANSFER_IMPLIED -> R.string.rate_source_implied
                                    RateSource.IMPORT -> R.string.rate_source_import
                                },
                            ),
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = { viewModel.deleteRate(r.id) }) { Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete)) }
                    },
                )
            }
        }
    }
    if (adding) AddRateDialog(viewModel, base) { adding = false; viewModel.clearError() }
}

@Composable
private fun AddRateDialog(viewModel: CurrencyViewModel, base: String, onDismiss: () -> Unit) {
    val all by viewModel.all.collectAsStateWithLifecycle()
    val enabled = all.filter { it.isEnabled }
    var from by rememberSaveable { mutableStateOf(enabled.firstOrNull { it.code != base }?.code ?: "USD") }
    var to by rememberSaveable { mutableStateOf(base) }
    var value by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(viewModel.today.toEpochDay()) }
    val error by viewModel.error.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rate_add)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(MyMoneyTheme.spacing.s)) {
                Text(stringResource(R.string.rate_from), style = MaterialTheme.typography.labelLarge)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    enabled.forEach { c -> FilterChip(from == c.code, { from = c.code }, { Text(c.code) }) }
                }
                Text(stringResource(R.string.rate_to), style = MaterialTheme.typography.labelLarge)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    enabled.forEach { c -> FilterChip(to == c.code, { to = c.code }, { Text(c.code) }) }
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.take(24) },
                    label = { Text(stringResource(R.string.rate_value, from, to)) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                )
                DateField(stringResource(R.string.rate_date), LocalDate.ofEpochDay(date), viewModel.today, { date = it.toEpochDay() }, Modifier.fillMaxWidth())
                Text(stringResource(R.string.rate_manual_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(errorMessage(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.addRate(from, to, value, LocalDate.ofEpochDay(date), onDismiss) }, enabled = from != to && value.isNotBlank()) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

