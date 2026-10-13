@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.mymoney.app.feature.data

import android.content.Context
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoney.app.MyMoneyApp
import com.mymoney.app.R
import com.mymoney.app.ui.common.BackTopBar
import com.mymoney.app.ui.common.ConfirmDialog
import com.mymoney.app.ui.common.categoryLabel
import com.mymoney.app.ui.common.kindLabel
import com.mymoney.app.ui.common.systemCategoryName
import com.mymoney.core.data.backup.DataSummary
import com.mymoney.core.data.exchange.ExportFilter
import com.mymoney.core.data.exchange.ExportLabels
import com.mymoney.core.designsystem.component.EmptyState
import com.mymoney.core.designsystem.component.InfoBanner
import com.mymoney.core.designsystem.component.MoneyText
import com.mymoney.core.designsystem.component.SectionCard
import com.mymoney.core.designsystem.format.LocalFormats
import com.mymoney.core.designsystem.theme.MyMoneyTheme
import com.mymoney.core.domain.calendar.PeriodType
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.money.Digits
import java.time.LocalDate
import kotlinx.coroutines.launch

/** Opening a system picker sends the app to the background on purpose; do not ask for the PIN on return. */
internal fun Context.allowExternalScreen() {
    (applicationContext as? MyMoneyApp)?.lockManager?.allowNextBackground()
}

@Composable
private fun WorkStatus(work: Work, onDismiss: () -> Unit) {
    when (work) {
        Work.Idle -> Unit
        Work.Running -> LinearProgressIndicator(Modifier.fillMaxWidth())
        is Work.Done -> {
            val f = LocalFormats.current
            val args = work.args.map { if (it is Number) Digits.localize(it.toString(), f.settings.digits) else it }.toTypedArray()
            SectionCard(containerColor = MyMoneyTheme.finance.incomeContainer) {
                Text(stringResource(work.message, *args), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
            }
        }
        is Work.Failed -> InfoBanner(stringResource(work.message), actionLabel = stringResource(R.string.action_ok), onAction = onDismiss)
    }
}

@Composable
private fun ScreenColumn(title: String, onBack: () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val spacing = MyMoneyTheme.spacing
    Scaffold(topBar = { BackTopBar(title, onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(spacing.screenPadding, spacing.s, spacing.screenPadding, 32.dp),
            verticalArrangement = Arrangement.spacedBy(spacing.cardGap),
            content = content,
        )
    }
}

// ------------------------------------------------------------------------------------------------
// Backup & restore
// ------------------------------------------------------------------------------------------------

@Composable
fun BackupScreen(onBack: () -> Unit, viewModel: DataViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val work by viewModel.work.collectAsStateWithLifecycle()
    val restore by viewModel.restore.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val f = LocalFormats.current
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    val pwProblem = when {
        password.isEmpty() -> null
        password.length < 8 -> R.string.backup_pw_short
        password != confirm -> R.string.backup_pw_mismatch
        else -> null
    }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) viewModel.backup(uri, password.takeIf { it.isNotEmpty() })
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) viewModel.pickedRestoreFile(uri) }
    val busy = work == Work.Running

    ScreenColumn(stringResource(R.string.backup_title), onBack) {
        item(key = "status") { WorkStatus(work, viewModel::clearWork) }
        item(key = "create") {
            SectionCard(title = stringResource(R.string.backup_create)) {
                Text(stringResource(R.string.backup_explain), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    password, { password = it }, label = { Text(stringResource(R.string.backup_password)) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (password.isNotEmpty()) {
                    OutlinedTextField(
                        confirm, { confirm = it }, label = { Text(stringResource(R.string.backup_password_again)) },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = pwProblem != null, supportingText = pwProblem?.let { { Text(stringResource(it)) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.backup_pw_forget), style = MaterialTheme.typography.bodySmall, color = MyMoneyTheme.finance.warning)
                } else {
                    InfoBanner(stringResource(R.string.backup_unencrypted_warning), Modifier.padding(top = 8.dp))
                }
                Button(
                    onClick = {
                        context.allowExternalScreen()
                        create.launch("MyMoney-${viewModel.today()}.mmbak")
                    },
                    enabled = !busy && pwProblem == null,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Icon(if (password.isEmpty()) Icons.Outlined.LockOpen else Icons.Outlined.Lock, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (password.isEmpty()) R.string.backup_save_plain else R.string.backup_save_encrypted))
                }
            }
        }
        item(key = "restore") {
            SectionCard(title = stringResource(R.string.restore_title)) {
                when (val step = restore) {
                    RestoreStep.PickFile -> {
                        Text(stringResource(R.string.restore_explain), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { context.allowExternalScreen(); pick.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.padding(top = 8.dp)) {
                            Icon(Icons.Outlined.Restore, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.restore_choose))
                        }
                    }
                    is RestoreStep.Password -> {
                        var pw by remember(step) { mutableStateOf("") }
                        Text(stringResource(R.string.restore_needs_password), style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            pw, { pw = it }, label = { Text(stringResource(R.string.backup_password)) }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), isError = step.wrong,
                            supportingText = if (step.wrong) ({ Text(stringResource(R.string.restore_wrong_password)) }) else null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row {
                            TextButton(onClick = viewModel::cancelRestore) { Text(stringResource(R.string.action_cancel)) }
                            Button(onClick = { viewModel.submitPassword(step.uri, pw) }, enabled = pw.isNotEmpty() && !busy) { Text(stringResource(R.string.restore_open)) }
                        }
                    }
                    is RestoreStep.Confirm -> RestoreConfirm(step, busy, viewModel)
                }
            }
        }
        if (history.isNotEmpty()) {
            item(key = "history") {
                SectionCard(title = stringResource(R.string.backup_history)) {
                    history.take(10).forEach { r ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (r.encrypted) Icons.Outlined.Lock else Icons.Outlined.LockOpen, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(f.dates.medium(java.time.Instant.ofEpochMilli(r.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    stringResource(if (r.kind == "SAFETY") R.string.backup_kind_safety else R.string.backup_kind_manual) + " · " +
                                        Digits.localize(Formatter.formatShortFileSize(context, r.sizeBytes), f.settings.digits),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Text(stringResource(R.string.backup_history_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun RestoreConfirm(step: RestoreStep.Confirm, busy: Boolean, viewModel: DataViewModel) {
    val scope = rememberCoroutineScope()
    var pin by remember(step) { mutableStateOf("") }
    var pinWrong by remember(step) { mutableStateOf(false) }
    SummaryTable(step.ready.summary, step.current)
    InfoBanner(stringResource(R.string.restore_replace_warning), Modifier.padding(vertical = 8.dp))
    if (viewModel.pinSet) {
        OutlinedTextField(
            pin, { pin = it.filter(Char::isDigit).take(8) }, label = { Text(stringResource(R.string.pin_enter_current)) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            isError = pinWrong, supportingText = if (pinWrong) ({ Text(stringResource(R.string.lock_wrong)) }) else null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Row {
        TextButton(onClick = viewModel::cancelRestore) { Text(stringResource(R.string.action_cancel)) }
        Button(
            onClick = {
                scope.launch {
                    if (viewModel.checkPin(Digits.normalize(pin))) viewModel.confirmRestore(step.ready) else pinWrong = true
                }
            },
            enabled = !busy && (!viewModel.pinSet || pin.length >= 4),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) { Text(stringResource(R.string.restore_confirm)) }
    }
}

@Composable
private fun SummaryTable(backup: DataSummary, current: DataSummary) {
    val f = LocalFormats.current
    fun n(v: Int) = Digits.localize(v.toString(), f.settings.digits)
    fun range(s: DataSummary): String {
        val min = s.dateMin ?: return "—"
        val max = s.dateMax ?: min
        return f.dates.short(LocalDate.ofEpochDay(min)) + " – " + f.dates.short(LocalDate.ofEpochDay(max))
    }
    @Composable
    fun line(label: Int, a: String, b: String) {
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(stringResource(label), Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall)
            Text(a, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Text(b, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.weight(1.2f))
        Text(stringResource(R.string.restore_col_file), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
        Text(stringResource(R.string.restore_col_current), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
    }
    HorizontalDivider()
    line(R.string.restore_row_accounts, n(backup.accounts), n(current.accounts))
    line(R.string.restore_row_entries, n(backup.entries), n(current.entries))
    line(R.string.restore_row_attachments, n(backup.attachments), n(current.attachments))
    line(R.string.restore_row_range, range(backup), range(current))
}

// ------------------------------------------------------------------------------------------------
// Export
// ------------------------------------------------------------------------------------------------

enum class ExportPeriod { THIS_MONTH, LAST_MONTH, THIS_YEAR, ALL }

@Composable
fun ExportScreen(onBack: () -> Unit, viewModel: DataViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val work by viewModel.work.collectAsStateWithLifecycle()
    val accounts by viewModel.accountList.collectAsStateWithLifecycle()
    val f = LocalFormats.current
    var format by rememberSaveable { mutableStateOf(ExportFormat.CSV) }
    var period by rememberSaveable { mutableStateOf(ExportPeriod.THIS_MONTH) }
    var selected by rememberSaveable { mutableStateOf(setOf<String>()) }
    val today = viewModel.today()
    val range = when (period) {
        ExportPeriod.THIS_MONTH -> f.periods.rangeOf(PeriodType.MONTH, today)
        ExportPeriod.LAST_MONTH -> f.periods.previous(PeriodType.MONTH, f.periods.rangeOf(PeriodType.MONTH, today))
        ExportPeriod.THIS_YEAR -> f.periods.rangeOf(PeriodType.YEAR, today)
        ExportPeriod.ALL -> null
    }
    val kindLabels = EntryKind.entries.associate { it.name to stringResource(kindLabel(it)) }
    val categoryNames = com.mymoney.core.data.db.SeedData.categories.map { it.key }.associateWith { k -> systemCategoryName(k)?.let { stringResource(it) } }
    val uncategorized = stringResource(R.string.category_uncategorized)
    val labels = ExportLabels(
        category = { key, name -> name?.takeIf { it.isNotBlank() } ?: key?.let { categoryNames[it] } ?: uncategorized },
        kind = { kindLabels[it] ?: it },
        localDate = { f.dates.short(it) },
    )
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(
            when (format) {
                ExportFormat.CSV -> "text/csv"
                ExportFormat.JSON -> "application/json"
                ExportFormat.PDF -> "application/pdf"
            },
        ),
    ) { uri -> if (uri != null) viewModel.export(uri, format, ExportFilter(range?.start, range?.endInclusive, selected), labels) }

    ScreenColumn(stringResource(R.string.export_title), onBack) {
        item(key = "status") { WorkStatus(work, viewModel::clearWork) }
        item(key = "format") {
            SectionCard(title = stringResource(R.string.export_format)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ExportFormat.entries.forEachIndexed { i, x ->
                        SegmentedButton(format == x, { format = x }, SegmentedButtonDefaults.itemShape(i, ExportFormat.entries.size)) { Text(x.name) }
                    }
                }
                Text(
                    stringResource(
                        when (format) {
                            ExportFormat.CSV -> R.string.export_csv_desc
                            ExportFormat.JSON -> R.string.export_json_desc
                            ExportFormat.PDF -> R.string.export_pdf_desc
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        item(key = "period") {
            SectionCard(title = stringResource(R.string.export_period)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExportPeriod.entries.filter { format != ExportFormat.PDF || it != ExportPeriod.ALL }.forEach { p ->
                        FilterChip(
                            period == p, { period = p },
                            label = {
                                Text(
                                    stringResource(
                                        when (p) {
                                            ExportPeriod.THIS_MONTH -> R.string.period_this_month
                                            ExportPeriod.LAST_MONTH -> R.string.period_last_month
                                            ExportPeriod.THIS_YEAR -> R.string.period_this_year
                                            ExportPeriod.ALL -> R.string.export_all_time
                                        },
                                    ),
                                )
                            },
                        )
                    }
                }
                range?.let { Text(f.dates.medium(it.start) + " – " + f.dates.medium(it.endInclusive), style = MaterialTheme.typography.bodySmall) }
            }
        }
        item(key = "accounts") {
            SectionCard(title = stringResource(R.string.export_accounts)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected.isEmpty(), { selected = emptySet() }, label = { Text(stringResource(R.string.export_all_accounts)) })
                    accounts.forEach { a ->
                        val on = a.account.id in selected
                        FilterChip(on, { selected = if (on) selected - a.account.id else selected + a.account.id }, label = { Text(a.account.name) })
                    }
                }
            }
        }
        item(key = "go") {
            if (format == ExportFormat.CSV || format == ExportFormat.JSON) {
                InfoBanner(stringResource(R.string.export_privacy_note), Modifier.padding(bottom = 8.dp))
            }
            Button(
                onClick = {
                    context.allowExternalScreen()
                    val ext = format.name.lowercase()
                    launcher.launch("MyMoney-${range?.start ?: "all"}.$ext")
                },
                enabled = work != Work.Running,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.export_go)) }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Trash
// ------------------------------------------------------------------------------------------------

@Composable
fun TrashScreen(onBack: () -> Unit, viewModel: DataViewModel = hiltViewModel()) {
    val rows by viewModel.trashRows.collectAsStateWithLifecycle()
    val f = LocalFormats.current
    var confirmEmpty by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf<String?>(null) }
    val spacing = MyMoneyTheme.spacing
    Scaffold(
        topBar = {
            BackTopBar(stringResource(R.string.trash_title), onBack) {
                if (rows.isNotEmpty()) IconButton(onClick = { confirmEmpty = true }) { Icon(Icons.Outlined.DeleteSweep, stringResource(R.string.trash_empty)) }
            }
        },
    ) { padding ->
        if (rows.isEmpty()) {
            EmptyState(Icons.Outlined.DeleteSweep, stringResource(R.string.trash_none_title), stringResource(R.string.trash_none_message), Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(spacing.screenPadding, spacing.s, spacing.screenPadding, 32.dp),
            verticalArrangement = Arrangement.spacedBy(spacing.s),
        ) {
            item(key = "note") { Text(stringResource(R.string.trash_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(rows, key = { it.groupId }) { r ->
                val deletedOn = java.time.Instant.ofEpochMilli(r.deletedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                val daysLeft = (30 - (viewModel.today().toEpochDay() - deletedOn.toEpochDay())).coerceAtLeast(0)
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                r.payee ?: if (r.kind == EntryKind.TRANSFER) stringResource(R.string.kind_transfer) else categoryLabel(r.categorySystemKey, r.categoryName),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                f.dates.medium(LocalDate.ofEpochDay(r.date)) + " · " + r.accountName,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stringResource(R.string.trash_days_left, Digits.localize(daysLeft.toString(), f.settings.digits)),
                                style = MaterialTheme.typography.labelSmall, color = MyMoneyTheme.finance.warning,
                            )
                        }
                        MoneyText(r.amountMinor, r.currency)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { confirmDelete = r.groupId }) { Text(stringResource(R.string.trash_delete_forever)) }
                        TextButton(onClick = { viewModel.restoreFromTrash(r.groupId) }) { Text(stringResource(R.string.trash_restore)) }
                    }
                }
            }
        }
    }
    if (confirmEmpty) {
        ConfirmDialog(
            title = stringResource(R.string.trash_empty),
            message = stringResource(R.string.trash_empty_confirm),
            confirmLabel = stringResource(R.string.trash_empty),
            onConfirm = { viewModel.emptyTrash(); confirmEmpty = false },
            onDismiss = { confirmEmpty = false },
        )
    }
    confirmDelete?.let { id ->
        ConfirmDialog(
            title = stringResource(R.string.trash_delete_forever),
            message = stringResource(R.string.trash_delete_confirm),
            confirmLabel = stringResource(R.string.trash_delete_forever),
            onConfirm = { viewModel.deleteForever(id); confirmDelete = null },
            onDismiss = { confirmDelete = null },
        )
    }
}

// ------------------------------------------------------------------------------------------------
// Delete all data
// ------------------------------------------------------------------------------------------------

@Composable
fun DeleteAllScreen(onBack: () -> Unit, onBackup: () -> Unit, viewModel: DataViewModel = hiltViewModel()) {
    val work by viewModel.work.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val word = stringResource(R.string.wipe_word)
    var typed by rememberSaveable { mutableStateOf("") }
    var pin by rememberSaveable { mutableStateOf("") }
    var pinWrong by rememberSaveable { mutableStateOf(false) }
    ScreenColumn(stringResource(R.string.wipe_title), onBack) {
        item(key = "status") { WorkStatus(work, viewModel::clearWork) }
        item(key = "explain") {
            SectionCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.DeleteForever, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.wipe_title), style = MaterialTheme.typography.titleMedium)
                }
                Text(stringResource(R.string.wipe_explain), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                TextButton(onClick = onBackup) { Text(stringResource(R.string.wipe_backup_first)) }
            }
        }
        item(key = "confirm") {
            SectionCard {
                Text(stringResource(R.string.wipe_type, word), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(typed, { typed = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (viewModel.pinSet) {
                    OutlinedTextField(
                        pin, { pin = it.filter(Char::isDigit).take(8); pinWrong = false }, label = { Text(stringResource(R.string.pin_enter_current)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        isError = pinWrong, supportingText = if (pinWrong) ({ Text(stringResource(R.string.lock_wrong)) }) else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Button(
                    onClick = { scope.launch { if (viewModel.checkPin(Digits.normalize(pin))) viewModel.wipe() else pinWrong = true } },
                    enabled = typed.trim() == word && work != Work.Running && (!viewModel.pinSet || pin.length >= 4),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text(stringResource(R.string.wipe_go)) }
            }
        }
    }
}
