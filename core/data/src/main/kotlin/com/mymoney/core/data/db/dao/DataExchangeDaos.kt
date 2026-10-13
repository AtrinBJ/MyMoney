package com.mymoney.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.mymoney.core.data.db.entity.AttachmentEntity
import com.mymoney.core.data.db.entity.BackupRecordEntity
import com.mymoney.core.data.db.entity.ImportBatchEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachment WHERE groupId = :groupId ORDER BY createdAt")
    fun observeFor(groupId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachment WHERE id = :id")
    suspend fun get(id: String): AttachmentEntity?

    @Query("SELECT * FROM attachment")
    suspend fun all(): List<AttachmentEntity>

    @Insert suspend fun insert(e: AttachmentEntity)

    @Query("DELETE FROM attachment WHERE id = :id")
    suspend fun delete(id: String)

    /** Attachments whose operation no longer exists (after a permanent purge). */
    @Query("SELECT * FROM attachment WHERE groupId NOT IN (SELECT DISTINCT groupId FROM entry)")
    suspend fun orphans(): List<AttachmentEntity>
}

@Dao
interface ImportBatchDao {
    @Insert suspend fun insert(e: ImportBatchEntity)

    @Query("SELECT * FROM import_batch WHERE fileSha256 = :sha AND status = 'COMPLETED' ORDER BY importedAt DESC LIMIT 1")
    suspend fun findCompleted(sha: String): ImportBatchEntity?

    @Query("SELECT * FROM import_batch ORDER BY importedAt DESC")
    fun observeAll(): Flow<List<ImportBatchEntity>>

    @Query("UPDATE import_batch SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: String)

    @Query("SELECT * FROM import_batch")
    suspend fun all(): List<ImportBatchEntity>
}

@Dao
interface BackupRecordDao {
    @Upsert suspend fun upsert(e: BackupRecordEntity)

    @Query("SELECT * FROM backup_record ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BackupRecordEntity>>
}
