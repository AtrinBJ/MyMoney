@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.mymoney.app.feature.wealth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.mymoney.app.R
import com.mymoney.app.data.FinanceSnapshot
import com.mymoney.app.data.FinanceSnapshotProvider
import com.mymoney.app.navigation.AssetDetailRoute
import com.mymoney.app.navigation.AssetEditorRoute
import com.mymoney.app.ui.common.BackTopBar
import com.mymoney.app.ui.common.ConfirmDialog
import com.mymoney.app.ui.common.DateField
import com.mymoney.app.ui.common.errorMessage
import com.mymoney.app.ui.common.parseAmount
import com.mymoney.app.ui.common.parseAmountOrNull
import com.mymoney.core.designsystem.component.AmountColor
import com.mymoney.core.designsystem.component.AmountField
import com.mymoney.core.designsystem.component.InfoBanner
import com.mymoney.core.designsystem.component.KpiValue
import com.mymoney.core.designsystem.component.LoadingSkeleton
import com.mymoney.core.designsystem.component.MoneyText
import com.mymoney.core.designsystem.component.SectionCard
import com.mymoney.core.designsystem.format.Formats
import com.mymoney.core.designsystem.format.LocalFormats
import com.mymoney.core.designsystem.theme.MyMoneyTheme
import com.mymoney.core.domain.calc.AssetHolding
import com.mymoney.core.domain.common.DomainError
import com.mymoney.core.domain.common.IdGenerator
import com.mymoney.core.domain.common.Outcome
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.model.Account
import com.mymoney.core.domain.model.Asset
import com.mymoney.core.domain.model.AssetTrade
import com.mymoney.core.domain.model.AssetType
import com.mymoney.core.domain.model.AssetValuation
import com.mymoney.core.domain.model.TradeType
import com.mymoney.core.domain.model.ValuationMode
import com.mymoney.core.domain.model.ValuationSource
import com.mymoney.core.domain.money.Digits
import com.mymoney.core.domain.repository.AssetRepository
import com.mymoney.core.domain.usecase.AssetOperationsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@androidx.annotation.StringRes
fun assetTypeLabel(t: AssetType): Int = when (t) {
    AssetType.GOLD -> R.string.asset_type_gold
    AssetType.STOCK -> R.string.asset_type_stock
    AssetType.FUND -> R.string.asset_type_fund
    AssetType.CRYPTO -> R.string.asset_type_crypto
    AssetType.VEHICLE -> R.string.asset_type_vehicle
    AssetType.REAL_ESTATE -> R.string.asset_type_real_estate
    AssetType.PERSONAL -> R.string.asset_type_personal
    AssetType.OTHER -> R.string.asset_type_other
}

/** Quantity typed with Persian or Latin digits → positive decimal, or null. */
internal fun parseQuantity(text: String): BigDecimal? =
    Digits.normalize(text).filterNot { it == ',' || it.isWhitespace() }.toBigDecimalOrNull()?.takeIf { it.signum() > 0 && it.scale() <= 18 }

// ---------------- Editor ----------------

data class AssetEditorState(
    val loading: Boolean = true,
    val original: Asset? = null,
    val name: String = "",
    val type: AssetType = AssetType.GOLD,
    val unit: String = "",
    val currency: String = "IRR",
    val mode: ValuationMode = ValuationMode.PER_UNIT,
    val quantity: String = "",
    val cost: String = "",
    val value: String = "",
    val includeInNetWorth: Boolean = true,
    val date: LocalDate = LocalDate.now(),
    val currencies: List<String> = emptyList(),
    val error: DomainError? = null,
    val done: Boolean = false,
)

@HiltViewModel
class AssetEditorViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val assets: AssetRepository,
    private val ops: AssetOperationsUseCase,
    provider: FinanceSnapshotProvider,
    clock: TimeProvider,
) : ViewModel() {
    private val id = handle.toRoute<AssetEditorRoute>().assetId
    private val _s = MutableStateFlow(AssetEditorState())
    val state: StateFlow<AssetEditorState> = _s

    init {
        viewModelScope.launch {
            val snap = provider.snapshot.first()
            val a = id?.let { assets.get(it) }
            val curr = snap.currencies.values.filter { it.isEnabled }.map { it.code }
            _s.value = if (a == null) {
                AssetEditorState(loading = false, currency = snap.base, currencies = curr, date = clock.today())
            } else {
                AssetEditorState(false, a, a.name, a.type, a.unit, a.currency, a.valuationMode, includeInNetWorth = a.includeInNetWorth, currencies = curr, date = clock.today())
            }
        }
    }

    fun update(f: (AssetEditorState) -> AssetEditorState) { _s.value = f(_s.value).copy(error = null) }

    fun save(formats: Formats) {
        val s = _s.value
        viewModelScope.launch {
            if (s.original != null) {
                when (val r = assets.saveAsset(s.original.copy(name = s.name, unit = s.unit, includeInNetWorth = s.includeInNetWorth))) {
                    is Outcome.Success -> _s.value = s.copy(done = true)
                    is Outcome.Failure -> _s.value = s.copy(error = r.error)
                }
                return@launch
            }
            val qty = if (s.quantity.isBlank()) null else parseQuantity(s.quantity) ?: run { _s.value = s.copy(error = DomainError.Required("quantity")); return@launch }
            val cost = if (s.cost.isBlank()) null else formats.parseAmountOrNull(s.cost, s.currency) ?: run { _s.value = s.copy(error = DomainError.Required("cost")); return@launch }
            val value = if (s.value.isBlank()) null else formats.parseAmountOrNull(s.value, s.currency) ?: run { _s.value = s.copy(error = DomainError.Required("value")); return@launch }
            // Per-unit assets store the typed value as a unit price; total-mode assets as a total.
            val unitPrice = if (s.mode == ValuationMode.PER_UNIT && value != null) BigDecimal.valueOf(value, formats.minorDigits(s.currency)) else null
            val total = if (s.mode == ValuationMode.TOTAL) value else null
            val asset = Asset("", s.name, s.type, s.unit.ifBlank { "-" }, s.currency, s.mode, s.includeInNetWorth)
            when (val r = ops.create(asset, qty, cost, total, unitPrice, s.date)) {
                is Outcome.Success -> _s.value = s.copy(done = true)
                is Outcome.Failure -> _s.value = s.copy(error = r.error)
            }
        }
    }
}

@Composable
fun AssetEditorScreen(onDone: () -> Unit, viewModel: AssetEditorViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val formats = LocalFormats.current
    LaunchedEffect(s.done) { if (s.done) onDone() }
    val spacing = MyMoneyTheme.spacing
    Scaffold(topBar = { BackTopBar(stringResource(if (s.original == null) R.string.asset_new else R.string.asset_edit), onDone) }) { padding ->
        if (s.loading) { LoadingSkeleton(Modifier.padding(padding)); return@Scaffold }
        val isNew = s.original == null
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(spacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(spacing.m),
        ) {
            OutlinedTextField(s.name, { v -> viewModel.update { it.copy(name = v.take(60)) } }, label = { Text(stringResource(R.string.cat_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (isNew) {
                Text(stringResource(R.string.acc_type), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                    AssetType.entries.forEach { t ->
                        FilterChip(s.type == t, {
                            viewModel.update {
                                it.copy(type = t, mode = if (t == AssetType.REAL_ESTATE || t == AssetType.VEHICLE || t == AssetType.PERSONAL) ValuationMode.TOTAL else ValuationMode.PER_UNIT)
                            }
                        }, { Text(stringResource(assetTypeLabel(t))) })
                    }
                }
                Text(stringResource(R.string.asset_cash_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(s.unit, { v -> viewModel.update { it.copy(unit = v.take(20)) } }, label = { Text(stringResource(R.string.asset_unit)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (isNew) {
                Text(stringResource(R.string.acc_currency), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    s.currencies.forEach { c -> FilterChip(s.currency == c, { viewModel.update { it.copy(currency = c) } }, { Text(c) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                    FilterChip(s.mode == ValuationMode.PER_UNIT, { viewModel.update { it.copy(mode = ValuationMode.PER_UNIT) } }, { Text(stringResource(R.string.asset_mode_unit)) })
                    FilterChip(s.mode == ValuationMode.TOTAL, { viewModel.update { it.copy(mode = ValuationMode.TOTAL) } }, { Text(stringResource(R.string.asset_mode_total)) })
                }
                OutlinedTextField(
                    s.quantity, { v -> viewModel.update { it.copy(quantity = v.take(30)) } }, label = { Text(stringResource(R.string.asset_quantity_owned)) }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                )
                AmountField(s.cost, { v -> viewModel.update { it.copy(cost = v) } }, s.currency, stringResource(R.string.asset_cost_basis), Modifier.fillMaxWidth(), supportingText = stringResource(R.string.asset_cost_hint))
                AmountField(
                    s.value, { v -> viewModel.update { it.copy(value = v) } }, s.currency,
                    stringResource(if (s.mode == ValuationMode.PER_UNIT) R.string.asset_unit_price else R.string.asset_total_value), Modifier.fillMaxWidth(),
                )
                DateField(stringResource(R.string.asset_valuation_date), s.date, s.date, { d -> viewModel.update { it.copy(date = d) } }, Modifier.fillMaxWidth())
                Text(stringResource(R.string.asset_existing_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            s.error?.let { Text(errorMessage(it), color = MaterialTheme.colorScheme.error) }
            Button(onClick = { viewModel.save(formats) }, enabled = s.name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_save)) }
        }
    }
}

// ---------------- Detail ----------------

data class AssetDetailState(
    val holding: AssetHolding? = null,
    val trades: List<AssetTrade> = emptyList(),
    val valuations: List<AssetValuation> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val snapshot: FinanceSnapshot? = null,
    val error: DomainError? = null,
)

@HiltViewModel
class AssetDetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    provider: FinanceSnapshotProvider,
    private val assets: AssetRepository,
    private val ops: AssetOperationsUseCase,
    private val ids: IdGenerator,
) : ViewModel() {
    val assetId = handle.toRoute<AssetDetailRoute>().assetId
    private val error = MutableStateFlow<DomainError?>(null)

    val state: StateFlow<AssetDetailState> = combine(provider.snapshot, error) { s, e ->
        AssetDetailState(
            holding = s.holdings.firstOrNull { it.asset.id == assetId },
            trades = s.trades.filter { it.assetId == assetId }.sortedByDescending { it.date },
            valuations = s.valuations.filter { it.assetId == assetId }.sortedByDescending { it.date },
            accounts = s.accounts.map { it.account }.filter { !it.isArchived && !it.type.isObligation },
            snapshot = s,
            error = e,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AssetDetailState())

    /**
     * [gross]/[fees] in the asset currency; [cash] in the account currency (needed when they differ, else = gross ± fees).
     */
    fun trade(formats: Formats, buy: Boolean, qtyText: String, grossText: String, feesText: String, cashText: String, accountId: String, date: LocalDate, onDone: () -> Unit) {
        val h = state.value.holding ?: return
        val account = state.value.accounts.firstOrNull { it.id == accountId } ?: run { error.value = DomainError.Required("account"); return }
        val qty = parseQuantity(qtyText) ?: run { error.value = DomainError.Required("quantity"); return }
        val gross = formats.parseAmount(grossText, h.asset.currency).let { if (it is Outcome.Failure) { error.value = it.error; return } else (it as Outcome.Success).value }
        val fees = if (feesText.isBlank()) 0L else formats.parseAmountOrNull(feesText, h.asset.currency) ?: run { error.value = DomainError.Required("fees"); return }
        val cash = if (account.currency == h.asset.currency) {
            if (buy) gross + fees else gross - fees
        } else {
            formats.parseAmountOrNull(cashText, account.currency) ?: run { error.value = DomainError.Required("amount"); return }
        }
        viewModelScope.launch {
            val r = if (buy) ops.buy(assetId, accountId, qty, gross, fees, cash, date, ids.newId()) else ops.sell(assetId, accountId, qty, gross, fees, cash, date, ids.newId())
            when (r) {
                is Outcome.Success -> { error.value = null; onDone() }
                is Outcome.Failure -> error.value = r.error
            }
        }
    }

    fun revalue(formats: Formats, text: String, date: LocalDate, onDone: () -> Unit) {
        val h = state.value.holding ?: return
        val minor = formats.parseAmountOrNull(text, h.asset.currency) ?: run { error.value = DomainError.Required("value"); return }
        viewModelScope.launch {
            val unit = if (h.asset.valuationMode == ValuationMode.PER_UNIT) BigDecimal.valueOf(minor, formats.minorDigits(h.asset.currency)) else null
            val total = if (h.asset.valuationMode == ValuationMode.TOTAL) minor else null
            when (val r = ops.revalue(assetId, date, unit, total, null)) {
                is Outcome.Success -> { error.value = null; onDone() }
                is Outcome.Failure -> error.value = r.error
            }
        }
    }

    fun deleteTrade(id: String) = viewModelScope.launch { assets.deleteTrade(id) }
    fun deleteValuation(id: String) = viewModelScope.launch { assets.deleteValuation(id) }
    fun archive() = viewModelScope.launch { state.value.holding?.asset?.let { assets.saveAsset(it.copy(isArchived = true)) } }
    fun clearError() { error.value = null }
}

@Composable
fun AssetDetailScreen(onBack: () -> Unit, onEdit: (String) -> Unit, viewModel: AssetDetailViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val formats = LocalFormats.current
    var dialog by rememberSaveable { mutableStateOf<String?>(null) } // buy | sell | value
    var confirmArchive by rememberSaveable { mutableStateOf(false) }
    val h = s.holding
    Scaffold(topBar = {
        BackTopBar(h?.asset?.name.orEmpty(), onBack) {
            if (h != null) {
                IconButton(onClick = { onEdit(h.asset.id) }) { Icon(Icons.Outlined.Edit, stringResource(R.string.action_edit)) }
                IconButton(onClick = { confirmArchive = true }) { Icon(Icons.Outlined.Archive, stringResource(R.string.acc_archive)) }
            }
        }
    }) { padding ->
        if (h == null) { LoadingSkeleton(Modifier.padding(padding)); return@Scaffold }
        val cur = h.asset.currency
        val today = s.snapshot?.today ?: LocalDate.now()
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(stringResource(assetTypeLabel(h.asset.type)) + " · " + Digits.localize(h.quantity.toPlainString(), formats.settings.digits) + " " + h.asset.unit)
                    val v = h.valueMinor
                    if (v != null) MoneyText(v, cur, style = MaterialTheme.typography.headlineMedium) else Text(stringResource(R.string.asset_no_value), color = MyMoneyTheme.finance.warning)
                    h.valuation?.let {
                        Text(
                            stringResource(R.string.asset_valued_on, formats.dates.medium(it.date), stringResource(valuationSourceLabel(it.source))),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(Modifier.padding(top = 8.dp)) {
                        KpiValue(stringResource(R.string.asset_cost_basis), h.costBasisMinor, cur, Modifier.weight(1f))
                        h.unrealizedMinor?.let { KpiValue(stringResource(R.string.asset_unrealized), it, cur, Modifier.weight(1f), AmountColor.SIGNED) }
                    }
                    if (h.realized.isNotEmpty()) KpiValue(stringResource(R.string.asset_realized), h.realizedTotalMinor, cur, Modifier.padding(top = 8.dp), AmountColor.SIGNED)
                }
            }
            if (h.isStale(today)) item { InfoBanner(stringResource(R.string.asset_stale_one), actionLabel = stringResource(R.string.asset_revalue), onAction = { dialog = "value" }) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { dialog = "buy" }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.asset_buy)) }
                    OutlinedButton(onClick = { dialog = "sell" }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.asset_sell)) }
                    OutlinedButton(onClick = { dialog = "value" }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.asset_revalue)) }
                }
                Text(stringResource(R.string.asset_rules_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { Text(stringResource(R.string.asset_trades), style = MaterialTheme.typography.titleSmall) }
            items(s.trades, key = { it.id }) { t ->
                ListItem(
                    headlineContent = { Text(stringResource(tradeTypeLabel(t.type)) + " · " + Digits.localize(t.quantity.toPlainString(), formats.settings.digits) + " " + h.asset.unit) },
                    supportingContent = { Text(formats.dates.medium(t.date)) },
                    trailingContent = {
                        Row {
                            MoneyText(t.grossMinor, cur)
                            IconButton(onClick = { viewModel.deleteTrade(t.id) }) { Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete)) }
                        }
                    },
                )
            }
            item { Text(stringResource(R.string.asset_valuations), style = MaterialTheme.typography.titleSmall) }
            items(s.valuations, key = { it.id }) { v ->
                ListItem(
                    headlineContent = {
                        val total = v.totalValueMinor
                        if (total != null) MoneyText(total, cur)
                        else Text(Digits.localize(v.unitPrice?.toPlainString().orEmpty(), formats.settings.digits) + " / " + h.asset.unit)
                    },
                    supportingContent = { Text(formats.dates.medium(v.date) + " · " + stringResource(valuationSourceLabel(v.source))) },
                    trailingContent = { IconButton(onClick = { viewModel.deleteValuation(v.id) }) { Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete)) } },
                )
            }
        }
    }
    when (dialog) {
        "buy", "sell" -> TradeDialog(viewModel, s, buy = dialog == "buy") { dialog = null; viewModel.clearError() }
        "value" -> RevalueDialog(viewModel, s) { dialog = null; viewModel.clearError() }
    }
    if (confirmArchive) {
        ConfirmDialog(stringResource(R.string.acc_archive), stringResource(R.string.asset_archive_confirm), stringResource(R.string.acc_archive),
            { confirmArchive = false; viewModel.archive(); onBack() }, { confirmArchive = false })
    }
}

@Composable
private fun TradeDialog(vm: AssetDetailViewModel, s: AssetDetailState, buy: Boolean, onDismiss: () -> Unit) {
    val formats = LocalFormats.current
    val h = s.holding ?: return
    var qty by rememberSaveable { mutableStateOf("") }
    var gross by rememberSaveable { mutableStateOf("") }
    var fees by rememberSaveable { mutableStateOf("") }
    var cash by rememberSaveable { mutableStateOf("") }
    var accountId by rememberSaveable { mutableStateOf(s.accounts.firstOrNull { it.currency == h.asset.currency }?.id ?: s.accounts.firstOrNull()?.id) }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    val account = s.accounts.firstOrNull { it.id == accountId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (buy) R.string.asset_buy else R.string.asset_sell)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(qty, { qty = it.take(30) }, label = { Text(stringResource(R.string.asset_quantity) + " (" + h.asset.unit + ")") }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal))
                AmountField(gross, { gross = it }, h.asset.currency, stringResource(R.string.asset_gross), Modifier.fillMaxWidth())
                AmountField(fees, { fees = it }, h.asset.currency, stringResource(R.string.editor_fee), Modifier.fillMaxWidth())
                Text(stringResource(if (buy) R.string.rule_pay_from else R.string.rule_to_account), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    s.accounts.forEach { a -> FilterChip(accountId == a.id, { accountId = a.id }, { Text(a.name) }) }
                }
                if (account != null && account.currency != h.asset.currency) {
                    AmountField(cash, { cash = it }, account.currency, stringResource(if (buy) R.string.asset_paid_in_account else R.string.asset_received_in_account), Modifier.fillMaxWidth())
                }
                DateField(stringResource(R.string.editor_date), LocalDate.ofEpochDay(date), LocalDate.now(), { date = it.toEpochDay() }, Modifier.fillMaxWidth())
                Text(stringResource(if (buy) R.string.asset_buy_note else R.string.asset_sell_note), style = MaterialTheme.typography.bodySmall)
                s.error?.let { Text(errorMessage(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { accountId?.let { vm.trade(formats, buy, qty, gross, fees, cash, it, LocalDate.ofEpochDay(date), onDismiss) } }) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun RevalueDialog(vm: AssetDetailViewModel, s: AssetDetailState, onDismiss: () -> Unit) {
    val formats = LocalFormats.current
    val h = s.holding ?: return
    var value by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.asset_revalue)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AmountField(value, { value = it }, h.asset.currency, stringResource(if (h.asset.valuationMode == ValuationMode.PER_UNIT) R.string.asset_unit_price else R.string.asset_total_value), Modifier.fillMaxWidth())
                DateField(stringResource(R.string.asset_valuation_date), LocalDate.ofEpochDay(date), LocalDate.now(), { date = it.toEpochDay() }, Modifier.fillMaxWidth())
                Text(stringResource(R.string.asset_manual_note), style = MaterialTheme.typography.bodySmall)
                s.error?.let { Text(errorMessage(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { vm.revalue(formats, value, LocalDate.ofEpochDay(date), onDismiss) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

fun tradeTypeLabel(t: TradeType): Int = when (t) {
    TradeType.OPENING -> R.string.asset_trade_opening
    TradeType.BUY -> R.string.asset_buy
    TradeType.SELL -> R.string.asset_sell
    TradeType.ADJUST -> R.string.asset_trade_adjust
}

fun valuationSourceLabel(v: ValuationSource): Int = when (v) {
    ValuationSource.MANUAL -> R.string.rate_source_manual
    ValuationSource.TRADE -> R.string.asset_source_trade
    ValuationSource.ESTIMATE -> R.string.asset_source_estimate
}

