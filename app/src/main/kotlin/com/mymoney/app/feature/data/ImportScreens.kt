@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.mymoney.app.feature.data

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mymoney.app.R
import com.mymoney.app.ui.common.AccountPickerSheet
import com.mymoney.app.ui.common.BackTopBar
import com.mymoney.app.ui.common.PickerField
import com.mymoney.core.data.db.SeedData
import com.mymoney.core.designsystem.component.InfoBanner
import com.mymoney.core.designsystem.component.MoneyText
import com.mymoney.core.designsystem.component.SectionCard
import com.mymoney.core.designsystem.format.LocalFormats
import com.mymoney.core.designsystem.theme.MyMoneyTheme
import com.mymoney.core.domain.common.Outcome
import com.mymoney.core.domain.csv.Csv
import com.mymoney.core.domain.csv.CsvImport
import com.mymoney.core.domain.csv.CsvMapping
import com.mymoney.core.domain.csv.DateOrder
import com.mymoney.core.domain.csv.ImportRow
import com.mymoney.core.domain.csv.RowProblem
import com.mymoney.core.domain.model.Account
import com.mymoney.core.domain.model.CategoryType
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.money.Currency
import com.mymoney.core.domain.money.Digits
import com.mymoney.core.domain.repository.AccountRepository
import com.mymoney.core.domain.repository.CategoryRepository
import com.mymoney.core.domain.repository.CurrencyRepository
import com.mymoney.core.domain.repository.ImportBatch
import com.mymoney.core.domain.repository.ImportBatchRepository
import com.mymoney.core.domain.settings.CalendarType
import com.mymoney.core.domain.settings.IrrUnit
import com.mymoney.core.domain.usecase.FindPossibleDuplicatesUseCase
import com.mymoney.core.domain.usecase.ImportCsvUseCase
import com.mymoney.core.domain.usecase.ImportResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ImportStep { PICK, MAP, PREVIEW, DONE }

data class ImportState(
    val step: ImportStep = ImportStep.PICK,
    val busy: Boolean = false,
    val error: Int? = null,
    val fileName: String = "",
    val sha: String = "",
    val rows: List<List<String>> = emptyList(),
    val previous: ImportBatch? = null,
    val mapping: CsvMapping? = null,
    val account: Account? = null,
    val plan: List<ImportRow> = emptyList(),
    val includeDuplicates: Boolean = false,
    val result: ImportResult? = null,
    val reverted: Boolean = false,
) {
    val header: List<String> get() = rows.firstOrNull().orEmpty()
    val columnCount: Int get() = rows.maxOfOrNull { it.size } ?: 0
    val chosen: List<ImportRow> get() = plan.filter { it.valid && (includeDuplicates || !it.duplicate) }
}

@HiltViewModel
class ImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val import: ImportCsvUseCase,
    private val accounts: AccountRepository,
    private val categories: CategoryRepository,
    private val currencies: CurrencyRepository,
    batches: ImportBatchRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = _state.asStateFlow()
    val accountList = accounts.observeWithBalances(includeArchived = false)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val history = batches.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private var planJob: Job? = null

    fun pick(uri: Uri, name: String) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
                    require(bytes.size <= MAX_FILE_BYTES)
                    val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    Triple(Csv.parse(decode(bytes)), sha, bytes.size)
                }.getOrNull()
            }
            if (loaded == null || loaded.first.isEmpty()) {
                _state.update { it.copy(busy = false, error = R.string.import_err_read) }
                return@launch
            }
            val (rows, sha, _) = loaded
            // Read directly: the account flow is only collected once the mapping step is on screen.
            val account = accounts.getAll().firstOrNull { !it.type.isObligation && !it.isArchived }
            val m = guessMapping(rows, account)
            _state.value = ImportState(
                step = ImportStep.MAP, fileName = name, sha = sha, rows = rows, previous = import.alreadyImported(sha), mapping = m, account = account,
            )
        }
    }

    /** Bank exports are UTF-8 or (older Iranian systems) Windows-1256; strict UTF-8 decoding tells them apart. */
    private fun decode(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        String(bytes, Charset.forName("windows-1256"))
    }

    /** Pre-selects columns from header names (English and Persian) and the date format from sample values. */
    private fun guessMapping(rows: List<List<String>>, account: Account?): CsvMapping? {
        account ?: return null
        val header = rows.first().map { FindPossibleDuplicatesUseCase.normalizePayee(it) }
        fun find(vararg keys: String) = header.indexOfFirst { h -> keys.any { h.contains(FindPossibleDuplicatesUseCase.normalizePayee(it)) } }.takeIf { it >= 0 }
        val dateCol = find("date", "تاریخ") ?: 0
        val hasHeader = rows.size > 1 && CsvImport.parseDate(rows[0].getOrElse(dateCol) { "" }, CalendarType.JALALI, DateOrder.YMD) == null &&
            CsvImport.parseDate(rows[0].getOrElse(dateCol) { "" }, CalendarType.GREGORIAN, DateOrder.YMD) == null
        val samples = rows.drop(if (hasHeader) 1 else 0).take(20).mapNotNull { it.getOrNull(dateCol) }
        val (calendar, order) = CsvImport.guessDateFormat(samples)
        val debit = if (hasHeader) find("debit", "withdraw", "برداشت", "بدهکار") else null
        val credit = if (hasHeader) find("credit", "deposit", "واریز", "بستانکار") else null
        val amount = if (hasHeader) find("amount", "مبلغ", "value") else null
        val useSplit = debit != null && credit != null && amount == null
        return CsvMapping(
            hasHeader = hasHeader,
            dateCol = dateCol,
            amountCol = if (useSplit) null else amount ?: (1.takeIf { rows.first().size > 1 } ?: 0),
            debitCol = if (useSplit) debit else null,
            creditCol = if (useSplit) credit else null,
            payeeCol = if (hasHeader) find("payee", "description", "merchant", "شرح", "طرف", "توضیح") else null,
            noteCol = if (hasHeader) find("note", "memo", "یادداشت") else null,
            categoryCol = if (hasHeader) find("category", "دسته") else null,
            calendar = calendar,
            dateOrder = order,
            accountId = account.id,
            currency = account.currency,
            minorDigits = 2,
            irrUnit = IrrUnit.RIAL,
        )
    }

    fun setMapping(m: CsvMapping) = _state.update { it.copy(mapping = m) }

    fun setAccount(a: Account) = _state.update { s -> s.copy(account = a, mapping = s.mapping?.copy(accountId = a.id, currency = a.currency)) }

    fun toPreview() {
        val s = _state.value
        val m = s.mapping ?: return
        planJob?.cancel()
        _state.update { it.copy(busy = true) }
        planJob = viewModelScope.launch {
            val digits = currencies.getAll().firstOrNull { it.code == m.currency }?.minorDigits ?: 2
            val mapping = m.copy(minorDigits = digits)
            val plan = withContext(Dispatchers.Default) {
                val first = CsvImport.plan(s.rows, mapping, emptySet())
                CsvImport.plan(s.rows, mapping, import.existingKeys(mapping.accountId, first))
            }
            _state.update { it.copy(step = ImportStep.PREVIEW, plan = plan, mapping = mapping, busy = false) }
        }
    }

    fun back() = _state.update { it.copy(step = if (it.step == ImportStep.PREVIEW) ImportStep.MAP else ImportStep.PICK) }

    fun setIncludeDuplicates(v: Boolean) = _state.update { it.copy(includeDuplicates = v) }

    fun runImport() {
        val s = _state.value
        val m = s.mapping ?: return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val cats = categories.getAll().filter { !it.isArchived }
            fun key(t: String?) = FindPossibleDuplicatesUseCase.normalizePayee(t)
            val byName = cats.filter { it.name != null }.associateBy { key(it.name) to it.type }
            val categoryFor = { r: ImportRow ->
                val type = if (r.kind == EntryKind.INCOME) CategoryType.INCOME else CategoryType.EXPENSE
                r.categoryText?.let { byName[key(it) to type]?.id }
                    ?: SeedData.categoryId(if (type == CategoryType.INCOME) "income.other" else "expense.other")
            }
            when (val r = import(s.fileName, s.sha, m.accountId, s.plan, s.includeDuplicates, categoryFor)) {
                is Outcome.Success -> _state.update { it.copy(step = ImportStep.DONE, result = r.value, busy = false) }
                is Outcome.Failure -> _state.update { it.copy(busy = false, error = R.string.import_err_failed) }
            }
        }
    }

    fun revert(batchId: String) {
        viewModelScope.launch {
            import.revert(batchId)
            _state.update { if (it.result?.batchId == batchId) it.copy(reverted = true) else it }
        }
    }

    fun reset() { _state.value = ImportState() }

    private companion object {
        const val MAX_FILE_BYTES = 10 * 1024 * 1024
    }
}

@Composable
fun ImportScreen(onBack: () -> Unit, viewModel: ImportViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.pick(uri, displayName(context, uri))
    }
    val spacing = MyMoneyTheme.spacing
    Scaffold(topBar = { BackTopBar(stringResource(R.string.import_title), if (s.step == ImportStep.MAP || s.step == ImportStep.PREVIEW) viewModel::back else onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(spacing.screenPadding, spacing.s, spacing.screenPadding, 32.dp),
            verticalArrangement = Arrangement.spacedBy(spacing.cardGap),
        ) {
            if (s.busy) item(key = "busy") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            s.error?.let { e -> item(key = "error") { InfoBanner(stringResource(e)) } }
            when (s.step) {
                ImportStep.PICK -> {
                    item(key = "pick") {
                        SectionCard {
                            Text(stringResource(R.string.import_explain), style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = { context.allowExternalScreen(); picker.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "*/*")) }, modifier = Modifier.padding(top = 8.dp)) {
                                Icon(Icons.Outlined.FileOpen, null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.import_choose))
                            }
                        }
                    }
                    if (history.isNotEmpty()) {
                        item(key = "history") { ImportHistory(history, viewModel::revert) }
                    }
                }
                ImportStep.MAP -> mappingItems(s, viewModel)
                ImportStep.PREVIEW -> previewItems(s, viewModel)
                ImportStep.DONE -> item(key = "done") {
                    val r = s.result!!
                    val f = LocalFormats.current
                    SectionCard(containerColor = if (s.reverted) MaterialTheme.colorScheme.surfaceContainerLow else MyMoneyTheme.finance.incomeContainer) {
                        Text(
                            if (s.reverted) stringResource(R.string.import_reverted)
                            else stringResource(R.string.import_done, Digits.localize(r.imported.toString(), f.settings.digits), Digits.localize(r.skipped.toString(), f.settings.digits)),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Row {
                            if (!s.reverted) TextButton(onClick = { viewModel.revert(r.batchId) }) { Text(stringResource(R.string.import_undo)) }
                            TextButton(onClick = onBack) { Text(stringResource(R.string.action_ok)) }
                        }
                    }
                }
            }
        }
    }
}

private fun displayName(context: Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment ?: "import.csv"

private fun androidx.compose.foundation.lazy.LazyListScope.mappingItems(s: ImportState, vm: ImportViewModel) {
    val m = s.mapping
    s.previous?.let { p ->
        item(key = "previous") {
            val f = LocalFormats.current
            InfoBanner(
                stringResource(
                    R.string.import_already,
                    f.dates.medium(java.time.Instant.ofEpochMilli(p.importedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()),
                    Digits.localize(p.importedCount.toString(), f.settings.digits),
                ),
            )
        }
    }
    item(key = "sample") {
        SectionCard(title = s.fileName) {
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                s.rows.take(6).forEachIndexed { i, r ->
                    Row {
                        r.forEach { c ->
                            Text(
                                c, Modifier.widthIn(min = 72.dp, max = 140.dp).padding(end = 8.dp, bottom = 2.dp),
                                style = if (i == 0 && m?.hasHeader == true) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
    if (m == null) {
        item(key = "noacc") { InfoBanner(stringResource(R.string.import_no_account)) }
        return
    }
    item(key = "mapping") {
        var pickAccount by remember { mutableStateOf(false) }
        val accounts by vm.accountList.collectAsStateWithLifecycle()
        SectionCard(title = stringResource(R.string.import_mapping)) {
            PickerField(stringResource(R.string.import_account), s.account?.name.orEmpty(), { pickAccount = true }, Modifier.fillMaxWidth())
            SwitchLine(stringResource(R.string.import_has_header), m.hasHeader) { vm.setMapping(m.copy(hasHeader = it)) }
            ColumnChoice(stringResource(R.string.import_col_date), m.dateCol, s, required = true) { vm.setMapping(m.copy(dateCol = it ?: 0)) }
            SwitchLine(stringResource(R.string.import_split_amounts), m.amountCol == null) { split ->
                vm.setMapping(if (split) m.copy(amountCol = null, debitCol = m.amountCol ?: 1, creditCol = (m.amountCol ?: 1) + 1) else m.copy(amountCol = m.debitCol ?: 1, debitCol = null, creditCol = null))
            }
            if (m.amountCol != null) {
                ColumnChoice(stringResource(R.string.import_col_amount), m.amountCol, s, required = true) { vm.setMapping(m.copy(amountCol = it ?: 0)) }
                SwitchLine(stringResource(R.string.import_invert), m.invertSign) { vm.setMapping(m.copy(invertSign = it)) }
            } else {
                ColumnChoice(stringResource(R.string.import_col_debit), m.debitCol, s) { vm.setMapping(m.copy(debitCol = it)) }
                ColumnChoice(stringResource(R.string.import_col_credit), m.creditCol, s) { vm.setMapping(m.copy(creditCol = it)) }
            }
            ColumnChoice(stringResource(R.string.import_col_payee), m.payeeCol, s) { vm.setMapping(m.copy(payeeCol = it)) }
            ColumnChoice(stringResource(R.string.import_col_note), m.noteCol, s) { vm.setMapping(m.copy(noteCol = it)) }
            ColumnChoice(stringResource(R.string.import_col_category), m.categoryCol, s) { vm.setMapping(m.copy(categoryCol = it)) }
            Text(stringResource(R.string.import_date_format), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(m.calendar == CalendarType.JALALI, { vm.setMapping(m.copy(calendar = CalendarType.JALALI)) }, label = { Text(stringResource(R.string.settings_calendar_jalali)) })
                FilterChip(m.calendar == CalendarType.GREGORIAN, { vm.setMapping(m.copy(calendar = CalendarType.GREGORIAN)) }, label = { Text(stringResource(R.string.settings_calendar_gregorian)) })
                DateOrder.entries.forEach { o ->
                    FilterChip(m.dateOrder == o, { vm.setMapping(m.copy(dateOrder = o)) }, label = { Text(stringResource(dateOrderLabel(o))) })
                }
            }
            if (m.currency == Currency.IRR) {
                Text(stringResource(R.string.import_unit), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(m.irrUnit == IrrUnit.RIAL, { vm.setMapping(m.copy(irrUnit = IrrUnit.RIAL)) }, label = { Text(stringResource(R.string.settings_irr_unit_rial)) })
                    FilterChip(m.irrUnit == IrrUnit.TOMAN, { vm.setMapping(m.copy(irrUnit = IrrUnit.TOMAN)) }, label = { Text(stringResource(R.string.settings_irr_unit_toman)) })
                }
            }
            Button(onClick = vm::toPreview, enabled = !s.busy, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text(stringResource(R.string.import_check)) }
        }
        if (pickAccount) {
            AccountPickerSheet(accounts.map { it.account }.filter { !it.type.isObligation }, { vm.setAccount(it); pickAccount = false }, { pickAccount = false })
        }
    }
}

private fun dateOrderLabel(o: DateOrder) = when (o) {
    DateOrder.YMD -> R.string.import_order_ymd
    DateOrder.DMY -> R.string.import_order_dmy
    DateOrder.MDY -> R.string.import_order_mdy
}

@Composable
private fun SwitchLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onCheckedChange = null)
    }
}

@Composable
private fun ColumnChoice(label: String, selected: Int?, s: ImportState, required: Boolean = false, onPick: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val none = stringResource(R.string.import_col_none)
    fun name(i: Int) = if (s.mapping?.hasHeader == true) s.header.getOrNull(i)?.takeIf { it.isNotBlank() } ?: "#${i + 1}" else "#${i + 1}: " + (s.rows.firstOrNull()?.getOrNull(i) ?: "")
    PickerField(label, selected?.let { name(it) } ?: none, { open = true }, Modifier.fillMaxWidth().padding(top = 4.dp))
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = {
                LazyColumn {
                    if (!required) item { ListItem(headlineContent = { Text(none) }, modifier = Modifier.clickable { onPick(null); open = false }) }
                    items((0 until s.columnCount).toList()) { i ->
                        ListItem(headlineContent = { Text(name(i), maxLines = 1, overflow = TextOverflow.Ellipsis) }, modifier = Modifier.clickable { onPick(i); open = false })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.previewItems(s: ImportState, vm: ImportViewModel) {
    val valid = s.plan.filter { it.valid }
    val invalid = s.plan.filter { !it.valid }
    val dups = valid.count { it.duplicate }
    item(key = "summary") {
        val f = LocalFormats.current
        fun n(v: Int) = Digits.localize(v.toString(), f.settings.digits)
        SectionCard(title = stringResource(R.string.import_preview)) {
            Text(stringResource(R.string.import_counts, n(s.plan.size), n(valid.size), n(invalid.size), n(dups)), style = MaterialTheme.typography.bodyMedium)
            val cur = s.mapping!!.currency
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.home_income), style = MaterialTheme.typography.labelMedium)
                    MoneyText(s.chosen.filter { it.kind == EntryKind.INCOME }.sumOf { it.amountMinor }, cur)
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.home_expense), style = MaterialTheme.typography.labelMedium)
                    MoneyText(s.chosen.filter { it.kind == EntryKind.EXPENSE }.sumOf { it.amountMinor }, cur)
                }
            }
            if (dups > 0) SwitchLine(stringResource(R.string.import_include_dups), s.includeDuplicates, vm::setIncludeDuplicates)
            Button(onClick = vm::runImport, enabled = !s.busy && s.chosen.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(stringResource(R.string.import_go, n(s.chosen.size)))
            }
        }
    }
    if (invalid.isNotEmpty()) {
        item(key = "invalid") {
            val f = LocalFormats.current
            SectionCard(title = stringResource(R.string.import_rejected)) {
                invalid.take(50).forEach { r ->
                    Text(
                        stringResource(R.string.import_line, Digits.localize(r.line.toString(), f.settings.digits)) + ": " + stringResource(problemLabel(r.problem!!)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (invalid.size > 50) Text("…", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    item(key = "sample") {
        val f = LocalFormats.current
        SectionCard(title = stringResource(R.string.import_sample)) {
            valid.take(15).forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.payee ?: r.note ?: "—", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            f.dates.medium(r.date!!) + if (r.duplicate) " · " + stringResource(R.string.import_duplicate) else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (r.duplicate) MyMoneyTheme.finance.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    MoneyText(if (r.kind == EntryKind.EXPENSE) -r.amountMinor else r.amountMinor, s.mapping!!.currency, color = com.mymoney.core.designsystem.component.AmountColor.SIGNED)
                }
                HorizontalDivider()
            }
        }
    }
}

private fun problemLabel(p: RowProblem) = when (p) {
    RowProblem.DATE -> R.string.import_p_date
    RowProblem.DATE_RANGE -> R.string.import_p_date_range
    RowProblem.AMOUNT -> R.string.import_p_amount
    RowProblem.ZERO -> R.string.import_p_zero
    RowProblem.TOO_LARGE -> R.string.import_p_large
    RowProblem.COLUMNS -> R.string.import_p_columns
}

@Composable
private fun ImportHistory(history: List<ImportBatch>, onRevert: (String) -> Unit) {
    val f = LocalFormats.current
    var confirm by remember { mutableStateOf<ImportBatch?>(null) }
    SectionCard(title = stringResource(R.string.import_history)) {
        history.take(10).forEach { b ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(b.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        f.dates.medium(java.time.Instant.ofEpochMilli(b.importedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()) + " · " +
                            stringResource(R.string.import_history_count, Digits.localize(b.importedCount.toString(), f.settings.digits)) +
                            if (b.reverted) " · " + stringResource(R.string.import_reverted_short) else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!b.reverted) OutlinedButton(onClick = { confirm = b }) { Text(stringResource(R.string.import_undo)) }
            }
        }
    }
    confirm?.let { b ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.import_undo)) },
            text = { Text(stringResource(R.string.import_undo_confirm, b.fileName)) },
            confirmButton = { TextButton(onClick = { onRevert(b.id); confirm = null }) { Text(stringResource(R.string.import_undo)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
