@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.mymoney.app.feature.wealth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import com.mymoney.app.feature.transactions.TransactionsScreen
import com.mymoney.app.navigation.ObligationDetailRoute
import com.mymoney.app.navigation.ObligationEditorRoute
import com.mymoney.app.ui.common.BackTopBar
import com.mymoney.app.ui.common.SaveAction
import com.mymoney.app.ui.common.CategoryPickerSheet
import com.mymoney.app.ui.common.ConfirmDialog
import com.mymoney.app.ui.common.DateField
import com.mymoney.app.ui.common.OptionalDateField
import com.mymoney.app.ui.common.PickerField
import com.mymoney.app.ui.common.categoryLabel
import com.mymoney.app.ui.common.editableAmount
import com.mymoney.app.ui.common.errorMessage
import com.mymoney.app.ui.common.parseAmount
import com.mymoney.app.ui.common.parseAmountOrNull
import com.mymoney.core.designsystem.component.AmountColor
import com.mymoney.core.designsystem.component.AmountField
import com.mymoney.core.designsystem.component.EmptyState
import com.mymoney.core.designsystem.component.KpiValue
import com.mymoney.core.designsystem.component.LoadingSkeleton
import com.mymoney.core.designsystem.component.MoneyText
import com.mymoney.core.designsystem.component.ProgressBar
import com.mymoney.core.designsystem.component.SectionCard
import com.mymoney.core.designsystem.format.Formats
import com.mymoney.core.designsystem.format.LocalFormats
import com.mymoney.core.designsystem.theme.MyMoneyTheme
import com.mymoney.core.domain.calc.Amortization
import com.mymoney.core.domain.common.DomainError
import com.mymoney.core.domain.common.IdGenerator
import com.mymoney.core.domain.common.Outcome
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.model.Account
import com.mymoney.core.domain.model.AccountWithBalance
import com.mymoney.core.domain.model.CategoryType
import com.mymoney.core.domain.model.Frequency
import com.mymoney.core.domain.model.InterestMethod
import com.mymoney.core.domain.model.Obligation
import com.mymoney.core.domain.model.ObligationDirection
import com.mymoney.core.domain.model.ObligationKind
import com.mymoney.core.domain.model.ObligationStatus
import com.mymoney.core.domain.model.RecurringRule
import com.mymoney.core.domain.model.RuleKind
import com.mymoney.core.domain.money.Digits
import com.mymoney.core.domain.repository.ObligationRepository
import com.mymoney.core.domain.repository.RecurringRepository
import com.mymoney.core.domain.usecase.CreateObligationUseCase
import com.mymoney.core.domain.usecase.ExtraLeg
import com.mymoney.core.domain.usecase.ObligationOrigin
import com.mymoney.core.domain.usecase.RecordOperationUseCase
import com.mymoney.core.domain.usecase.TransferDraft
import com.mymoney.core.domain.usecase.WriteOffObligationUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ObligationRow(val obligation: Obligation, val account: AccountWithBalance) {
    /** Outstanding amount, positive (FBR-10: debts are negative balances, receivables positive). */
    val outstanding: Long get() = kotlin.math.abs(account.balanceMinor)
    val paidFraction: Float get() = if (obligation.principalMinor <= 0) 0f else (1f - outstanding.toFloat() / obligation.principalMinor).coerceIn(0f, 1f)
}

fun FinanceSnapshot.obligationRows(): List<ObligationRow> = obligations.mapNotNull { o ->
    accountsById[o.accountId]?.let { ObligationRow(o, it) }
}

@androidx.annotation.StringRes
fun obligationKindLabel(k: ObligationKind): Int = when (k) {
    ObligationKind.BANK_LOAN -> R.string.ob_kind_bank
    ObligationKind.PERSONAL -> R.string.ob_kind_personal
    ObligationKind.INSTALLMENT_PURCHASE -> R.string.ob_kind_installment
    ObligationKind.FAMILY -> R.string.ob_kind_family
    ObligationKind.CUSTOMER -> R.string.ob_kind_customer
    ObligationKind.OTHER -> R.string.ob_kind_other
}

// ---------------- List ----------------

@HiltViewModel
class ObligationsViewModel @Inject constructor(provider: FinanceSnapshotProvider) : ViewModel() {
    val rows: StateFlow<List<ObligationRow>?> = provider.snapshot.map { it.obligationRows() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun ObligationsScreen(
    initialReceivables: Boolean,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onAdd: (receivable: Boolean) -> Unit,
    viewModel: ObligationsViewModel = hiltViewModel(),
) {
    var tab by rememberSaveable { mutableIntStateOf(if (initialReceivables) 1 else 0) }
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val formats = LocalFormats.current
    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.ob_title), onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { onAdd(tab == 1) }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text(stringResource(if (tab == 0) R.string.ob_add_debt else R.string.ob_add_receivable)) })
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.ob_debts)) })
                Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.ob_receivables)) })
            }
            val list = rows
            if (list == null) { LoadingSkeleton(); return@Column }
            val dir = if (tab == 0) ObligationDirection.PAYABLE else ObligationDirection.RECEIVABLE
            val shown = list.filter { it.obligation.direction == dir }.sortedWith(compareBy({ it.outstanding == 0L }, { it.obligation.dueDate ?: LocalDate.MAX }))
            if (shown.isEmpty()) {
                EmptyState(Icons.Filled.Add, stringResource(if (tab == 0) R.string.ob_empty_debts else R.string.ob_empty_receivables), stringResource(R.string.ob_explain))
                return@Column
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                items(shown, key = { it.obligation.accountId }) { r ->
                    ListItem(
                        modifier = Modifier.clickable { onOpen(r.obligation.accountId) },
                        headlineContent = { Text(r.account.account.name) },
                        supportingContent = {
                            Column {
                                Text(r.obligation.counterparty + " · " + stringResource(obligationKindLabel(r.obligation.kind)) +
                                    (r.obligation.dueDate?.let { " · " + formats.dates.medium(it) } ?: ""))
                                ProgressBar(r.paidFraction, Modifier.padding(top = 4.dp), warnAt = 2f)
                            }
                        },
                        trailingContent = {
                            if (r.outstanding == 0L) Text(stringResource(R.string.ob_settled), color = MyMoneyTheme.finance.income)
                            else MoneyText(r.outstanding, r.account.account.currency, color = if (dir == ObligationDirection.PAYABLE) AmountColor.EXPENSE else AmountColor.INCOME)
                        },
                    )
                }
            }
        }
    }
}

// ---------------- Editor (create) ----------------

data class ObligationEditorState(
    val receivable: Boolean = false,
    val name: String = "",
    val counterparty: String = "",
    val kind: ObligationKind = ObligationKind.BANK_LOAN,
    val principal: String = "",
    val currency: String = "IRR",
    val startDate: LocalDate = LocalDate.now(),
    val dueDate: LocalDate? = null,
    val rate: String = "",
    val method: InterestMethod = InterestMethod.NONE,
    val termMonths: String = "",
    val origin: ObligationOrigin = ObligationOrigin.CASH,
    val fundsAccountId: String? = null,
    val purchaseCategoryId: String? = null,
    val createInstallments: Boolean = true,
    val accounts: List<Account> = emptyList(),
    val categories: List<com.mymoney.core.domain.model.Category> = emptyList(),
    val currencies: List<String> = emptyList(),
    val today: LocalDate = LocalDate.now(),
    val error: DomainError? = null,
    val done: Boolean = false,
)

@HiltViewModel
class ObligationEditorViewModel @Inject constructor(
    handle: SavedStateHandle,
    provider: FinanceSnapshotProvider,
    private val create: CreateObligationUseCase,
    private val recurring: RecurringRepository,
    clock: TimeProvider,
) : ViewModel() {
    private val receivable = handle.toRoute<ObligationEditorRoute>().receivable
    private val _s = MutableStateFlow(ObligationEditorState(receivable = receivable))
    val state: StateFlow<ObligationEditorState> = _s

    init {
        viewModelScope.launch {
            val s = provider.snapshot.first()
            val accs = s.accounts.map { it.account }.filter { !it.isArchived && !it.type.isObligation }
            _s.value = _s.value.copy(
                currency = s.base, accounts = accs, fundsAccountId = accs.firstOrNull()?.id, categories = s.categories.filter { it.type == CategoryType.EXPENSE && !it.isArchived },
                currencies = s.currencies.values.filter { it.isEnabled }.map { it.code }, today = clock.today(), startDate = clock.today(),
                kind = if (receivable) ObligationKind.PERSONAL else ObligationKind.BANK_LOAN,
            )
        }
    }

    fun update(f: (ObligationEditorState) -> ObligationEditorState) { _s.value = f(_s.value).copy(error = null) }

    fun save(formats: Formats) {
        val s = _s.value
        val principal = when (val r = formats.parseAmount(s.principal, s.currency)) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> { _s.value = s.copy(error = r.error); return }
        }
        val rate = if (s.rate.isBlank()) null else Digits.normalize(s.rate).toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }
            ?: run { _s.value = s.copy(error = DomainError.InvalidRate("rate")); return }
        val term = s.termMonths.toIntOrNull()?.takeIf { it in 1..600 }
        val method = if (rate == null) InterestMethod.NONE else s.method
        val ob = Obligation(
            accountId = "", direction = if (s.receivable) ObligationDirection.RECEIVABLE else ObligationDirection.PAYABLE,
            counterparty = s.counterparty.trim().ifEmpty { s.name }, kind = s.kind, principalMinor = principal,
            startDate = s.startDate, dueDate = s.dueDate, annualRatePercent = rate, interestMethod = method, termMonths = term,
        )
        viewModelScope.launch {
            when (val r = create(ob, s.name.ifBlank { ob.counterparty }, s.currency, s.origin, s.fundsAccountId, s.purchaseCategoryId)) {
                is Outcome.Failure -> _s.value = s.copy(error = r.error)
                is Outcome.Success -> {
                    // Optional installment plan: a monthly LOAN_INSTALLMENT rule; nothing is paid until confirmed (FBR-12).
                    if (!s.receivable && s.createInstallments && term != null && s.fundsAccountId != null) {
                        val first = Amortization.schedule(method, principal, rate, term).installments.first().paymentMinor
                        recurring.save(
                            RecurringRule(
                                "", RuleKind.LOAN_INSTALLMENT, s.name.ifBlank { ob.counterparty }, s.fundsAccountId, toAccountId = r.value,
                                amountMinor = first, currency = s.currency, frequency = Frequency.MONTHLY,
                                calendar = formats.settings.calendar, startDate = formats.calendar.plusMonths(s.startDate, 1),
                                maxOccurrences = term, reminderDaysBefore = 3,
                            ),
                        )
                    }
                    _s.value = s.copy(done = true)
                }
            }
        }
    }
}

@Composable
fun ObligationEditorScreen(onDone: () -> Unit, viewModel: ObligationEditorViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val formats = LocalFormats.current
    var pickCategory by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(s.done) { if (s.done) onDone() }
    val spacing = MyMoneyTheme.spacing
    Scaffold(topBar = { BackTopBar(stringResource(if (s.receivable) R.string.ob_add_receivable else R.string.ob_add_debt), onDone) {
        SaveAction(s.counterparty.isNotBlank() || s.name.isNotBlank()) { viewModel.save(formats) }
    } }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(spacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(spacing.m),
        ) {
            OutlinedTextField(s.counterparty, { v -> viewModel.update { it.copy(counterparty = v.take(60)) } }, label = { Text(stringResource(if (s.receivable) R.string.ob_debtor else R.string.ob_lender)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(s.name, { v -> viewModel.update { it.copy(name = v.take(60)) } }, label = { Text(stringResource(R.string.ob_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                ObligationKind.entries.forEach { k -> FilterChip(s.kind == k, { viewModel.update { it.copy(kind = k) } }, { Text(stringResource(obligationKindLabel(k))) }) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                s.currencies.forEach { c -> FilterChip(s.currency == c, { viewModel.update { it.copy(currency = c) } }, { Text(c) }) }
            }
            AmountField(s.principal, { v -> viewModel.update { it.copy(principal = v) } }, s.currency, stringResource(R.string.ob_principal), Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                DateField(stringResource(R.string.acc_start_date), s.startDate, s.today, { d -> viewModel.update { it.copy(startDate = d) } }, Modifier.weight(1f))
                OptionalDateField(stringResource(R.string.ob_due), s.dueDate, s.today, { d -> viewModel.update { it.copy(dueDate = d) } }, Modifier.weight(1f))
            }
            Text(stringResource(R.string.ob_origin), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                FilterChip(s.origin == ObligationOrigin.CASH, { viewModel.update { it.copy(origin = ObligationOrigin.CASH) } }, { Text(stringResource(if (s.receivable) R.string.ob_origin_lent else R.string.ob_origin_received)) })
                FilterChip(s.origin == ObligationOrigin.EXISTING, { viewModel.update { it.copy(origin = ObligationOrigin.EXISTING) } }, { Text(stringResource(R.string.ob_origin_existing)) })
                if (!s.receivable) FilterChip(s.origin == ObligationOrigin.PURCHASE, { viewModel.update { it.copy(origin = ObligationOrigin.PURCHASE) } }, { Text(stringResource(R.string.ob_origin_purchase)) })
            }
            if (s.origin == ObligationOrigin.CASH || (!s.receivable && s.createInstallments)) {
                Text(stringResource(if (s.receivable) R.string.rule_pay_from else R.string.ob_funds_account), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    s.accounts.filter { it.currency == s.currency }.forEach { a -> FilterChip(s.fundsAccountId == a.id, { viewModel.update { it.copy(fundsAccountId = a.id) } }, { Text(a.name) }) }
                }
            }
            if (s.origin == ObligationOrigin.PURCHASE) {
                PickerField(stringResource(R.string.editor_category), s.categories.firstOrNull { it.id == s.purchaseCategoryId }?.let { categoryLabel(it) }.orEmpty(), { pickCategory = true })
            }
            Text(stringResource(R.string.ob_origin_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!s.receivable) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                    OutlinedTextField(s.rate, { v -> viewModel.update { it.copy(rate = v.take(6)) } }, label = { Text(stringResource(R.string.tools_rate)) }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    OutlinedTextField(s.termMonths, { v -> viewModel.update { it.copy(termMonths = Digits.normalize(v).filter(Char::isDigit).take(3)) } }, label = { Text(stringResource(R.string.tools_months)) }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                    FilterChip(s.method == InterestMethod.ANNUITY, { viewModel.update { it.copy(method = InterestMethod.ANNUITY) } }, { Text(stringResource(R.string.tools_annuity)) })
                    FilterChip(s.method == InterestMethod.SIMPLE, { viewModel.update { it.copy(method = InterestMethod.SIMPLE) } }, { Text(stringResource(R.string.tools_simple)) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ob_create_installments), Modifier.weight(1f))
                    Switch(s.createInstallments, { v -> viewModel.update { it.copy(createInstallments = v) } })
                }
            }
            s.error?.let { Text(errorMessage(it), color = MaterialTheme.colorScheme.error) }
            Button(onClick = { viewModel.save(formats) }, enabled = s.counterparty.isNotBlank() || s.name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_save)) }
        }
    }
    if (pickCategory) {
        CategoryPickerSheet(s.categories, { c -> viewModel.update { it.copy(purchaseCategoryId = c.id) }; pickCategory = false }, { pickCategory = false })
    }
}

// ---------------- Detail ----------------

data class ObligationDetailState(
    val row: ObligationRow? = null,
    val funds: List<Account> = emptyList(),
    val error: DomainError? = null,
    val today: LocalDate = LocalDate.now(),
)

@HiltViewModel
class ObligationDetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    provider: FinanceSnapshotProvider,
    private val obligations: ObligationRepository,
    private val record: RecordOperationUseCase,
    private val writeOff: WriteOffObligationUseCase,
    private val ids: IdGenerator,
    clock: TimeProvider,
) : ViewModel() {
    val accountId = handle.toRoute<ObligationDetailRoute>().accountId
    private val error = MutableStateFlow<DomainError?>(null)
    private val today = clock.today()

    val state: StateFlow<ObligationDetailState> = combine(provider.snapshot, error) { s, e ->
        ObligationDetailState(
            row = s.obligationRows().firstOrNull { it.obligation.accountId == accountId },
            funds = s.accounts.map { it.account }.filter { !it.isArchived && !it.type.isObligation },
            error = e, today = s.today,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ObligationDetailState())

    fun suggestedInterest(paymentMinor: Long): Long {
        val r = state.value.row ?: return 0
        return Amortization.suggestSplit(paymentMinor, r.outstanding, r.obligation.annualRatePercent, r.obligation.interestMethod).second
    }

    /** Payment on a debt (principal transfer + interest expense, FBR-04) or collection of a receivable (FBR-05). */
    fun settle(formats: Formats, amountText: String, interestText: String, fundsId: String, date: LocalDate, onDone: () -> Unit) {
        val r = state.value.row ?: return
        val cur = r.account.account.currency
        val amount = formats.parseAmount(amountText, cur).let { if (it is Outcome.Failure) { error.value = it.error; return } else (it as Outcome.Success).value }
        val interest = if (interestText.isBlank()) 0 else formats.parseAmountOrNull(interestText, cur) ?: run { error.value = DomainError.Required("interest"); return }
        val payable = r.obligation.direction == ObligationDirection.PAYABLE
        val principal = amount - interest
        if (principal <= 0) { error.value = DomainError.RuleViolation("FBR-04", "principal"); return }
        val draft = if (payable) {
            TransferDraft(ids.newId(), fundsId, accountId, principal, principal, date, feeMinor = interest, extraLeg = ExtraLeg.INTEREST, note = r.obligation.counterparty)
        } else {
            TransferDraft(ids.newId(), accountId, fundsId, amount, amount, date, note = r.obligation.counterparty)
        }
        viewModelScope.launch {
            when (val res = record(draft)) {
                is Outcome.Failure -> error.value = res.error
                is Outcome.Success -> {
                    error.value = null
                    if (principal >= r.outstanding) obligations.save(r.obligation.copy(status = ObligationStatus.SETTLED))
                    onDone()
                }
            }
        }
    }

    fun writeOff(reason: String, onDone: () -> Unit) = viewModelScope.launch {
        when (val r = writeOff(accountId, today, reason)) {
            is Outcome.Success -> { error.value = null; onDone() }
            is Outcome.Failure -> error.value = r.error
        }
    }

    fun clearError() { error.value = null }
}

@Composable
fun ObligationDetailScreen(onBack: () -> Unit, onOpenEntry: (String) -> Unit, onAddInstallments: (String) -> Unit, viewModel: ObligationDetailViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val formats = LocalFormats.current
    val snackbar = remember { SnackbarHostState() }
    var paying by rememberSaveable { mutableStateOf(false) }
    var writingOff by rememberSaveable { mutableStateOf(false) }
    val r = s.row
    Scaffold(topBar = { BackTopBar(r?.account?.account?.name.orEmpty(), onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        if (r == null) { LoadingSkeleton(Modifier.padding(padding)); return@Scaffold }
        val payable = r.obligation.direction == ObligationDirection.PAYABLE
        val cur = r.account.account.currency
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionCard(containerColor = if (payable) MyMoneyTheme.finance.expenseContainer else MyMoneyTheme.finance.incomeContainer) {
                    Text(r.obligation.counterparty + " · " + stringResource(obligationKindLabel(r.obligation.kind)), style = MaterialTheme.typography.labelLarge)
                    MoneyText(r.outstanding, cur, style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(if (payable) R.string.ob_remaining_debt else R.string.ob_remaining_receivable), style = MaterialTheme.typography.bodySmall)
                    ProgressBar(r.paidFraction, Modifier.padding(vertical = 6.dp), warnAt = 2f)
                    Row {
                        KpiValue(stringResource(R.string.ob_principal), r.obligation.principalMinor, cur, Modifier.weight(1f))
                        r.obligation.annualRatePercent?.let {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.tools_rate), style = MaterialTheme.typography.labelMedium)
                                Text(Digits.localize(it.toPlainString() + "%", formats.settings.digits))
                            }
                        }
                    }
                    r.obligation.dueDate?.let { Text(stringResource(R.string.ob_due_on, formats.dates.medium(it)), style = MaterialTheme.typography.bodySmall) }
                    if (r.obligation.status != ObligationStatus.OPEN) {
                        Text(stringResource(if (r.obligation.status == ObligationStatus.SETTLED) R.string.ob_settled else R.string.ob_written_off), color = MyMoneyTheme.finance.income)
                    }
                }
                if (r.outstanding > 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { paying = true }, modifier = Modifier.weight(1f)) { Text(stringResource(if (payable) R.string.ob_pay else R.string.ob_collect)) }
                        OutlinedButton(onClick = { writingOff = true }, modifier = Modifier.weight(1f)) { Text(stringResource(if (payable) R.string.ob_forgive else R.string.ob_write_off)) }
                    }
                    if (payable) TextButton(onClick = { onAddInstallments(r.obligation.accountId) }) { Text(stringResource(R.string.ob_add_plan)) }
                }
                Text(stringResource(if (payable) R.string.ob_rules_payable else R.string.ob_rules_receivable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TransactionsScreen(PaddingValues(), snackbar, s.today, onOpenEntry, fixedAccountId = r.obligation.accountId, showSearch = false)
        }
        if (paying) SettleDialog(viewModel, s, payable) { paying = false; viewModel.clearError() }
        if (writingOff) {
            ConfirmDialog(
                stringResource(if (payable) R.string.ob_forgive else R.string.ob_write_off),
                stringResource(if (payable) R.string.ob_forgive_confirm else R.string.ob_write_off_confirm, formats.money.format(r.outstanding, cur)),
                stringResource(R.string.action_confirm),
                { writingOff = false; viewModel.writeOff(if (payable) "forgiven" else "uncollectible") {} },
                { writingOff = false },
                destructive = true,
            )
        }
    }
}

@Composable
private fun SettleDialog(vm: ObligationDetailViewModel, s: ObligationDetailState, payable: Boolean, onDismiss: () -> Unit) {
    val formats = LocalFormats.current
    val r = s.row ?: return
    val cur = r.account.account.currency
    var amount by rememberSaveable { mutableStateOf("") }
    var interest by rememberSaveable { mutableStateOf("") }
    var fundsId by rememberSaveable { mutableStateOf(s.funds.firstOrNull { it.currency == cur }?.id) }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (payable) R.string.ob_pay else R.string.ob_collect)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AmountField(amount, {
                    amount = it
                    if (payable) formats.parseAmountOrNull(it, cur)?.let { a ->
                        val sug = vm.suggestedInterest(a)
                        interest = if (sug == 0L) "" else formats.editableAmount(sug, cur)
                    }
                }, cur, stringResource(R.string.editor_amount), Modifier.fillMaxWidth())
                if (payable) AmountField(interest, { interest = it }, cur, stringResource(R.string.plan_interest_part), Modifier.fillMaxWidth(), supportingText = stringResource(R.string.plan_interest_hint))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    s.funds.filter { it.currency == cur }.forEach { a -> FilterChip(fundsId == a.id, { fundsId = a.id }, { Text(a.name) }) }
                }
                DateField(stringResource(R.string.editor_date), LocalDate.ofEpochDay(date), LocalDate.now(), { date = it.toEpochDay() }, Modifier.fillMaxWidth())
                s.error?.let { Text(errorMessage(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { fundsId?.let { vm.settle(formats, amount, interest, it, LocalDate.ofEpochDay(date), onDismiss) } }) { Text(stringResource(R.string.action_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

