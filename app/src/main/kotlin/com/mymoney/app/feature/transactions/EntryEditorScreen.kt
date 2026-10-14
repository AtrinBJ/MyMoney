package com.mymoney.app.feature.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoney.app.R
import com.mymoney.app.ui.common.AccountPickerSheet
import com.mymoney.app.ui.common.BackTopBar
import com.mymoney.app.ui.common.SaveAction
import com.mymoney.app.ui.common.CategoryPickerSheet
import com.mymoney.app.ui.common.DateField
import com.mymoney.app.ui.common.PickerField
import com.mymoney.app.ui.common.TimeField
import com.mymoney.app.ui.common.categoryLabel
import com.mymoney.app.ui.common.errorMessage
import com.mymoney.core.designsystem.component.AmountField
import com.mymoney.core.designsystem.component.InfoBanner
import com.mymoney.core.designsystem.component.LoadingSkeleton
import com.mymoney.core.designsystem.component.MoneyText
import com.mymoney.core.designsystem.format.LocalFormats
import com.mymoney.core.designsystem.theme.MyMoneyTheme
import com.mymoney.core.domain.model.CostType
import com.mymoney.core.domain.money.Digits

@Composable
fun EntryEditorScreen(onDone: () -> Unit, viewModel: EntryEditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) { if (state.saved) onDone() }
    val title = stringResource(
        when {
            state.isEdit -> R.string.editor_title_edit
            else -> when (state.mode) {
                EditorMode.EXPENSE, EditorMode.INCOME, EditorMode.TRANSFER -> R.string.editor_title_new
                EditorMode.REFUND -> R.string.editor_title_refund
                EditorMode.ADJUSTMENT -> R.string.editor_title_adjust
            }
        },
    )
    Scaffold(topBar = {
        BackTopBar(title, onDone) { if (!state.loading) SaveAction(!state.saving) { viewModel.save() } }
    }) { padding ->
        if (state.loading) {
            LoadingSkeleton(Modifier.padding(padding))
            return@Scaffold
        }
        EditorForm(state, viewModel, Modifier.padding(padding))
    }

    if (state.duplicates.isNotEmpty()) {
        val formats = LocalFormats.current
        AlertDialog(
            onDismissRequest = viewModel::dismissDuplicates,
            title = { Text(stringResource(R.string.editor_duplicate_title)) },
            text = {
                Text(stringResource(R.string.editor_duplicate_message, formats.dates.medium(state.duplicates.first().date)))
            },
            confirmButton = { TextButton(onClick = { viewModel.save(force = true) }) { Text(stringResource(R.string.editor_save_anyway)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissDuplicates) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorForm(s: EditorState, vm: EntryEditorViewModel, modifier: Modifier) {
    val spacing = MyMoneyTheme.spacing
    val formats = LocalFormats.current
    var pickAccountFor by rememberSaveable { mutableStateOf<String?>(null) } // "from" | "to"
    var pickCategoryFor by rememberSaveable { mutableIntStateOf(-2) } // -1 main, >=0 split index
    var showMore by rememberSaveable { mutableStateOf(s.isEdit && (s.payee.isNotEmpty() || s.note.isNotEmpty() || s.selectedTags.isNotEmpty())) }

    Column(
        modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(spacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(spacing.m),
    ) {
        if (!s.isEdit && s.refundOf == null && s.mode != EditorMode.ADJUSTMENT) {
            val modes = listOf(EditorMode.EXPENSE, EditorMode.INCOME, EditorMode.TRANSFER)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = s.mode == m,
                        onClick = { vm.setMode(m) },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    ) {
                        Text(
                            stringResource(
                                when (m) {
                                    EditorMode.EXPENSE -> R.string.kind_expense
                                    EditorMode.INCOME -> R.string.kind_income
                                    else -> R.string.kind_transfer
                                },
                            ),
                        )
                    }
                }
            }
        }

        if (s.accounts.isEmpty()) {
            InfoBanner(stringResource(R.string.editor_no_accounts))
            return@Column
        }

        // Account(s)
        val accountLabel = if (s.mode == EditorMode.TRANSFER) R.string.editor_from_account else R.string.editor_account
        PickerField(
            label = stringResource(accountLabel),
            value = s.account?.name.orEmpty(),
            onClick = { pickAccountFor = "from" },
            icon = { Icon(Icons.Outlined.ExpandMore, null) },
        )
        if (s.mode == EditorMode.TRANSFER) {
            PickerField(
                label = stringResource(R.string.editor_to_account),
                value = s.toAccount?.name.orEmpty(),
                onClick = { pickAccountFor = "to" },
                icon = { Icon(Icons.Outlined.ExpandMore, null) },
            )
        }

        // Refund context
        if (s.mode == EditorMode.REFUND && s.refundOf != null && s.refundableMinor != null) {
            InfoBanner(
                stringResource(
                    R.string.editor_refundable,
                    formats.money.format(s.refundableMinor, s.account?.currency ?: formats.baseCurrency),
                ),
            )
        }
        if (s.mode == EditorMode.ADJUSTMENT && s.adjustCurrentMinor != null && s.account != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.editor_adjust_current) + "  ", style = MaterialTheme.typography.bodyMedium)
                MoneyText(s.adjustCurrentMinor, s.account!!.currency, style = MaterialTheme.typography.titleSmall)
            }
        }

        // Amounts
        val currency = s.account?.currency ?: formats.baseCurrency
        AmountField(
            value = s.amount,
            onValueChange = vm::setAmount,
            currency = currency,
            label = stringResource(
                when (s.mode) {
                    EditorMode.TRANSFER -> if (s.isCrossCurrency) R.string.editor_amount_sent else R.string.editor_amount
                    EditorMode.ADJUSTMENT -> R.string.editor_adjust_actual
                    else -> R.string.editor_amount
                },
            ),
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.headlineSmall,
        )
        if (s.isCrossCurrency) {
            AmountField(
                value = s.amountIn,
                onValueChange = vm::setAmountIn,
                currency = s.toAccount!!.currency,
                label = stringResource(R.string.editor_amount_received),
                modifier = Modifier.fillMaxWidth(),
            )
            vm.impliedRate(s)?.let { rate ->
                Text(
                    stringResource(
                        R.string.editor_implied_rate,
                        formats.money.label(s.account!!.currency),
                        Digits.localize(java.text.DecimalFormat("#,##0.##########", java.text.DecimalFormatSymbols(java.util.Locale.ROOT)).format(rate), formats.settings.digits),
                        formats.money.label(s.toAccount!!.currency),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (s.mode == EditorMode.TRANSFER) {
            AmountField(
                value = s.fee,
                onValueChange = vm::setFee,
                currency = currency,
                label = stringResource(R.string.editor_fee),
                supportingText = stringResource(R.string.editor_fee_hint),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Category / split
        if (s.mode == EditorMode.EXPENSE || s.mode == EditorMode.INCOME || (s.mode == EditorMode.REFUND && s.refundOf == null)) {
            if (s.mode != EditorMode.REFUND) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.editor_split), modifier = Modifier.weight(1f))
                    Switch(checked = s.useSplit, onCheckedChange = vm::setUseSplit)
                }
            }
            if (!s.useSplit || s.mode == EditorMode.REFUND) {
                val cat = s.categories.firstOrNull { it.id == s.categoryId }
                PickerField(
                    label = stringResource(R.string.editor_category),
                    value = if (cat != null) categoryLabel(cat) else "",
                    onClick = { pickCategoryFor = -1 },
                    icon = { Icon(Icons.Outlined.ExpandMore, null) },
                )
            } else {
                s.splits.forEachIndexed { i, row ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                        val cat = s.categories.firstOrNull { it.id == row.categoryId }
                        PickerField(
                            label = stringResource(R.string.editor_category),
                            value = if (cat != null) categoryLabel(cat) else "",
                            onClick = { pickCategoryFor = i },
                            modifier = Modifier.weight(1.2f),
                        )
                        AmountField(
                            value = row.amount,
                            onValueChange = { vm.setSplit(i, row.copy(amount = it)) },
                            currency = currency,
                            label = stringResource(R.string.editor_amount),
                            modifier = Modifier.weight(1f),
                        )
                        if (s.splits.size > 2) {
                            IconButton(onClick = { vm.removeSplit(i) }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_delete))
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = vm::addSplit) {
                        Icon(Icons.Filled.Add, null)
                        Text(stringResource(R.string.editor_add_split))
                    }
                    vm.splitRemaining(s)?.let { rem ->
                        Text(
                            stringResource(R.string.editor_split_remaining, formats.money.format(rem, currency)),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (rem == 0L) MyMoneyTheme.finance.income else MyMoneyTheme.finance.warning,
                        )
                    }
                }
            }
        }

        if (s.mode == EditorMode.ADJUSTMENT) {
            OutlinedTextField(
                value = s.reason,
                onValueChange = vm::setReason,
                label = { Text(stringResource(R.string.editor_adjust_reason)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Date / time
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
            DateField(stringResource(R.string.editor_date), s.date, s.today, vm::setDate, Modifier.weight(1.4f))
            if (s.mode != EditorMode.ADJUSTMENT) {
                TimeField(stringResource(R.string.editor_time), s.timeMinutes, vm::setTime, Modifier.weight(1f))
            }
        }

        if (s.mode != EditorMode.ADJUSTMENT) {
            TextButton(onClick = { showMore = !showMore }) { Text(stringResource(R.string.editor_more_options)) }
        }
        if (showMore && s.mode != EditorMode.ADJUSTMENT) {
            if (s.mode != EditorMode.TRANSFER) {
                OutlinedTextField(
                    value = s.payee,
                    onValueChange = vm::setPayee,
                    label = { Text(stringResource(R.string.editor_payee)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = s.note,
                onValueChange = vm::setNote,
                label = { Text(stringResource(R.string.editor_note)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.editor_pending), modifier = Modifier.weight(1f))
                Switch(checked = s.pending, onCheckedChange = vm::setPending)
            }
            if (s.mode == EditorMode.EXPENSE) {
                Text(stringResource(R.string.editor_cost_type), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                    FilterChip(s.costType == CostType.FIXED, { vm.setCostType(if (s.costType == CostType.FIXED) null else CostType.FIXED) }, { Text(stringResource(R.string.editor_fixed)) })
                    FilterChip(s.costType == CostType.VARIABLE, { vm.setCostType(if (s.costType == CostType.VARIABLE) null else CostType.VARIABLE) }, { Text(stringResource(R.string.editor_variable)) })
                    FilterChip(s.essential == true, { vm.setEssential(if (s.essential == true) null else true) }, { Text(stringResource(R.string.editor_essential)) })
                    FilterChip(s.essential == false, { vm.setEssential(if (s.essential == false) null else false) }, { Text(stringResource(R.string.editor_optional)) })
                }
            }
            TagPicker(s, vm)
        }

        s.error?.let { Text(errorMessage(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

        Button(
            onClick = { vm.save() },
            enabled = !s.saving,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.action_save)) }
    }

    if (pickAccountFor != null) {
        AccountPickerSheet(
            accounts = s.accounts,
            onPick = { a ->
                if (pickAccountFor == "to") vm.setToAccount(a.id) else vm.setAccount(a.id)
                pickAccountFor = null
            },
            onDismiss = { pickAccountFor = null },
        )
    }
    if (pickCategoryFor >= -1) {
        CategoryPickerSheet(
            categories = s.visibleCategories,
            onPick = { c ->
                if (pickCategoryFor == -1) vm.setCategory(c.id) else vm.setSplit(pickCategoryFor, s.splits[pickCategoryFor].copy(categoryId = c.id))
                pickCategoryFor = -2
            },
            onDismiss = { pickCategoryFor = -2 },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagPicker(s: EditorState, vm: EntryEditorViewModel) {
    var newTag by rememberSaveable { mutableStateOf("") }
    Text(stringResource(R.string.editor_tags), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(MyMoneyTheme.spacing.s)) {
        s.tags.forEach { t ->
            InputChip(
                selected = t.id in s.selectedTags,
                onClick = { vm.toggleTag(t.id) },
                label = { Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = newTag,
            onValueChange = { newTag = it.take(30) },
            label = { Text(stringResource(R.string.editor_new_tag)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        IconButton(enabled = newTag.isNotBlank(), onClick = { vm.addTag(newTag); newTag = "" }) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_add))
        }
    }
}
