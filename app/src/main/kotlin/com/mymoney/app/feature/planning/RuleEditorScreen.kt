@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.mymoney.app.feature.planning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.mymoney.app.R
import com.mymoney.app.navigation.RuleEditorRoute
import com.mymoney.app.ui.common.AccountPickerSheet
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
import com.mymoney.core.designsystem.component.AmountField
import com.mymoney.core.designsystem.component.LoadingSkeleton
import com.mymoney.core.designsystem.format.Formats
import com.mymoney.core.designsystem.format.LocalFormats
import com.mymoney.core.designsystem.theme.MyMoneyTheme
import com.mymoney.core.domain.calendar.CalendarSystem
import com.mymoney.core.domain.common.DomainError
import com.mymoney.core.domain.common.Outcome
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.model.Account
import com.mymoney.core.domain.model.AmountChange
import com.mymoney.core.domain.model.Category
import com.mymoney.core.domain.model.CategoryType
import com.mymoney.core.domain.model.Frequency
import com.mymoney.core.domain.model.PostingPolicy
import com.mymoney.core.domain.model.RecurringRule
import com.mymoney.core.domain.model.RuleKind
import com.mymoney.core.domain.model.RuleStatus
import com.mymoney.core.domain.repository.AccountRepository
import com.mymoney.core.domain.repository.CategoryRepository
import com.mymoney.core.domain.repository.RecurringRepository
import com.mymoney.core.domain.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RuleEditorState(
    val loading: Boolean = true,
    val original: RecurringRule? = null,
    val kind: RuleKind = RuleKind.BILL,
    val name: String = "",
    val accountId: String? = null,
    val toAccountId: String? = null,
    val categoryId: String? = null,
    val amount: String = "",
    val estimate: Boolean = false,
    val frequency: Frequency = Frequency.MONTHLY,
    val interval: String = "1",
    val startDate: LocalDate = LocalDate.now(),
    val endDate: LocalDate? = null,
    val reminderDays: Int? = 3,
    val autoPost: Boolean = false,
    val status: RuleStatus = RuleStatus.ACTIVE,
    val payee: String = "",
    val changeFrom: LocalDate? = null,
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val today: LocalDate = LocalDate.now(),
    val error: DomainError? = null,
    val done: Boolean = false,
) {
    val account: Account? get() = accounts.firstOrNull { it.id == accountId }
    val needsTarget: Boolean get() = kind == RuleKind.TRANSFER || kind == RuleKind.LOAN_INSTALLMENT
    val needsCategory: Boolean get() = !needsTarget
}

@HiltViewModel
class RuleEditorViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val recurring: RecurringRepository,
    accounts: AccountRepository,
    categories: CategoryRepository,
    private val settings: SettingsRepository,
    clock: TimeProvider,
) : ViewModel() {
    private val route = handle.toRoute<RuleEditorRoute>()
    private val _s = MutableStateFlow(RuleEditorState())
    val state: StateFlow<RuleEditorState> = _s.asStateFlow()

    init {
        viewModelScope.launch {
            val accs = accounts.getAll().filter { !it.isArchived }
            val cats = categories.getAll().filter { !it.isArchived }
            val today = clock.today()
            val r = route.ruleId?.let { recurring.get(it) }
            _s.value = if (r == null) {
                val kind = route.kind?.let { runCatching { RuleKind.valueOf(it) }.getOrNull() } ?: RuleKind.BILL
                RuleEditorState(
                    loading = false, kind = kind, accountId = accs.firstOrNull { !it.type.isObligation }?.id,
                    toAccountId = route.toAccountId, accounts = accs, categories = cats, today = today, startDate = today,
                )
            } else {
                RuleEditorState(
                    loading = false, original = r, kind = r.kind, name = r.name, accountId = r.accountId, toAccountId = r.toAccountId,
                    categoryId = r.categoryId, amount = "", estimate = r.amountIsEstimate, frequency = r.frequency, interval = r.interval.toString(),
                    startDate = r.startDate, endDate = r.endDate, reminderDays = r.reminderDaysBefore, autoPost = r.postingPolicy == PostingPolicy.AUTO_POST,
                    status = r.status, payee = r.payee.orEmpty(), accounts = accs, categories = cats, today = today,
                )
            }
        }
    }

    /** Fills the amount field once Formats are known (display unit). */
    fun prefillAmount(formats: Formats) {
        val r = _s.value.original ?: return
        if (_s.value.amount.isEmpty()) {
            _s.update { it.copy(amount = formats.editableAmount(com.mymoney.core.domain.calc.Recurrence.amountOn(r, it.today), r.currency)) }
        }
    }

    fun update(f: (RuleEditorState) -> RuleEditorState) = _s.update { f(it).copy(error = null) }

    fun save(formats: Formats) {
        val s = _s.value
        val acc = s.account ?: run { _s.update { it.copy(error = DomainError.Required("account")) }; return }
        val amount = when (val r = formats.parseAmount(s.amount, acc.currency)) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> { _s.update { it.copy(error = r.error) }; return }
        }
        if (s.needsCategory && s.categoryId == null) { _s.update { it.copy(error = DomainError.Required("category")) }; return }
        val interval = s.interval.toIntOrNull()?.takeIf { it in 1..366 } ?: run { _s.update { it.copy(error = DomainError.Required("interval")) }; return }
        viewModelScope.launch {
            val cal = settings.settings.first().calendar
            val original = s.original
            // Editing the amount of an existing rule applies from a date and never rewrites history (docs/01 §10).
            val changes = if (original != null && amount != com.mymoney.core.domain.calc.Recurrence.amountOn(original, s.today)) {
                original.amountChanges + AmountChange(s.changeFrom ?: s.today, amount)
            } else {
                original?.amountChanges.orEmpty()
            }
            val rule = RecurringRule(
                id = original?.id ?: "",
                kind = s.kind,
                name = s.name,
                accountId = acc.id,
                toAccountId = if (s.needsTarget) s.toAccountId else null,
                categoryId = if (s.needsCategory) s.categoryId else null,
                amountMinor = original?.amountMinor ?: amount,
                currency = acc.currency,
                amountIsEstimate = s.estimate,
                frequency = s.frequency,
                interval = interval,
                calendar = original?.calendar ?: cal,
                startDate = s.startDate,
                anchorDay = CalendarSystem.of(original?.calendar ?: cal).toYmd(s.startDate).day,
                endDate = s.endDate,
                postingPolicy = if (s.autoPost) PostingPolicy.AUTO_POST else PostingPolicy.MANUAL_CONFIRM,
                reminderDaysBefore = s.reminderDays,
                status = s.status,
                payee = s.payee.trim().ifEmpty { null },
                amountChanges = changes,
            )
            when (val r = recurring.save(rule)) {
                is Outcome.Success -> _s.update { it.copy(done = true) }
                is Outcome.Failure -> _s.update { it.copy(error = r.error) }
            }
        }
    }

    fun delete() = viewModelScope.launch { _s.value.original?.let { recurring.delete(it.id) }; _s.update { it.copy(done = true) } }
}

@Composable
fun RuleEditorScreen(onDone: () -> Unit, viewModel: RuleEditorViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val formats = LocalFormats.current
    LaunchedEffect(s.done) { if (s.done) onDone() }
    LaunchedEffect(s.loading) { if (!s.loading) viewModel.prefillAmount(formats) }
    var pickAccount by rememberSaveable { mutableStateOf<String?>(null) }
    var pickCategory by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val spacing = MyMoneyTheme.spacing

    Scaffold(topBar = {
        BackTopBar(stringResource(if (s.original == null) R.string.rule_add else R.string.rule_edit), onDone) {
            if (s.original != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete)) }
            if (!s.loading) SaveAction(s.name.isNotBlank()) { viewModel.save(formats) }
        }
    }) { padding ->
        if (s.loading) { LoadingSkeleton(Modifier.padding(padding)); return@Scaffold }
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(spacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(spacing.m),
        ) {
            Text(stringResource(R.string.rule_kind), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                RuleKind.entries.forEach { k ->
                    FilterChip(s.kind == k, { if (s.original == null) viewModel.update { it.copy(kind = k) } }, { Text(stringResource(ruleKindLabel(k))) })
                }
            }
            OutlinedTextField(s.name, { v -> viewModel.update { it.copy(name = v.take(60)) } }, label = { Text(stringResource(R.string.cat_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            PickerField(
                stringResource(if (s.kind == RuleKind.INCOME) R.string.rule_to_account else R.string.rule_pay_from),
                s.account?.name.orEmpty(), { pickAccount = "from" }, icon = { Icon(Icons.Outlined.ExpandMore, null) },
            )
            if (s.needsTarget) {
                PickerField(
                    stringResource(if (s.kind == RuleKind.LOAN_INSTALLMENT) R.string.rule_loan_account else R.string.editor_to_account),
                    s.accounts.firstOrNull { it.id == s.toAccountId }?.name.orEmpty(), { pickAccount = "to" }, icon = { Icon(Icons.Outlined.ExpandMore, null) },
                )
            }
            if (s.needsCategory) {
                PickerField(stringResource(R.string.editor_category), s.categories.firstOrNull { it.id == s.categoryId }?.let { categoryLabel(it) }.orEmpty(), { pickCategory = true }, icon = { Icon(Icons.Outlined.ExpandMore, null) })
            }
            AmountField(s.amount, { v -> viewModel.update { it.copy(amount = v) } }, s.account?.currency ?: formats.baseCurrency, stringResource(R.string.editor_amount), Modifier.fillMaxWidth())
            if (s.original != null) {
                OptionalDateField(stringResource(R.string.rule_amount_from), s.changeFrom, s.today, { d -> viewModel.update { it.copy(changeFrom = d) } }, Modifier.fillMaxWidth())
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(R.string.rule_estimate), Modifier.weight(1f))
                androidx.compose.material3.Switch(s.estimate, { v -> viewModel.update { it.copy(estimate = v) } })
            }
            Text(stringResource(R.string.rule_frequency), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                Frequency.entries.forEach { f -> FilterChip(s.frequency == f, { viewModel.update { it.copy(frequency = f) } }, { Text(stringResource(frequencyLabel(f))) }) }
            }
            if (s.frequency != Frequency.ONCE) {
                OutlinedTextField(
                    s.interval, { v -> viewModel.update { it.copy(interval = com.mymoney.core.domain.money.Digits.normalize(v).filter(Char::isDigit).take(3)) } },
                    label = { Text(stringResource(R.string.rule_interval)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                DateField(stringResource(R.string.rule_first_due), s.startDate, s.today, { d -> viewModel.update { it.copy(startDate = d) } }, Modifier.weight(1f))
                OptionalDateField(stringResource(R.string.rule_end), s.endDate, s.today, { d -> viewModel.update { it.copy(endDate = d) } }, Modifier.weight(1f))
            }
            Text(stringResource(R.string.rule_reminder), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                listOf(null, 0, 1, 3, 7).forEach { d ->
                    FilterChip(s.reminderDays == d, { viewModel.update { it.copy(reminderDays = d) } }, {
                        Text(if (d == null) stringResource(R.string.rule_no_reminder) else stringResource(R.string.rule_days_before, d))
                    })
                }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.rule_auto_post))
                    Text(stringResource(R.string.rule_auto_post_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                androidx.compose.material3.Switch(s.autoPost, { v -> viewModel.update { it.copy(autoPost = v) } })
            }
            if (s.original != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                    RuleStatus.entries.forEach { st ->
                        FilterChip(s.status == st, { viewModel.update { it.copy(status = st) } }, {
                            Text(stringResource(when (st) { RuleStatus.ACTIVE -> R.string.rule_active; RuleStatus.PAUSED -> R.string.rule_paused; RuleStatus.ENDED -> R.string.rule_ended }))
                        })
                    }
                }
            }
            Text(stringResource(R.string.rule_no_auto_money), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            s.error?.let { Text(errorMessage(it), color = MaterialTheme.colorScheme.error) }
            Button(onClick = { viewModel.save(formats) }, enabled = s.name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_save)) }
        }
    }
    pickAccount?.let { which ->
        val list = when {
            which == "to" && s.kind == RuleKind.LOAN_INSTALLMENT -> s.accounts.filter { it.type == com.mymoney.core.domain.model.AccountType.LIABILITY }
            else -> s.accounts.filter { !it.type.isObligation }
        }
        AccountPickerSheet(list, { a -> viewModel.update { if (which == "to") it.copy(toAccountId = a.id) else it.copy(accountId = a.id) }; pickAccount = null }, { pickAccount = null })
    }
    if (pickCategory) {
        val type = if (s.kind == RuleKind.INCOME) CategoryType.INCOME else CategoryType.EXPENSE
        CategoryPickerSheet(s.categories.filter { it.type == type }, { c -> viewModel.update { it.copy(categoryId = c.id) }; pickCategory = false }, { pickCategory = false })
    }
    if (confirmDelete) {
        ConfirmDialog(stringResource(R.string.action_delete), stringResource(R.string.rule_delete_confirm), stringResource(R.string.action_delete),
            { confirmDelete = false; viewModel.delete() }, { confirmDelete = false }, destructive = true)
    }
}

fun ruleKindLabel(k: RuleKind): Int = when (k) {
    RuleKind.BILL -> R.string.rule_kind_bill
    RuleKind.SUBSCRIPTION -> R.string.rule_kind_subscription
    RuleKind.INCOME -> R.string.rule_kind_income
    RuleKind.LOAN_INSTALLMENT -> R.string.rule_kind_loan
    RuleKind.TRANSFER -> R.string.rule_kind_transfer
    RuleKind.OTHER -> R.string.rule_kind_other
}

fun frequencyLabel(f: Frequency): Int = when (f) {
    Frequency.ONCE -> R.string.freq_once
    Frequency.DAILY -> R.string.freq_daily
    Frequency.WEEKLY -> R.string.freq_weekly
    Frequency.MONTHLY -> R.string.freq_monthly
    Frequency.YEARLY -> R.string.freq_yearly
}
