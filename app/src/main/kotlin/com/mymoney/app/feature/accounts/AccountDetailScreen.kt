package com.mymoney.app.feature.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.mymoney.app.feature.transactions.TransactionsScreen
import com.mymoney.app.navigation.AccountDetailRoute
import com.mymoney.app.ui.common.BackTopBar
import com.mymoney.app.ui.common.ConfirmDialog
import com.mymoney.app.ui.common.accountTypeLabel
import com.mymoney.core.designsystem.component.AmountColor
import com.mymoney.core.designsystem.component.LoadingSkeleton
import com.mymoney.core.designsystem.component.MoneyText
import com.mymoney.core.designsystem.component.SectionCard
import com.mymoney.core.designsystem.format.LocalFormats
import com.mymoney.core.designsystem.theme.MyMoneyTheme
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.model.AccountType
import com.mymoney.core.domain.model.AccountWithBalance
import com.mymoney.core.domain.repository.AccountRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AccountDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val accounts: AccountRepository,
    clock: TimeProvider,
) : ViewModel() {
    val accountId: String = savedStateHandle.toRoute<AccountDetailRoute>().accountId
    val today: LocalDate = clock.today()

    val account: StateFlow<AccountWithBalance?> = accounts.observeWithBalances(includeArchived = true)
        .map { list -> list.firstOrNull { it.account.id == accountId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setArchived(archived: Boolean) {
        viewModelScope.launch { accounts.setArchived(accountId, archived) }
    }
}

@Composable
fun AccountDetailScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onOpenEntry: (String) -> Unit,
    onNewEntry: (mode: String, accountId: String) -> Unit,
    viewModel: AccountDetailViewModel = hiltViewModel(),
) {
    val awb by viewModel.account.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmArchive by rememberSaveable { mutableStateOf(false) }
    val acc = awb?.account
    Scaffold(
        topBar = {
            BackTopBar(acc?.name.orEmpty(), onBack) {
                if (acc != null) {
                    IconButton(onClick = { onEdit(acc.id) }) { Icon(Icons.Outlined.Edit, stringResource(R.string.action_edit)) }
                    IconButton(onClick = { if (acc.isArchived) viewModel.setArchived(false) else confirmArchive = true }) {
                        Icon(
                            if (acc.isArchived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                            stringResource(if (acc.isArchived) R.string.acc_unarchive else R.string.acc_archive),
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val current = awb
        if (current == null) {
            LoadingSkeleton(Modifier.padding(padding))
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            BalanceHeader(current, onNewEntry)
            TransactionsScreen(
                contentPadding = PaddingValues(),
                snackbar = snackbar,
                today = viewModel.today,
                onOpen = onOpenEntry,
                fixedAccountId = current.account.id,
                showSearch = false,
            )
        }
    }
    if (confirmArchive) {
        ConfirmDialog(
            title = stringResource(R.string.acc_archive),
            message = stringResource(R.string.acc_archive_confirm),
            confirmLabel = stringResource(R.string.acc_archive),
            onConfirm = { confirmArchive = false; viewModel.setArchived(true) },
            onDismiss = { confirmArchive = false },
        )
    }
}

@Composable
private fun BalanceHeader(awb: AccountWithBalance, onNewEntry: (String, String) -> Unit) {
    val acc = awb.account
    val spacing = MyMoneyTheme.spacing
    val formats = LocalFormats.current
    Column(Modifier.padding(spacing.screenPadding), verticalArrangement = Arrangement.spacedBy(spacing.s)) {
        SectionCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
            Text(stringResource(accountTypeLabel(acc.type)) + " · " + formats.currencyName(acc.currency), style = MaterialTheme.typography.labelLarge)
            MoneyText(awb.balanceMinor, acc.currency, style = MaterialTheme.typography.displaySmall, color = if (awb.balanceMinor < 0) AmountColor.EXPENSE else AmountColor.NEUTRAL)
            if (awb.pendingMinor != 0L) {
                Row {
                    Text(stringResource(R.string.acc_after_pending) + "  ", style = MaterialTheme.typography.bodySmall)
                    MoneyText(awb.balanceAfterPendingMinor, acc.currency, style = MaterialTheme.typography.bodySmall)
                }
            }
            val limit = acc.creditLimitMinor
            if (acc.type == AccountType.CREDIT_CARD && limit != null) {
                Row {
                    Text(stringResource(R.string.acc_available_credit) + "  ", style = MaterialTheme.typography.bodySmall)
                    MoneyText(limit + awb.balanceMinor, acc.currency, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (acc.isArchived) Text(stringResource(R.string.acc_is_archived), color = MyMoneyTheme.finance.warning)
        }
        if (!acc.isArchived) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                AssistChip(onClick = { onNewEntry("EXPENSE", acc.id) }, label = { Text(stringResource(R.string.kind_expense)) })
                AssistChip(onClick = { onNewEntry("INCOME", acc.id) }, label = { Text(stringResource(R.string.kind_income)) })
                AssistChip(onClick = { onNewEntry("TRANSFER", acc.id) }, label = { Text(stringResource(R.string.kind_transfer)) })
                AssistChip(onClick = { onNewEntry("ADJUSTMENT", acc.id) }, label = { Text(stringResource(R.string.acc_adjust)) })
            }
        }
    }
}
