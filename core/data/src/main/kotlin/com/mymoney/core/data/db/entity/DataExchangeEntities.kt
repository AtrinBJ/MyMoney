package com.mymoney.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Schema v3 additions (docs/03 §3.5).

/**
 * An encrypted file attached to an operation. Linked to the entry group (not a single leg) so editing an
 * operation never orphans its receipt; files of permanently deleted groups are removed by the purge.
 */
@Entity(tableName = "attachment", indices = [Index(value = ["groupId"])])
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    /** File name under filesDir/attachments (random, no user data). */
    val storedName: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val createdAt: Long,
)

@Entity(tableName = "import_batch", indices = [Index(value = ["fileSha256"])])
data class ImportBatchEntity(
    @PrimaryKey val id: String,
    val fileName: String,
    val fileSha256: String,
    val importedAt: Long,
    val rowCount: Int,
    val importedCount: Int,
    val skippedCount: Int,
    val accountId: String?,
    /** COMPLETED or REVERTED. */
    val status: String,
)

@Entity(tableName = "backup_record")
data class BackupRecordEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val fileName: String,
    val sizeBytes: Long,
    val encrypted: Boolean,
    val formatVersion: Int,
    val entryCount: Int,
    /** MANUAL or SAFETY. */
    val kind: String,
)
