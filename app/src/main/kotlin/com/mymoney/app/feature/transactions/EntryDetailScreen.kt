package com.mymoney.app.feature.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import com.mymoney.app.navigation.EntryDetailRoute
import com.mymoney.app.ui.common.BackTopBar
import com.mymoney.app.ui.common.ConfirmDialog
import com.mymoney.app.ui.common.categoryLabel
import com.mymoney.app.ui.common.kindLabel
import com.mymoney.core.designsystem.component.AmountColor
import com.mymoney.core.designsystem.component.EmptyState
import com.mymoney.core.designsystem.component.LoadingSkeleton
import com.mymoney.core.designsystem.component.MoneyText
import com.mymoney.core.designsystem.component.SectionCard
import com.mymoney.core.designsystem.format.LocalFormats
import com.mymoney.core.designsystem.theme.MyMoneyTheme
import com.mymoney.core.domain.model.Account
import com.mymoney.core.domain.model.Category
import com.mymoney.core.domain.model.EntryGroup
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.model.EntryStatus
import com.mymoney.core.domain.model.Tag
import com.mymoney.core.domain.money.Digits
import com.mymoney.core.domain.repository.AccountRepository
import com.mymoney.core.domain.repository.CategoryRepository
import com.mymoney.core.domain.repository.LedgerRepository
import com.mymoney.core.domain.repository.TagRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class EntryDetailState(
    val loading: Boolean = true,
    val group: EntryGroup? = null,
    val accounts: Map<String, Account> = emptyMap(),
    val categories: Map<String, Category> = emptyMap(),
    val tags: Map<String, Tag> = emptyMap(),
    val refundedMinor: Long = 0,
    val deleted: Boolean = false,
    val blockedByRefunds: Boolean = false,
)

@HiltViewModel
class EntryDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val ledger: LedgerRepository,
    private val accounts: AccountRepository,
    private val categories: CategoryRepository,
    private val tags: TagRepository,
) : ViewModel() {
    private val groupId = savedStateHandle.toRoute<EntryDetailRoute>().groupId
    private val _state = MutableStateFlow(EntryDetailState())
    val state: StateFlow<EntryDetailState> = _state.asStateFlow()

    init {
        // Re-load whenever the ledger changes (e.g. after editing and coming back).
        viewModelScope.launch { ledger.observeChanges().collectLatest { load() } }
    }

    private suspend fun load() {
        val group = ledger.getGroup(groupId)
        val main = group?.entries?.firstOrNull { it.kind == EntryKind.EXPENSE }
        _state.value = EntryDetailState(
            loading = false,
            group = group,
            accounts = accounts.getAll().associateBy { it.id },
            categories = categories.getAll().associateBy { it.id },
            tags = tags.observeAll().first().associateBy { it.id },
            refundedMinor = main?.let { ledger.refundedTotal(it.id) } ?: 0,
            deleted = _state.value.deleted,
        )
    }

    fun delete() {
        viewModelScope.launch {
            val group = _state.value.group ?: return@launch
            val expenseIds = group.entries.filter { it.kind == EntryKind.EXPENSE }.map { it.id }
            val refunded = expenseIds.sumOf { ledger.refundedTotal(it) }
            if (refunded > 0) {
                // Deleting a refunded expense would leave refunds without their purchase (FBR-08).
                _state.value = _state.value.copy(blockedByRefunds = true)
                return@launch
            }
            ledger.softDeleteGroup(groupId)
            _state.value = _state.value.copy(deleted = true)
        }
    }

    fun dismissBlocked() {
        _state.value = _state.value.copy(blockedByRefunds = false)
    }
}

@Composable
fun EntryDetailScreen(
    onBack: () -> Unit,
    onEdit: (groupId: String) -> Unit,
    onCopy: (groupId: String) -> Unit,
    onRefund: (entryId: String) -> Unit,
    viewModel: EntryDetailViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(s.deleted) { if (s.deleted) onBack() }
    val group = s.group
    val editable = group != null && group.entries.none { it.kind == EntryKind.OPENING_BALANCE }

    Scaffold(
        topBar = {
            BackTopBar(stringResource(R.string.detail_title), onBack) {
                if (editable) {
                    IconButton(onClick = { onCopy(group!!.groupId) }) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.action_copy)) }
                    IconButton(onClick = { onEdit(group!!.groupId) }) { Icon(Icons.Outlined.Edit, stringResource(R.string.action_edit)) }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete)) }
                }
            }
        },
    ) { padding ->
        when {
            s.loading -> LoadingSkeleton(Modifier.padding(padding))
            group == null -> EmptyState(Icons.Outlined.Delete, stringResource(R.string.detail_missing), "", Modifier.padding(padding))
            else -> DetailBody(s, group, onRefund, Modifier.padding(padding))
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.action_delete),
            message = stringResource(R.string.detail_delete_confirm),
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = { confirmDelete = false; viewModel.delete() },
            onDismiss = { confirmDelete = false },
        )
    }
    if (s.blockedByRefunds) {
        ConfirmDialog(
            title = stringResource(R.string.action_delete),
            message = stringResource(R.string.detail_delete_has_refunds),
            confirmLabel = stringResource(R.string.action_ok),
            onConfirm = viewModel::dismissBlocked,
            onDismiss = viewModel::dismissBlocked,
        )
    }
}

@Composable
private fun DetailBody(s: EntryDetailState, group: EntryGroup, onRefund: (String) -> Unit, modifier: Modifier) {
    val formats = LocalFormats.current
    val spacing = MyMoneyTheme.spacing
    val main = group.entries.firstOrNull { it.kind == EntryKind.TRANSFER && it.amountMinor < 0 } ?: group.entries.first()
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(spacing.m),
    ) {
        SectionCard {
            Text(stringResource(kindLabel(main.kind)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            val currency = s.accounts[main.accountId]?.currency ?: formats.baseCurrency
            MoneyText(
                minor = main.amountMinor,
                currency = currency,
                style = MaterialTheme.typography.headlineMedium,
                color = if (main.kind == EntryKind.TRANSFER) AmountColor.TRANSFER else AmountColor.SIGNED,
            )
            Text(formats.dates.long(main.date) + (main.timeMinutes?.let { "  " + Digits.localize("%02d:%02d".format(it / 60, it % 60), formats.settings.digits) } ?: ""))
            if (main.status == EntryStatus.PENDING) Text(stringResource(R.string.tx_pending), color = MyMoneyTheme.finance.warning)
        }
        SectionCard {
            group.entries.sortedBy { it.amountMinor }.forEach { leg ->
                val acc = s.accounts[leg.accountId]
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(acc?.name ?: "?", style = MaterialTheme.typography.bodyLarge)
                        val cat = leg.categoryId?.let { s.categories[it] }
                        val label = when {
                            leg.splits.isNotEmpty() -> stringResource(R.string.tx_split_count, leg.splits.size)
                            cat != null -> categoryLabel(cat)
                            else -> stringResource(kindLabel(leg.kind))
                        }
                        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    MoneyText(leg.amountMinor, acc?.currency ?: formats.baseCurrency, color = AmountColor.SIGNED)
                }
                leg.splits.forEach { sp ->
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp)) {
                        Text(categoryLabel(s.categories[sp.categoryId]), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        MoneyText(sp.amountMinor, acc?.currency ?: formats.baseCurrency, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (!main.payee.isNullOrBlank() || !main.note.isNullOrBlank() || main.tagIds.isNotEmpty()) {
            SectionCard {
                main.payee?.let { DetailLine(stringResource(R.string.editor_payee), it) }
                main.note?.let { DetailLine(stringResource(R.string.editor_note), it) }
                if (main.tagIds.isNotEmpty()) DetailLine(stringResource(R.string.editor_tags), main.tagIds.mapNotNull { s.tags[it]?.name }.joinToString("، "))
                main.costType?.let { DetailLine(stringResource(R.string.editor_cost_type), stringResource(if (it.name == "FIXED") R.string.editor_fixed else R.string.editor_variable)) }
            }
        }
        val expense = group.entries.singleOrNull { it.kind == EntryKind.EXPENSE && group.entries.size == 1 }
        if (expense != null) {
            val currency = s.accounts[expense.accountId]?.currency ?: formats.baseCurrency
            if (s.refundedMinor > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.detail_refunded) + "  ")
                    MoneyText(s.refundedMinor, currency, color = AmountColor.INCOME)
                }
            }
            if (s.refundedMinor < -expense.amountMinor) {
                OutlinedButton(onClick = { onRefund(expense.id) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Undo, null)
                    Text("  " + stringResource(R.string.detail_refund_action))
                }
            }
        }
        HorizontalDivider()
        Text(
            stringResource(R.string.detail_created, formats.dates.medium(java.time.Instant.ofEpochMilli(main.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate())),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

