package com.mymoney.app.feature.data

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mymoney.app.lock.AppLockManager
import com.mymoney.core.data.backup.BackupManager
import com.mymoney.core.data.backup.DataSummary
import com.mymoney.core.data.backup.InspectResult
import com.mymoney.core.data.db.dao.TrashRow
import com.mymoney.core.data.db.entity.BackupRecordEntity
import com.mymoney.core.data.exchange.DataExporter
import com.mymoney.core.data.exchange.DataWiper
import com.mymoney.core.data.exchange.ExportFilter
import com.mymoney.core.data.exchange.ExportLabels
import com.mymoney.core.data.exchange.TrashRepository
import com.mymoney.core.data.security.PinStore
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.repository.AccountRepository
import com.mymoney.core.domain.repository.CurrencyRepository
import com.mymoney.core.domain.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Progress of a long data operation; [message] is a string resource id. */
sealed interface Work {
    data object Idle : Work
    data object Running : Work
    data class Done(val message: Int, val args: List<Any> = emptyList()) : Work
    data class Failed(val message: Int) : Work
}

sealed interface RestoreStep {
    data object PickFile : RestoreStep
    data class Password(val uri: Uri, val wrong: Boolean = false) : RestoreStep
    data class Confirm(val ready: InspectResult.Ready, val current: DataSummary) : RestoreStep
}

enum class ExportFormat { CSV, JSON, PDF }

@HiltViewModel
class DataViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backups: BackupManager,
    private val exporter: DataExporter,
    private val trash: TrashRepository,
    private val wiper: DataWiper,
    private val pins: PinStore,
    private val lock: AppLockManager,
    private val settings: SettingsRepository,
    private val currencies: CurrencyRepository,
    accounts: AccountRepository,
    private val pdf: ReportPdfWriter,
    private val clock: TimeProvider,
) : ViewModel() {
    val history: StateFlow<List<BackupRecordEntity>> = backups.observeHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val trashRows: StateFlow<List<TrashRow>> = trash.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val accountList = accounts.observeWithBalances(includeArchived = true).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settingsState = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _work = MutableStateFlow<Work>(Work.Idle)
    val work: StateFlow<Work> = _work.asStateFlow()
    private val _restore = MutableStateFlow<RestoreStep>(RestoreStep.PickFile)
    val restore: StateFlow<RestoreStep> = _restore.asStateFlow()

    val pinSet: Boolean get() = pins.hasPin()

    fun clearWork() { _work.value = Work.Idle }

    private fun open(uri: Uri): InputStream = requireNotNull(context.contentResolver.openInputStream(uri)) { "cannot open" }

    private fun launchWork(block: suspend () -> Work) {
        _work.value = Work.Running
        viewModelScope.launch {
            _work.value = try {
                block()
            } catch (e: Exception) {
                Work.Failed(com.mymoney.app.R.string.data_err_generic)
            }
        }
    }

    // ---- Backup ----

    fun backup(uri: Uri, password: String?) = launchWork {
        val out = requireNotNull(context.contentResolver.openOutputStream(uri, "wt"))
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "backup.mmbak"
        val r = backups.export(out, password?.takeIf { it.isNotEmpty() }?.toCharArray(), fileName = name)
        Work.Done(com.mymoney.app.R.string.backup_done, listOf(r.entryCount))
    }

    // ---- Restore ----

    fun pickedRestoreFile(uri: Uri) {
        _work.value = Work.Running
        viewModelScope.launch {
            when (backups.needsPassword { open(uri) }) {
                null -> _work.value = Work.Failed(com.mymoney.app.R.string.restore_err_not_backup)
                true -> { _work.value = Work.Idle; _restore.value = RestoreStep.Password(uri) }
                false -> inspect(uri, null)
            }
        }
    }

    fun submitPassword(uri: Uri, password: String) {
        _work.value = Work.Running
        viewModelScope.launch { inspect(uri, password.toCharArray()) }
    }

    private suspend fun inspect(uri: Uri, password: CharArray?) {
        val r = try {
            backups.inspect({ open(uri) }, password)
        } catch (e: Exception) {
            InspectResult.Corrupt
        }
        _work.value = Work.Idle
        when (r) {
            is InspectResult.Ready -> _restore.value = RestoreStep.Confirm(r, backups.currentSummary())
            InspectResult.WrongPassword -> _restore.value = RestoreStep.Password(uri, wrong = true)
            InspectResult.NeedsPassword -> _restore.value = RestoreStep.Password(uri)
            InspectResult.NotABackup -> _work.value = Work.Failed(com.mymoney.app.R.string.restore_err_not_backup)
            InspectResult.NewerVersion -> _work.value = Work.Failed(com.mymoney.app.R.string.restore_err_newer)
            InspectResult.Corrupt -> _work.value = Work.Failed(com.mymoney.app.R.string.restore_err_corrupt)
        }
    }

    fun confirmRestore(ready: InspectResult.Ready) = launchWork {
        val result = backups.restore(ready)
        _restore.value = RestoreStep.PickFile
        Work.Done(com.mymoney.app.R.string.restore_done, listOf(result.accounts, result.entries))
    }

    fun cancelRestore() {
        _restore.value = RestoreStep.PickFile
    }

    /** Verifies the PIN before a destructive action; true when no PIN is set. */
    suspend fun checkPin(pin: String): Boolean =
        !pins.hasPin() || withContext(Dispatchers.Default) { pins.verify(pin) } == PinStore.Result.Ok

    // ---- Export ----

    fun export(uri: Uri, format: ExportFormat, filter: ExportFilter, labels: ExportLabels) = launchWork {
        val out = requireNotNull(context.contentResolver.openOutputStream(uri, "wt"))
        val s = settings.settings.first()
        val n = when (format) {
            ExportFormat.CSV -> exporter.exportCsv(out, filter, labels, s.irrUnit, currencies.getAll().associate { it.code to it.minorDigits })
            ExportFormat.JSON -> exporter.exportJson(out, filter, labels)
            ExportFormat.PDF -> pdf.write(out, filter)
        }
        Work.Done(com.mymoney.app.R.string.export_done, listOf(n))
    }

    // ---- Trash ----

    fun restoreFromTrash(groupId: String) = viewModelScope.launch { trash.restore(groupId) }
    fun deleteForever(groupId: String) = viewModelScope.launch { trash.deletePermanently(groupId) }
    fun emptyTrash() = viewModelScope.launch { trash.empty() }

    // ---- Delete all ----

    fun wipe() = launchWork {
        wiper.wipeAll()
        lock.unlock()
        Work.Done(com.mymoney.app.R.string.wipe_done)
    }

    fun today() = clock.today()
}
