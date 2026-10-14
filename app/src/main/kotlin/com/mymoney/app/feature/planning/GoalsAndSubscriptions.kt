@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.mymoney.app.feature.planning

import androidx.compose.foundation.clickable
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
import com.mymoney.app.data.FinanceSnapshotProvider
import com.mymoney.app.navigation.GoalDetailRoute
import com.mymoney.app.navigation.GoalEditorRoute
import com.mymoney.app.ui.common.BackTopBar
import com.mymoney.app.ui.common.SaveAction
import com.mymoney.app.ui.common.ConfirmDialog
import com.mymoney.app.ui.common.OptionalDateField
import com.mymoney.app.ui.common.editableAmount
import com.mymoney.app.ui.common.errorMessage
import com.mymoney.app.ui.common.parseAmount
import com.mymoney.app.ui.common.parseAmountOrNull
import com.mymoney.core.designsystem.component.AmountColor
import com.mymoney.core.designsystem.component.AmountField
import com.mymoney.core.designsystem.component.EstimateNote
import com.mymoney.core.designsystem.component.KpiValue
import com.mymoney.core.designsystem.component.LoadingSkeleton
import com.mymoney.core.designsystem.component.MoneyText
import com.mymoney.core.designsystem.component.ProgressBar
import com.mymoney.core.designsystem.component.SectionCard
import com.mymoney.core.designsystem.format.Formats
import com.mymoney.core.designsystem.format.LocalFormats
import com.mymoney.core.designsystem.theme.MyMoneyTheme
import com.mymoney.core.domain.calc.GoalProgress
import com.mymoney.core.domain.calc.Recurrence
import com.mymoney.core.domain.common.DomainError
import com.mymoney.core.domain.common.Outcome
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.model.Account
import com.mymoney.core.domain.model.GoalContribution
import com.mymoney.core.domain.model.GoalMode
import com.mymoney.core.domain.model.GoalStatus
import com.mymoney.core.domain.model.RuleKind
import com.mymoney.core.domain.model.RuleStatus
import com.mymoney.core.domain.model.SavingsGoal
import com.mymoney.core.domain.repository.AccountRepository
import com.mymoney.core.domain.repository.GoalRepository
import com.mymoney.core.domain.settings.SettingsRepository
import com.mymoney.core.domain.usecase.GoalAllocationUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ---------------- Subscriptions (docs/01 §14, FBR-35) ----------------

@Composable
fun SubscriptionsScreen(onBack: () -> Unit, onOpenRule: (String?) -> Unit, viewModel: PlanningViewModel = hiltViewModel()) {
    val st by viewModel.state.collectAsStateWithLifecycle()
    val formats = LocalFormats.current
    Scaffold(topBar = { BackTopBar(stringResource(R.string.sub_title), onBack) }) { padding ->
        val s = st.snapshot
        if (s == null) { LoadingSkeleton(Modifier.padding(padding)); return@Scaffold }
        val subs = s.rules.filter { it.kind == RuleKind.SUBSCRIPTION }.sortedWith(compareBy({ it.status != RuleStatus.ACTIVE }, { -Recurrence.monthlyEquivalent(it) }))
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            item {
                SectionCard {
                    Row {
                        KpiValue(stringResource(R.string.sub_per_month), st.subscriptionsMonthlyBase, s.base, Modifier.weight(1f))
                        KpiValue(stringResource(R.string.sub_per_year), st.subscriptionsMonthlyBase * 12, s.base, Modifier.weight(1f))
                    }
                    Text(stringResource(R.string.sub_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { TextButton(onClick = { onOpenRule(null) }) { Text(stringResource(R.string.sub_add)) } }
            items(subs, key = { it.id }) { r ->
                val amount = Recurrence.amountOn(r, s.today)
                val next = Recurrence.dueDates(r, com.mymoney.core.domain.calendar.DateRange(s.today, s.today.plusYears(2))).firstOrNull()
                ListItem(
                    modifier = Modifier.clickable { onOpenRule(r.id) },
                    headlineContent = { Text(r.name) },
                    supportingContent = {
                        Text(
                            stringResource(frequencyLabel(r.frequency)) + " · " +
                                (next?.let { stringResource(R.string.sub_renews, formats.dates.medium(it)) } ?: stringResource(R.string.rule_ended)) +
                                if (r.status == RuleStatus.PAUSED) " · " + stringResource(R.string.rule_paused) else "",
                        )
                    },
                    trailingContent = {
                        Column(horizontalAlignment = Alignment.End) {
                            MoneyText(amount, r.currency)
                            Row {
                                MoneyText(Recurrence.monthlyEquivalent(r, amount), r.currency, style = MaterialTheme.typography.bodySmall)
                                Text(" " + stringResource(R.string.sub_month_suffix), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    },
                )
            }
        }
    }
}

// ---------------- Goals ----------------

data class GoalDetailState(
    val progress: GoalProgress? = null,
    val contributions: List<GoalContribution> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val error: DomainError? = null,
    val deleted: Boolean = false,
)

@HiltViewModel
class GoalDetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    provider: FinanceSnapshotProvider,
    private val goals: GoalRepository,
    private val allocate: GoalAllocationUseCase,
    private val clock: TimeProvider,
) : ViewModel() {
    private val goalId = handle.toRoute<GoalDetailRoute>().goalId
    private val error = MutableStateFlow<DomainError?>(null)
    private val deleted = MutableStateFlow(false)
    val today: LocalDate = clock.today()

    val state: StateFlow<GoalDetailState> = combine(provider.snapshot, error, deleted) { s, e, d ->
        GoalDetailState(
            progress = PlanningMath.goalProgress(s).firstOrNull { it.goal.id == goalId },
            contributions = s.contributions.filter { it.goalId == goalId }.sortedByDescending { it.date },
            accounts = s.accounts.map { it.account }.filter { !it.isArchived && !it.type.isObligation },
            error = e,
            deleted = d,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GoalDetailState())

    fun move(formats: Formats, text: String, accountId: String?, release: Boolean, onDone: () -> Unit) {
        val goal = state.value.progress?.goal ?: return
        val amount = when (val r = formats.parseAmount(text, goal.currency)) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> { error.value = r.error; return }
        }
        viewModelScope.launch {
            when (val r = allocate(goal.id, accountId, if (release) -amount else amount, clock.today(), null)) {
                is Outcome.Success -> { error.value = null; onDone() }
                is Outcome.Failure -> error.value = r.error
            }
        }
    }

    fun deleteContribution(id: String) = viewModelScope.launch { goals.deleteContribution(id) }
    fun markAchieved() = viewModelScope.launch { state.value.progress?.goal?.let { goals.save(it.copy(status = GoalStatus.ACHIEVED)) } }
    fun delete() = viewModelScope.launch { goals.delete(goalId); deleted.value = true }
    fun clearError() { error.value = null }
}

@Composable
fun GoalDetailScreen(onBack: () -> Unit, onEdit: (String) -> Unit, viewModel: GoalDetailViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val formats = LocalFormats.current
    var moving by rememberSaveable { mutableStateOf<Boolean?>(null) } // true = release
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(s.deleted) { if (s.deleted) onBack() }
    val p = s.progress
    Scaffold(topBar = {
        BackTopBar(p?.goal?.name.orEmpty(), onBack) {
            if (p != null) {
                IconButton(onClick = { onEdit(p.goal.id) }) { Icon(Icons.Outlined.Edit, stringResource(R.string.action_edit)) }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete)) }
            }
        }
    }) { padding ->
        if (p == null) { LoadingSkeleton(Modifier.padding(padding)); return@Scaffold }
        val cur = p.goal.currency
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(MyMoneyTheme.spacing.m)) {
            item {
                SectionCard(containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
                    MoneyText(p.currentMinor, cur, style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.goal_of_target, formats.money.format(p.goal.targetMinor, cur)))
                    ProgressBar(p.fraction, Modifier.padding(vertical = 8.dp), warnAt = 2f)
                    Row {
                        KpiValue(stringResource(R.string.goal_remaining), p.remainingMinor, cur, Modifier.weight(1f))
                        p.requiredPerMonthMinor?.let { KpiValue(stringResource(R.string.goal_needed_monthly), it, cur, Modifier.weight(1f)) }
                    }
                    p.goal.targetDate?.let { Text(stringResource(R.string.goal_target_date, formats.dates.medium(it)), style = MaterialTheme.typography.bodySmall) }
                    p.projectedDate?.let {
                        EstimateNote(stringResource(R.string.goal_projection, formats.dates.monthYear(it), formats.money.format(p.averagePerMonthMinor ?: 0, cur)))
                    }
                    if (p.goal.mode == GoalMode.LINKED_ACCOUNTS) Text(stringResource(R.string.goal_linked_note), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (p.goal.mode == GoalMode.ALLOCATION) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(MyMoneyTheme.spacing.s)) {
                        Button(onClick = { moving = false }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.goal_allocate)) }
                        OutlinedButton(onClick = { moving = true }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.goal_release)) }
                    }
                    Text(stringResource(R.string.goal_virtual_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(s.contributions, key = { it.id }) { c ->
                    ListItem(
                        headlineContent = { MoneyText(c.amountMinor, cur, color = AmountColor.SIGNED) },
                        supportingContent = { Text(formats.dates.medium(c.date) + (c.accountId?.let { id -> " · " + (s.accounts.firstOrNull { it.id == id }?.name ?: "") } ?: "")) },
                        trailingContent = { IconButton(onClick = { viewModel.deleteContribution(c.id) }) { Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete)) } },
                    )
                }
            }
            if (p.goal.status == GoalStatus.ACTIVE && p.remainingMinor == 0L) {
                item { TextButton(onClick = viewModel::markAchieved) { Text(stringResource(R.string.goal_mark_achieved)) } }
            }
        }
    }
    moving?.let { release ->
        var amount by rememberSaveable { mutableStateOf("") }
        var accountId by rememberSaveable { mutableStateOf(p?.goal?.accountIds?.firstOrNull() ?: s.accounts.firstOrNull()?.id) }
        AlertDialog(
            onDismissRequest = { moving = null; viewModel.clearError() },
            title = { Text(stringResource(if (release) R.string.goal_release else R.string.goal_allocate)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AmountField(amount, { amount = it }, p?.goal?.currency ?: formats.baseCurrency, stringResource(R.string.editor_amount), Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        s.accounts.forEach { a -> FilterChip(accountId == a.id, { accountId = a.id }, { Text(a.name) }) }
                    }
                    s.error?.let { Text(errorMessage(it), color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.move(formats, amount, accountId, release) { moving = null } }) { Text(stringResource(R.string.action_confirm)) } },
            dismissButton = { TextButton(onClick = { moving = null; viewModel.clearError() }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(stringResource(R.string.action_delete), stringResource(R.string.goal_delete_confirm), stringResource(R.string.action_delete),
            { confirmDelete = false; viewModel.delete() }, { confirmDelete = false }, destructive = true)
    }
}

data class GoalEditorState(
    val loading: Boolean = true,
    val original: SavingsGoal? = null,
    val name: String = "",
    val target: String = "",
    val currency: String = "IRR",
    val targetDate: LocalDate? = null,
    val priority: Int = 2,
    val mode: GoalMode = GoalMode.ALLOCATION,
    val accountIds: Set<String> = emptySet(),
    val accounts: List<Account> = emptyList(),
    val today: LocalDate = LocalDate.now(),
    val error: DomainError? = null,
    val savedId: String? = null,
)

@HiltViewModel
class GoalEditorViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val goals: GoalRepository,
    accounts: AccountRepository,
    settings: SettingsRepository,
    clock: TimeProvider,
) : ViewModel() {
    private val id = handle.toRoute<GoalEditorRoute>().goalId
    private val _s = MutableStateFlow(GoalEditorState())
    val state: StateFlow<GoalEditorState> = _s.asStateFlow()
    private val baseFlow = settings.settings.map { it.baseCurrency }

    init {
        viewModelScope.launch {
            val accs = accounts.getAll().filter { !it.isArchived && !it.type.isObligation }
            val g = id?.let { goals.get(it) }
            _s.value = if (g == null) {
                GoalEditorState(loading = false, currency = baseFlow.first(), accounts = accs, today = clock.today())
            } else {
                GoalEditorState(false, g, g.name, "", g.currency, g.targetDate, g.priority, g.mode, g.accountIds, accs, clock.today())
            }
        }
    }

    fun prefill(formats: Formats) {
        val g = _s.value.original ?: return
        if (_s.value.target.isEmpty()) _s.update { it.copy(target = formats.editableAmount(g.targetMinor, g.currency)) }
    }

    fun update(f: (GoalEditorState) -> GoalEditorState) = _s.update { f(it).copy(error = null) }

    fun save(formats: Formats) {
        val s = _s.value
        val target = formats.parseAmountOrNull(s.target, s.currency) ?: run { _s.update { it.copy(error = DomainError.Required("target")) }; return }
        val goal = (s.original ?: SavingsGoal("", "", target, s.currency)).copy(
            name = s.name, targetMinor = target, targetDate = s.targetDate, priority = s.priority, mode = s.mode,
            accountIds = if (s.mode == GoalMode.LINKED_ACCOUNTS) s.accountIds else emptySet(),
        )
        viewModelScope.launch {
            when (val r = goals.save(goal)) {
                is Outcome.Success -> _s.update { it.copy(savedId = goal.id.ifBlank { "new" }) }
                is Outcome.Failure -> _s.update { it.copy(error = r.error) }
            }
        }
    }
}

@Composable
fun GoalEditorScreen(onDone: () -> Unit, viewModel: GoalEditorViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val formats = LocalFormats.current
    LaunchedEffect(s.savedId) { if (s.savedId != null) onDone() }
    LaunchedEffect(s.loading) { if (!s.loading) viewModel.prefill(formats) }
    val spacing = MyMoneyTheme.spacing
    Scaffold(topBar = { BackTopBar(stringResource(if (s.original == null) R.string.goal_new else R.string.goal_edit), onDone) {
        SaveAction(s.name.isNotBlank()) { viewModel.save(formats) }
    } }) { padding ->
        if (s.loading) { LoadingSkeleton(Modifier.padding(padding)); return@Scaffold }
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(spacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(spacing.m),
        ) {
            OutlinedTextField(s.name, { v -> viewModel.update { it.copy(name = v.take(60)) } }, label = { Text(stringResource(R.string.cat_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            AmountField(s.target, { v -> viewModel.update { it.copy(target = v) } }, s.currency, stringResource(R.string.goal_target), Modifier.fillMaxWidth())
            OptionalDateField(stringResource(R.string.goal_target_date_label), s.targetDate, s.today, { d -> viewModel.update { it.copy(targetDate = d) } }, Modifier.fillMaxWidth())
            Text(stringResource(R.string.goal_priority), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                listOf(1 to R.string.goal_priority_high, 2 to R.string.goal_priority_normal, 3 to R.string.goal_priority_low).forEach { (p, label) ->
                    FilterChip(s.priority == p, { viewModel.update { it.copy(priority = p) } }, { Text(stringResource(label)) })
                }
            }
            Text(stringResource(R.string.goal_mode), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                FilterChip(s.mode == GoalMode.ALLOCATION, { viewModel.update { it.copy(mode = GoalMode.ALLOCATION) } }, { Text(stringResource(R.string.goal_mode_allocation)) })
                FilterChip(s.mode == GoalMode.LINKED_ACCOUNTS, { viewModel.update { it.copy(mode = GoalMode.LINKED_ACCOUNTS) } }, { Text(stringResource(R.string.goal_mode_linked)) })
            }
            if (s.mode == GoalMode.LINKED_ACCOUNTS) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    s.accounts.forEach { a ->
                        FilterChip(a.id in s.accountIds, { viewModel.update { st -> st.copy(accountIds = if (a.id in st.accountIds) st.accountIds - a.id else st.accountIds + a.id) } }, { Text(a.name) })
                    }
                }
            }
            s.error?.let { Text(errorMessage(it), color = MaterialTheme.colorScheme.error) }
            Button(onClick = { viewModel.save(formats) }, enabled = s.name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_save)) }
        }
    }
}

