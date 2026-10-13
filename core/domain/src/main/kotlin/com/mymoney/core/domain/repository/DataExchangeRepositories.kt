package com.mymoney.core.domain.repository

import kotlinx.coroutines.flow.Flow

/** One CSV import (docs/03 §3.5 import_batch). */
data class ImportBatch(
    val id: String,
    val fileName: String,
    val fileSha256: String,
    val importedAt: Long,
    val rowCount: Int,
    val importedCount: Int,
    val skippedCount: Int,
    val accountId: String?,
    val reverted: Boolean,
)

/** Removes attachment files whose operation was permanently deleted. */
fun interface AttachmentCleaner {
    suspend fun purgeOrphans(): Int
}

interface ImportBatchRepository {
    /** A completed (not reverted) import of the same file content, if any. */
    suspend fun findCompleted(fileSha256: String): ImportBatch?
    suspend fun insert(batch: ImportBatch)
    suspend fun markReverted(id: String)
    fun observeAll(): Flow<List<ImportBatch>>
}
