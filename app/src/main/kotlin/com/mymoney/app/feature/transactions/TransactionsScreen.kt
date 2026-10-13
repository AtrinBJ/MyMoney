package com.mymoney.app.feature.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.mymoney.app.R
import com.mymoney.app.ui.common.OptionalDateField
import com.mymoney.app.ui.common.EntryRowItem
import com.mymoney.app.ui.common.categoryLabel
import com.mymoney.app.ui.common.kindLabel
import com.mymoney.core.data.repository.EntryFilter
import com.mymoney.core.designsystem.component.EmptyState
import com.mymoney.core.designsystem.component.LoadingSkeleton
import com.mymoney.core.designsystem.format.LocalFormats
import com.mymoney.core.designsystem.theme.MyMoneyTheme
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.model.EntryStatus
import java.time.LocalDate

/**
 * Transaction list for the Transactions tab (all accounts) and for an account's history ([fixedAccountId]).
 */
@Composable
fun TransactionsScreen(
    contentPadding: PaddingValues,
    snackbar: SnackbarHostState,
    today: LocalDate,
    onOpen: (groupId: String) -> Unit,
    fixedAccountId: String? = null,
    showSearch: Boolean = true,
    viewModel: TransactionsViewModel = hiltViewModel(key = "tx-${fixedAccountId ?: "all"}"),
) {
    LaunchedEffect(fixedAccountId) { if (fixedAccountId != null) viewModel.setAccount(fixedAccountId) }
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val items = viewModel.items.collectAsLazyPagingItems()
    val formats = LocalFormats.current
    var showFilters by rememberSaveable { mutableStateOf(false) }

    val deletedMsg = stringResource(R.string.tx_deleted)
    val undo = stringResource(R.string.action_undo)
    LaunchedEffect(Unit) {
        viewModel.events.collect { e ->
            if (e is TxEvent.Deleted) {
                val r = snackbar.showSnackbar(deletedMsg, undo, duration = SnackbarDuration.Long)
                if (r == SnackbarResult.ActionPerformed) viewModel.undoDelete(e.groupId)
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        if (showSearch) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = filter.text.orEmpty(),
                    onValueChange = viewModel::setText,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    placeholder = { Text(stringResource(R.string.tx_search_hint), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { showFilters = true }) {
                    BadgedBox(badge = { if (filter.copy(text = null).isActive) Badge() }) {
                        Icon(Icons.Outlined.FilterList, contentDescription = stringResource(R.string.action_filter))
                    }
                }
            }
        }
        val refreshing = items.loadState.refresh is LoadState.Loading && items.itemCount == 0
        when {
            refreshing -> LoadingSkeleton()
            items.itemCount == 0 -> EmptyState(
                icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                title = stringResource(if (filter.isActive) R.string.tx_no_results_title else R.string.tx_empty_title),
                message = stringResource(if (filter.isActive) R.string.tx_no_results_message else R.string.tx_empty_message),
            )
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                items(items.itemCount, key = items.itemKey { it.key() }, contentType = { i -> items.peek(i)?.let { it::class.simpleName } }) { i ->
                    when (val item = items[i]) {
                        is TxListItem.Header -> Text(
                            formats.dates.long(item.date),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                        )
                        is TxListItem.Row -> EntryRowItem(item.row, onClick = { onOpen(item.row.groupId) })
                        null -> Spacer(Modifier.height(72.dp))
                    }
                }
            }
        }
    }

    if (showFilters) {
        FilterSheet(
            current = filter,
            viewModel = viewModel,
            today = today,
            showAccount = fixedAccountId == null,
            onApply = { viewModel.setFilter(it.copy(accountId = fixedAccountId ?: it.accountId)); showFilters = false },
            onDismiss = { showFilters = false },
        )
    }
}

private fun TxListItem.key(): String = when (this) {
    is TxListItem.Header -> "h-${date.toEpochDay()}"
    is TxListItem.Row -> "r-${row.id}"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(
    current: EntryFilter,
    viewModel: TransactionsViewModel,
    today: LocalDate,
    showAccount: Boolean,
    onApply: (EntryFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    var f by remember { mutableStateOf(current) }
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val spacing = MyMoneyTheme.spacing
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.l, vertical = spacing.s),
            verticalArrangement = Arrangement.spacedBy(spacing.m),
        ) {
            Text(stringResource(R.string.tx_filter_title), style = MaterialTheme.typography.titleMedium)

            Text(stringResource(R.string.tx_filter_kind), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                listOf(EntryKind.EXPENSE, EntryKind.INCOME, EntryKind.TRANSFER, EntryKind.REFUND, EntryKind.ADJUSTMENT).forEach { k ->
                    FilterChip(
                        selected = k in f.kinds,
                        onClick = { f = f.copy(kinds = if (k in f.kinds) f.kinds - k else f.kinds + k) },
                        label = { Text(stringResource(kindLabel(k))) },
                    )
                }
            }
            if (showAccount) {
                Text(stringResource(R.string.tx_filter_account), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                    accounts.filter { !it.type.isObligation }.forEach { a ->
                        FilterChip(
                            selected = f.accountId == a.id,
                            onClick = { f = f.copy(accountId = if (f.accountId == a.id) null else a.id) },
                            label = { Text(a.name) },
                        )
                    }
                }
            }
            Text(stringResource(R.string.tx_filter_category), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                categories.filter { !it.isArchived }.forEach { c ->
                    FilterChip(
                        selected = c.id in f.categoryIds,
                        onClick = {
                            // Selecting a parent includes its subcategories.
                            val ids = setOf(c.id) + categories.filter { it.parentId == c.id }.map { it.id }
                            f = f.copy(categoryIds = if (c.id in f.categoryIds) f.categoryIds - ids else f.categoryIds + ids)
                        },
                        label = { Text(categoryLabel(c)) },
                    )
                }
            }
            if (tags.isNotEmpty()) {
                Text(stringResource(R.string.editor_tags), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                    tags.forEach { t ->
                        FilterChip(selected = f.tagId == t.id, onClick = { f = f.copy(tagId = if (f.tagId == t.id) null else t.id) }, label = { Text(t.name) })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                OptionalDateField(stringResource(R.string.tx_filter_from), f.from, today, { f = f.copy(from = it) }, Modifier.weight(1f))
                OptionalDateField(stringResource(R.string.tx_filter_to), f.to, today, { f = f.copy(to = it) }, Modifier.weight(1f))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                FilterChip(f.status == EntryStatus.PENDING, { f = f.copy(status = if (f.status == EntryStatus.PENDING) null else EntryStatus.PENDING) }, { Text(stringResource(R.string.tx_pending)) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.s), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { f = EntryFilter(text = f.text) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_clear)) }
                Button(onClick = { onApply(f) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_apply)) }
            }
            Spacer(Modifier.height(spacing.xl))
        }
    }
}
