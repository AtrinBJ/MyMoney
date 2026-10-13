package com.mymoney.core.data.exchange

import androidx.room.withTransaction
import com.mymoney.core.data.attachments.AttachmentStore
import com.mymoney.core.data.backup.BackupManager
import com.mymoney.core.data.db.MyMoneyDatabase
import com.mymoney.core.data.db.SeedData
import com.mymoney.core.data.db.dao.TrashRow
import com.mymoney.core.data.security.PinStore
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.repository.LedgerRepository
import com.mymoney.core.domain.settings.AppSettings
import com.mymoney.core.domain.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Deleted operations stay restorable for [RETENTION_DAYS] days, then the daily job removes them (docs/03 §3.3). */
@Singleton
class TrashRepository @Inject constructor(
    private val db: MyMoneyDatabase,
    private val ledger: LedgerRepository,
    private val attachments: AttachmentStore,
) {
    fun observe(): Flow<List<TrashRow>> = db.entryDao().observeTrashGroups()

    suspend fun restore(groupId: String) = ledger.restoreGroup(groupId)

    suspend fun deletePermanently(groupId: String) {
        db.entryDao().purgeGroup(groupId)
        attachments.purgeOrphans()
    }

    suspend fun empty(): Int {
        val n = ledger.purgeDeletedBefore(Long.MAX_VALUE)
        attachments.purgeOrphans()
        return n
    }

    companion object {
        const val RETENTION_DAYS = 30
    }
}

/**
 * "Delete all data" (docs/SECURITY.md §Delete all data): every table is emptied in one transaction and the
 * reference data re-seeded, then the file is rewritten (VACUUM) so freed pages keep nothing; attachments,
 * local safety backups, the PIN and all settings are removed. The app starts again at onboarding.
 */
@Singleton
class DataWiper @Inject constructor(
    private val db: MyMoneyDatabase,
    private val attachments: AttachmentStore,
    private val backups: BackupManager,
    private val pins: PinStore,
    private val settings: SettingsRepository,
    private val clock: TimeProvider,
) {
    suspend fun wipeAll() = withContext(Dispatchers.IO) {
        val sql = db.openHelper.writableDatabase
        db.withTransaction {
            sql.execSQL("PRAGMA defer_foreign_keys = ON")
            val tables = sql.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' AND name <> 'android_metadata'",
            ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            tables.forEach { sql.execSQL("DELETE FROM `$it`") }
            SeedData.insertReferenceData(sql, clock.nowMillis())
        }
        sql.execSQL("VACUUM")
        attachments.deleteAll()
        backups.deleteLocalCopies()
        pins.clear()
        settings.update { AppSettings() }
    }
}
