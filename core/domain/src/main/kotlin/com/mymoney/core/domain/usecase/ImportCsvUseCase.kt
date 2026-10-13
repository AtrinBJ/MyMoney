package com.mymoney.core.domain.usecase

import com.mymoney.core.domain.common.IdGenerator
import com.mymoney.core.domain.common.Outcome
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.common.failure
import com.mymoney.core.domain.common.success
import com.mymoney.core.domain.csv.CsvImport
import com.mymoney.core.domain.csv.ImportRow
import com.mymoney.core.domain.repository.ImportBatch
import com.mymoney.core.domain.repository.ImportBatchRepository
import com.mymoney.core.domain.repository.LedgerRepository
import com.mymoney.core.domain.repository.TransactionRunner
import javax.inject.Inject

data class ImportResult(val batchId: String, val imported: Int, val skipped: Int)

/**
 * FBR-37: records the confirmed rows of a CSV import in one database transaction, tagged with an import
 * batch id so the whole import can be undone. If any row fails validation nothing is recorded.
 */
class ImportCsvUseCase @Inject constructor(
    private val record: RecordOperationUseCase,
    private val ledger: LedgerRepository,
    private val batches: ImportBatchRepository,
    private val tx: TransactionRunner,
    private val ids: IdGenerator,
    private val clock: TimeProvider,
) {
    suspend fun alreadyImported(fileSha256: String): ImportBatch? = batches.findCompleted(fileSha256)

    /** Duplicate keys of the account's existing transactions around the dates in [rows] (±1 day, FBR-36). */
    suspend fun existingKeys(accountId: String, rows: List<ImportRow>): Set<String> {
        val dates = rows.mapNotNull { it.date }
        if (dates.isEmpty()) return emptySet()
        val entries = ledger.entriesForAccount(accountId, dates.min().minusDays(1), dates.max().plusDays(1))
        return entries.flatMap { e -> (-1L..1L).map { CsvImport.duplicateKey(e.date.plusDays(it), e.amountMinor, e.payee) } }.toSet()
    }

    suspend operator fun invoke(
        fileName: String,
        fileSha256: String,
        accountId: String,
        rows: List<ImportRow>,
        includeDuplicates: Boolean,
        categoryFor: (ImportRow) -> String?,
    ): Outcome<ImportResult> = try {
        tx.run {
            val batchId = ids.newId()
            val chosen = rows.filter { it.valid && (includeDuplicates || !it.duplicate) }
            chosen.forEach { r ->
                val draft = IncomeExpenseDraft(
                    groupId = "imp-$batchId-${r.line}",
                    kind = r.kind!!,
                    accountId = accountId,
                    amountMinor = r.amountMinor,
                    date = r.date!!,
                    categoryId = categoryFor(r),
                    payee = r.payee,
                    note = r.note,
                    importBatchId = batchId,
                )
                when (val res = record(draft)) {
                    is Outcome.Success -> Unit
                    is Outcome.Failure -> throw DomainFailure(res.error)
                }
            }
            batches.insert(
                ImportBatch(batchId, fileName, fileSha256, clock.nowMillis(), rows.size, chosen.size, rows.size - chosen.size, accountId, reverted = false),
            )
            ImportResult(batchId, chosen.size, rows.size - chosen.size).success()
        }
    } catch (e: DomainFailure) {
        e.error.failure()
    }

    /** Undo: moves every transaction of the import to the trash (restorable for 30 days). */
    suspend fun revert(batchId: String) = tx.run {
        ledger.groupIdsForImport(batchId).forEach { ledger.softDeleteGroup(it) }
        batches.markReverted(batchId)
    }
}
