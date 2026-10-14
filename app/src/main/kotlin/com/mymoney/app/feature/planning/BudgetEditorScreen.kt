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
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.mymoney.app.R
import com.mymoney.app.navigation.BudgetEditorRoute
import com.mymoney.app.ui.common.BackTopBar
import com.mymoney.app.ui.common.SaveAction
import com.mymoney.app.ui.common.DateField
import com.mymoney.app.ui.common.OptionalDateField
import com.mymoney.app.ui.common.categoryLabel
import com.mymoney.app.ui.common.errorMessage
import com.mymoney.core.designsystem.component.AmountField
import com.mymoney.core.designsystem.component.LoadingSkeleton
import com.mymoney.core.designsystem.theme.MyMoneyTheme
import com.mymoney.core.domain.common.AmountProblem
import com.mymoney.core.domain.common.DomainError
import com.mymoney.core.domain.common.Outcome
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.model.Budget
import com.mymoney.core.domain.model.BudgetPeriodType
import com.mymoney.core.domain.model.BudgetScope
import com.mymoney.core.domain.model.Category
import com.mymoney.core.domain.model.CategoryType
import com.mymoney.core.domain.model.RolloverMode
import com.mymoney.core.domain.money.AmountParser
import com.mymoney.core.domain.repository.BudgetRepository
import com.mymoney.core.domain.repository.CategoryRepository
import com.mymoney.core.domain.settings.AppSettings
import com.mymoney.core.domain.settings.SettingsRepository
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

data class BudgetEditorState(
    val loading: Boolean = true,
    val original: Budget? = null,
    val name: String = "",
    val periodType: BudgetPeriodType = BudgetPeriodType.MONTHLY,
    val amount: String = "",
    val scope: BudgetScope = BudgetScope.TOTAL,
    val categoryIds: Set<String> = emptySet(),
    val rollover: RolloverMode = RolloverMode.NONE,
    val thresholds: Set<Int> = setOf(50, 80, 100),
    val includePending: Boolean = false,
    val startDate: LocalDate = LocalDate.now(),
    val endDate: LocalDate? = null,
    val categories: List<Category> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val today: LocalDate = LocalDate.now(),
    val error: DomainError? = null,
    val done: Boolean = false,
)

@HiltViewModel
class BudgetEditorViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val budgets: BudgetRepository,
    categories: CategoryRepository,
    private val settings: SettingsRepository,
    private val currencies: com.mymoney.core.domain.repository.CurrencyRepository,
    clock: TimeProvider,
) : ViewModel() {
    private var digitsOf: Map<String, Int> = emptyMap()
    private val id = handle.toRoute<BudgetEditorRoute>().budgetId
    private val _s = MutableStateFlow(BudgetEditorState())
    val state: StateFlow<BudgetEditorState> = _s.asStateFlow()

    init {
        viewModelScope.launch {
            val st = settings.settings.first()
            digitsOf = currencies.getAll().associate { it.code to it.minorDigits }
            val cats = categories.getAll().filter { it.type == CategoryType.EXPENSE && !it.isArchived && it.parentId == null }
            val b = id?.let { budgets.get(it) }
            val today = clock.today()
            _s.value = if (b == null) {
                BudgetEditorState(loading = false, categories = cats, settings = st, today = today, startDate = today)
            } else {
                BudgetEditorState(
                    loading = false, original = b, name = b.name, periodType = b.periodType, amount = display(b.amountMinor, b.currency, st),
                    scope = b.scope, categoryIds = b.categoryIds, rollover = b.rollover, thresholds = b.alertThresholds.toSet(),
                    includePending = b.includePending, startDate = b.startDate, endDate = b.endDate, categories = cats, settings = st, today = today,
                )
            }
        }
    }

    fun update(f: (BudgetEditorState) -> BudgetEditorState) = _s.update { f(it).copy(error = null) }

    fun save() {
        val s = _s.value
        val currency = s.original?.currency ?: s.settings.baseCurrency
        val digits = digitsOf[currency] ?: 2
        val amount = when (val r = AmountParser.parse(s.amount, currency, digits, s.settings.irrUnit)) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> { _s.update { it.copy(error = r.error) }; return }
        }
        if (s.scope == BudgetScope.CATEGORIES && s.categoryIds.isEmpty()) { _s.update { it.copy(error = DomainError.Required("category")) }; return }
        if (s.periodType == BudgetPeriodType.CUSTOM && s.endDate == null) { _s.update { it.copy(error = DomainError.Required("end")) }; return }
        if (s.thresholds.isEmpty()) { _s.update { it.copy(error = DomainError.InvalidAmount(AmountProblem.ZERO)) }; return }
        val budget = (s.original ?: Budget("", "", s.periodType, s.settings.calendar, s.startDate, amountMinor = amount, currency = currency)).copy(
            name = s.name, periodType = s.periodType, startDate = s.startDate, endDate = if (s.periodType == BudgetPeriodType.CUSTOM) s.endDate else null,
            amountMinor = amount, scope = s.scope, categoryIds = if (s.scope == BudgetScope.CATEGORIES) s.categoryIds else emptySet(),
            rollover = s.rollover, alertThresholds = s.thresholds.sorted(), includePending = s.includePending,
        )
        viewModelScope.launch {
            when (val r = budgets.save(budget)) {
                is Outcome.Success -> _s.update { it.copy(done = true) }
                is Outcome.Failure -> _s.update { it.copy(error = r.error) }
            }
        }
    }

    fun archive() = viewModelScope.launch { id?.let { budgets.setArchived(it, true) }; _s.update { it.copy(done = true) } }
    fun delete() = viewModelScope.launch { id?.let { budgets.delete(it) }; _s.update { it.copy(done = true) } }

    private fun display(minor: Long, currency: String, st: AppSettings): String {
        val digits = digitsOf[currency] ?: 2
        var v = BigDecimal.valueOf(minor, digits)
        if (currency == "IRR" && st.irrUnit == com.mymoney.core.domain.settings.IrrUnit.TOMAN) v = v.movePointLeft(1)
        return v.stripTrailingZeros().toPlainString()
    }
}

@Composable
fun BudgetEditorScreen(onDone: () -> Unit, viewModel: BudgetEditorViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.done) { if (s.done) onDone() }
    val spacing = MyMoneyTheme.spacing
    Scaffold(topBar = {
        BackTopBar(stringResource(if (s.original == null) R.string.budget_new else R.string.budget_edit), onDone) {
            if (s.original != null) {
                IconButton(onClick = viewModel::archive) { Icon(Icons.Outlined.Archive, stringResource(R.string.acc_archive)) }
                IconButton(onClick = viewModel::delete) { Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete)) }
            }
            if (!s.loading) SaveAction(s.name.isNotBlank(), viewModel::save)
        }
    }) { padding ->
        if (s.loading) { LoadingSkeleton(Modifier.padding(padding)); return@Scaffold }
        val currency = s.original?.currency ?: s.settings.baseCurrency
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(spacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(spacing.m),
        ) {
            OutlinedTextField(s.name, { v -> viewModel.update { it.copy(name = v.take(60)) } }, label = { Text(stringResource(R.string.cat_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            AmountField(s.amount, { v -> viewModel.update { it.copy(amount = v) } }, currency, stringResource(R.string.budget_limit), Modifier.fillMaxWidth())
            Text(stringResource(R.string.budget_period), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                BudgetPeriodType.entries.forEach { p ->
                    FilterChip(s.periodType == p, { viewModel.update { it.copy(periodType = p) } }, {
                        Text(stringResource(when (p) {
                            BudgetPeriodType.WEEKLY -> R.string.freq_weekly
                            BudgetPeriodType.MONTHLY -> R.string.freq_monthly
                            BudgetPeriodType.YEARLY -> R.string.freq_yearly
                            BudgetPeriodType.CUSTOM -> R.string.budget_custom
                        }))
                    })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                DateField(stringResource(R.string.acc_start_date), s.startDate, s.today, { d -> viewModel.update { it.copy(startDate = d) } }, Modifier.weight(1f))
                if (s.periodType == BudgetPeriodType.CUSTOM) {
                    OptionalDateField(stringResource(R.string.tx_filter_to), s.endDate, s.today, { d -> viewModel.update { it.copy(endDate = d) } }, Modifier.weight(1f))
                }
            }
            Text(stringResource(R.string.budget_scope), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                FilterChip(s.scope == BudgetScope.TOTAL, { viewModel.update { it.copy(scope = BudgetScope.TOTAL) } }, { Text(stringResource(R.string.budget_scope_total)) })
                FilterChip(s.scope == BudgetScope.CATEGORIES, { viewModel.update { it.copy(scope = BudgetScope.CATEGORIES) } }, { Text(stringResource(R.string.budget_scope_categories)) })
            }
            if (s.scope == BudgetScope.CATEGORIES) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    s.categories.forEach { c ->
                        FilterChip(c.id in s.categoryIds, {
                            viewModel.update { st -> st.copy(categoryIds = if (c.id in st.categoryIds) st.categoryIds - c.id else st.categoryIds + c.id) }
                        }, { Text(categoryLabel(c)) })
                    }
                }
                Text(stringResource(R.string.budget_sub_included), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(stringResource(R.string.budget_rollover), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                RolloverMode.entries.forEach { m ->
                    FilterChip(s.rollover == m, { viewModel.update { it.copy(rollover = m) } }, {
                        Text(stringResource(when (m) {
                            RolloverMode.NONE -> R.string.budget_rollover_none
                            RolloverMode.CARRY_UNUSED -> R.string.budget_rollover_unused
                            RolloverMode.CARRY_ALL -> R.string.budget_rollover_all
                        }))
                    })
                }
            }
            Text(stringResource(R.string.budget_alerts), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                listOf(50, 70, 80, 90, 100, 120).forEach { t ->
                    FilterChip(t in s.thresholds, {
                        viewModel.update { st -> st.copy(thresholds = if (t in st.thresholds) st.thresholds - t else st.thresholds + t) }
                    }, { Text(com.mymoney.core.domain.money.Digits.localize("$t%", s.settings.digits)) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.budget_include_pending), Modifier.weight(1f))
                Switch(s.includePending, { v -> viewModel.update { it.copy(includePending = v) } })
            }
            Text(stringResource(R.string.budget_rules_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            s.error?.let { Text(errorMessage(it), color = MaterialTheme.colorScheme.error) }
            Button(onClick = viewModel::save, enabled = s.name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_save)) }
        }
    }
}
