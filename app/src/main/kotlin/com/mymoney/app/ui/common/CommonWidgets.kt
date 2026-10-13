package com.mymoney.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mymoney.app.R
import com.mymoney.core.data.db.dao.EntryListRow
import com.mymoney.core.designsystem.component.AmountColor
import com.mymoney.core.designsystem.component.CalendarDatePickerDialog
import com.mymoney.core.designsystem.component.ColorKeys
import com.mymoney.core.designsystem.component.IconBadge
import com.mymoney.core.designsystem.component.IconKeys
import com.mymoney.core.designsystem.component.MoneyText
import com.mymoney.core.designsystem.format.LocalFormats
import com.mymoney.core.designsystem.theme.MyMoneyTheme
import com.mymoney.core.domain.model.Account
import com.mymoney.core.domain.model.Category
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.model.EntryStatus
import com.mymoney.core.domain.money.Digits
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
        },
        actions = { actions() },
    )
}

/** One row of the transaction list (docs/05). Transfers show "from → to"; pending rows are labeled. */
@Composable
fun EntryRowItem(row: EntryListRow, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val formats = LocalFormats.current
    val (icon, tint) = when (row.kind) {
        EntryKind.TRANSFER -> Icons.Filled.SwapHoriz to MyMoneyTheme.finance.transfer
        EntryKind.ADJUSTMENT, EntryKind.OPENING_BALANCE, EntryKind.WRITE_OFF -> Icons.Filled.Tune to MaterialTheme.colorScheme.outline
        EntryKind.ASSET_BUY, EntryKind.ASSET_SELL -> IconKeys.icon("chart") to MaterialTheme.colorScheme.secondary
        else -> IconKeys.icon(row.categoryIconKey) to ColorKeys.color(row.categoryColorKey)
    }
    val title = when {
        row.kind == EntryKind.TRANSFER -> stringResource(R.string.tx_transfer_to, row.accountName, row.counterpartAccountName ?: "?")
        row.splitCount > 0 -> stringResource(R.string.tx_split_count, row.splitCount)
        row.kind == EntryKind.ADJUSTMENT || row.kind == EntryKind.OPENING_BALANCE || row.kind == EntryKind.WRITE_OFF -> stringResource(kindLabel(row.kind))
        row.kind == EntryKind.ASSET_BUY || row.kind == EntryKind.ASSET_SELL -> stringResource(kindLabel(row.kind)) + (row.note?.let { " · $it" } ?: "")
        else -> categoryLabel(row.categorySystemKey, row.categoryName)
    }
    val subtitle = buildList {
        row.payee?.let { add(it) }
        if (row.kind != EntryKind.TRANSFER) add(row.accountName)
        if (row.kind == EntryKind.REFUND) add(stringResource(R.string.kind_refund))
        if (row.status == EntryStatus.PENDING) add(stringResource(R.string.tx_pending))
        if (row.payee == null) row.note?.let { add(it) }
    }.joinToString(" · ")
    val color = when (row.kind) {
        EntryKind.TRANSFER -> AmountColor.TRANSFER
        EntryKind.INCOME, EntryKind.REFUND -> AmountColor.INCOME
        EntryKind.EXPENSE -> AmountColor.EXPENSE
        else -> AmountColor.SIGNED
    }
    ListItem(
        modifier = modifier.clickable(role = Role.Button, onClick = onClick),
        leadingContent = { IconBadge(icon, tint) },
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (subtitle.isNotEmpty()) {
            { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } else {
            null
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                MoneyText(row.amountMinor, row.currency, color = color, style = MaterialTheme.typography.bodyLarge)
                row.timeMinutes?.let {
                    Text(
                        Digits.localize("%02d:%02d".format(it / 60, it % 60), formats.settings.digits),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

/** A read-only field that opens the calendar picker. */
@Composable
fun DateField(label: String, date: LocalDate, today: LocalDate, onDate: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    OptionalDateField(label, date, today, { if (it != null) onDate(it) }, modifier, clearable = false)
}

/** Date field that may be empty ("—"); used by filters. */
@Composable
fun OptionalDateField(
    label: String,
    date: LocalDate?,
    today: LocalDate,
    onDate: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier,
    clearable: Boolean = true,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val formats = LocalFormats.current
    PickerField(
        label = label,
        value = date?.let { formats.dates.medium(it) } ?: "—",
        icon = { Icon(Icons.Outlined.CalendarMonth, null) },
        onClick = { open = true },
        modifier = modifier,
    )
    if (open) {
        CalendarDatePickerDialog(
            initial = date ?: today,
            today = today,
            onDismiss = {
                open = false
                if (clearable) onDate(null)
            },
            onConfirm = { onDate(it); open = false },
            confirmLabel = stringResource(R.string.action_confirm),
            cancelLabel = stringResource(if (clearable) R.string.action_clear else R.string.action_cancel),
            todayLabel = stringResource(R.string.action_today),
            previousMonthLabel = stringResource(R.string.action_previous_month),
            nextMonthLabel = stringResource(R.string.action_next_month),
        )
    }
}

/** Time of day; tapping cycles through a simple hour/minute dialog. Optional (null = no time). */
@Composable
fun TimeField(label: String, minutes: Int?, onMinutes: (Int?) -> Unit, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    val formats = LocalFormats.current
    val shown = minutes?.let { Digits.localize("%02d:%02d".format(it / 60, it % 60), formats.settings.digits) } ?: "—"
    PickerField(label = label, value = shown, icon = { Icon(Icons.Outlined.Schedule, null) }, onClick = { open = true }, modifier = modifier)
    if (open) {
        var h by rememberSaveable { mutableStateOf(((minutes ?: 720) / 60).toString()) }
        var m by rememberSaveable { mutableStateOf(((minutes ?: 0) % 60).toString()) }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(h, { h = Digits.normalize(it).filter(Char::isDigit).take(2) }, label = { Text("HH") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(m, { m = Digits.normalize(it).filter(Char::isDigit).take(2) }, label = { Text("MM") }, modifier = Modifier.weight(1f), singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val hh = h.toIntOrNull()?.coerceIn(0, 23) ?: 0
                    val mm = m.toIntOrNull()?.coerceIn(0, 59) ?: 0
                    onMinutes(hh * 60 + mm)
                    open = false
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { onMinutes(null); open = false }) { Text(stringResource(R.string.action_clear)) }
            },
        )
    }
}

/** Looks like an outlined text field but opens a picker. */
@Composable
fun PickerField(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: (@Composable () -> Unit)? = null, isError: Boolean = false) {
    androidx.compose.foundation.layout.Box(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = icon,
            isError = isError,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // Transparent click layer: a read-only TextField swallows clicks otherwise.
        androidx.compose.foundation.layout.Box(
            Modifier
                .matchParentSize()
                .clickable(role = Role.Button, onClick = onClick),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountPickerSheet(accounts: List<Account>, onPick: (Account) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.editor_select_account),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn {
            items(accounts, key = { it.id }) { a ->
                ListItem(
                    modifier = Modifier.clickable { onPick(a) },
                    leadingContent = { IconBadge(a.iconKey?.let(IconKeys::icon) ?: IconKeys.forAccountType(a.type), ColorKeys.color(a.colorKey)) },
                    headlineContent = { Text(a.name) },
                    supportingContent = { Text(stringResource(accountTypeLabel(a.type)) + " · " + LocalFormats.current.currencyName(a.currency)) },
                )
            }
        }
        Spacer(Modifier.padding(bottom = 24.dp))
    }
}

/** Categories grouped parent → children; archived ones are hidden. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryPickerSheet(categories: List<Category>, onPick: (Category) -> Unit, onDismiss: () -> Unit) {
    val parents = categories.filter { it.parentId == null }
    val children = categories.filter { it.parentId != null }.groupBy { it.parentId }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.editor_select_category),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn {
            parents.forEach { p ->
                item(key = p.id) { CategoryRow(p, indent = false) { onPick(p) } }
                children[p.id].orEmpty().forEach { c ->
                    item(key = c.id) { CategoryRow(c, indent = true) { onPick(c) } }
                }
            }
        }
        Spacer(Modifier.padding(bottom = 24.dp))
    }
}

@Composable
private fun CategoryRow(c: Category, indent: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(start = if (indent) 32.dp else 0.dp),
        leadingContent = { IconBadge(IconKeys.icon(c.iconKey), ColorKeys.color(c.colorKey)) },
        headlineContent = { Text(categoryLabel(c)) },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun SpacerW(width: Int) = Spacer(Modifier.width(width.dp))

/** Icon + name + type line for account rows. */
@Composable
fun AccountRowContent(name: String, type: com.mymoney.core.domain.model.AccountType, iconKey: String?, colorKey: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconBadge(iconKey?.let(IconKeys::icon) ?: IconKeys.forAccountType(type), ColorKeys.color(colorKey))
        SpacerW(12)
        Column {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(accountTypeLabel(type)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
