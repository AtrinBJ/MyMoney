package com.mymoney.core.data.repository

import com.mymoney.core.data.db.MyMoneyDatabase
import com.mymoney.core.data.db.entity.ImportBatchEntity
import com.mymoney.core.domain.repository.ImportBatch
import com.mymoney.core.domain.repository.ImportBatchRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ImportBatchRepositoryImpl @Inject constructor(private val db: MyMoneyDatabase) : ImportBatchRepository {
    override suspend fun findCompleted(fileSha256: String): ImportBatch? = db.importBatchDao().findCompleted(fileSha256)?.toDomain()

    override suspend fun insert(batch: ImportBatch) = db.importBatchDao().insert(
        ImportBatchEntity(
            batch.id, batch.fileName, batch.fileSha256, batch.importedAt, batch.rowCount, batch.importedCount, batch.skippedCount,
            batch.accountId, if (batch.reverted) REVERTED else COMPLETED,
        ),
    )

    override suspend fun markReverted(id: String) = db.importBatchDao().setStatus(id, REVERTED)

    override fun observeAll(): Flow<List<ImportBatch>> = db.importBatchDao().observeAll().map { l -> l.map { it.toDomain() } }

    private fun ImportBatchEntity.toDomain() =
        ImportBatch(id, fileName, fileSha256, importedAt, rowCount, importedCount, skippedCount, accountId, status == REVERTED)

    private companion object {
        const val COMPLETED = "COMPLETED"
        const val REVERTED = "REVERTED"
    }
}
