package com.mymoney.core.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import com.mymoney.core.data.db.entity.AccountEntity
import com.mymoney.core.data.db.entity.CategoryEntity
import com.mymoney.core.data.db.entity.EntryEntity
import com.mymoney.core.data.db.entity.EntrySplitEntity
import com.mymoney.core.data.db.entity.EntryTagEntity
import com.mymoney.core.domain.model.AccountType
import com.mymoney.core.domain.model.CostType
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.model.EntryStatus
import kotlinx.coroutines.flow.Flow

data class CategoryAmountRowDb(
    val currency: String,
    val date: Long,
    val categoryId: String?,
    val kind: EntryKind,
    val amountMinor: Long,
)

data class FlowRowDb(
    val accountId: String,
    val currency: String,
    val date: Long,
    val kind: EntryKind,
    val counterpartAccountId: String?,
    val amountMinor: Long,
)

/** One row of the transaction list, with everything the row needs to render (no N+1 queries). */
data class EntryListRow(
    val id: String,
    val groupId: String,
    val accountId: String,
    val kind: EntryKind,
    val amountMinor: Long,
    val date: Long,
    val timeMinutes: Int?,
    val status: EntryStatus,
    val categoryId: String?,
    val payee: String?,
    val note: String?,
    val costType: CostType?,
    val relatedEntryId: String?,
    val createdAt: Long,
    val accountName: String,
    val accountType: AccountType,
    val currency: String,
    val categoryName: String?,
    val categorySystemKey: String?,
    val categoryIconKey: String?,
    val categoryColorKey: String?,
    val counterpartAccountName: String?,
    val splitCount: Int,
)

@Dao
interface EntryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEntries(entries: List<EntryEntity>)

    @Upsert
    suspend fun upsertEntries(entries: List<EntryEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSplits(splits: List<EntrySplitEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTags(tags: List<EntryTagEntity>)

    @Query("SELECT EXISTS(SELECT 1 FROM entry WHERE groupId = :groupId)")
    suspend fun groupExists(groupId: String): Boolean

    @Query("SELECT * FROM entry WHERE groupId = :groupId AND deletedAt IS NULL ORDER BY amountMinor")
    suspend fun groupEntries(groupId: String): List<EntryEntity>

    @Query("SELECT * FROM entry WHERE groupId = :groupId ORDER BY amountMinor")
    suspend fun groupEntriesIncludingDeleted(groupId: String): List<EntryEntity>

    @Query("SELECT * FROM entry WHERE id = :id AND deletedAt IS NULL")
    suspend fun get(id: String): EntryEntity?

    @Query("SELECT * FROM entry_split WHERE entryId IN (:entryIds)")
    suspend fun splitsFor(entryIds: List<String>): List<EntrySplitEntity>

    @Query("SELECT * FROM entry_tag WHERE entryId IN (:entryIds)")
    suspend fun tagsFor(entryIds: List<String>): List<EntryTagEntity>

    @Query("DELETE FROM entry_split WHERE entryId IN (:entryIds)")
    suspend fun deleteSplits(entryIds: List<String>)

    @Query("DELETE FROM entry_tag WHERE entryId IN (:entryIds)")
    suspend fun deleteTags(entryIds: List<String>)

    @Query("DELETE FROM entry WHERE id IN (:ids)")
    suspend fun deleteEntries(ids: List<String>)

    @Query("UPDATE entry SET deletedAt = :at, updatedAt = :at WHERE groupId = :groupId AND deletedAt IS NULL")
    suspend fun softDeleteGroup(groupId: String, at: Long)

    @Query("UPDATE entry SET deletedAt = NULL, updatedAt = :at WHERE groupId = :groupId")
    suspend fun restoreGroup(groupId: String, at: Long)

    @Query("SELECT DISTINCT groupId FROM entry WHERE importBatchId = :batchId AND deletedAt IS NULL")
    suspend fun groupIdsForImport(batchId: String): List<String>

    @Query("SELECT * FROM entry WHERE accountId = :accountId AND deletedAt IS NULL AND date BETWEEN :from AND :to")
    suspend fun entriesForAccount(accountId: String, from: Long, to: Long): List<EntryEntity>

    @Query("DELETE FROM entry WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun purgeDeletedBefore(cutoff: Long): Int

    @Query(
        """SELECT COALESCE(SUM(amountMinor), 0) FROM entry
           WHERE kind = 'REFUND' AND relatedEntryId = :originalId AND deletedAt IS NULL
             AND (:excludingGroupId IS NULL OR groupId <> :excludingGroupId)""",
    )
    suspend fun refundedTotal(originalId: String, excludingGroupId: String?): Long

    @Query("SELECT COUNT(*) FROM entry WHERE kind = 'REFUND' AND relatedEntryId IN (:originalIds) AND deletedAt IS NULL")
    suspend fun liveRefundCount(originalIds: List<String>): Int

    @Query(
        """SELECT * FROM entry
           WHERE accountId = :accountId AND kind = :kind AND amountMinor = :amount AND deletedAt IS NULL
             AND date BETWEEN :fromDay AND :toDay
             AND (:excludingGroupId IS NULL OR groupId <> :excludingGroupId)
           LIMIT 10""",
    )
    suspend fun findSimilar(accountId: String, kind: EntryKind, amount: Long, fromDay: Long, toDay: Long, excludingGroupId: String?): List<EntryEntity>

    @Query(
        """SELECT a.currencyCode AS currency, v.date AS date, v.categoryId AS categoryId, v.kind AS kind,
                  SUM(v.amountMinor) AS amountMinor
           FROM v_category_amount v JOIN account a ON a.id = v.accountId
           WHERE v.date BETWEEN :fromDay AND :toDay
             AND v.kind IN ('INCOME', 'EXPENSE', 'REFUND')
             AND (v.status = 'COMPLETED' OR (:includePending AND v.status = 'PENDING'))
           GROUP BY a.currencyCode, v.date, v.categoryId, v.kind""",
    )
    suspend fun categoryAmounts(fromDay: Long, toDay: Long, includePending: Boolean): List<CategoryAmountRowDb>

    @Query(
        """SELECT a.currencyCode AS currency, v.date AS date, v.categoryId AS categoryId, v.kind AS kind,
                  SUM(v.amountMinor) AS amountMinor
           FROM v_category_amount v JOIN account a ON a.id = v.accountId
           WHERE v.date BETWEEN :fromDay AND :toDay
             AND v.kind IN ('INCOME', 'EXPENSE', 'REFUND') AND v.status = 'COMPLETED'
           GROUP BY a.currencyCode, v.date, v.categoryId, v.kind""",
    )
    fun observeCategoryAmounts(fromDay: Long, toDay: Long): Flow<List<CategoryAmountRowDb>>

    @Query(
        """SELECT e.accountId AS accountId, a.currencyCode AS currency, e.date AS date, e.kind AS kind,
                  (SELECT e2.accountId FROM entry e2
                    WHERE e2.groupId = e.groupId AND e2.kind = 'TRANSFER' AND e2.id <> e.id AND e2.deletedAt IS NULL
                    LIMIT 1) AS counterpartAccountId,
                  SUM(e.amountMinor) AS amountMinor
           FROM entry e JOIN account a ON a.id = e.accountId
           WHERE e.deletedAt IS NULL AND e.status = 'COMPLETED' AND e.date BETWEEN :fromDay AND :toDay
           GROUP BY e.accountId, e.date, e.kind, counterpartAccountId""",
    )
    suspend fun flows(fromDay: Long, toDay: Long): List<FlowRowDb>

    @Query("SELECT COUNT(*) FROM entry")
    fun observeCount(): Flow<Int>

    @Query(
        """SELECT * FROM entry WHERE deletedAt IS NULL AND NOT (kind = 'TRANSFER' AND amountMinor > 0)
           ORDER BY date DESC, COALESCE(timeMinutes, 1440) DESC, createdAt DESC LIMIT :limit""",
    )
    fun observeRecent(limit: Int): Flow<List<EntryEntity>>

    /** Filtered transaction list; the SQL is built by [com.mymoney.core.data.repository.EntryListQuery]. */
    @RawQuery(observedEntities = [EntryEntity::class, AccountEntity::class, CategoryEntity::class, EntrySplitEntity::class, EntryTagEntity::class])
    fun pagingSource(query: SupportSQLiteQuery): PagingSource<Int, EntryListRow>

    @RawQuery(observedEntities = [EntryEntity::class, AccountEntity::class, CategoryEntity::class, EntrySplitEntity::class, EntryTagEntity::class])
    suspend fun list(query: SupportSQLiteQuery): List<EntryListRow>

    @Query("SELECT * FROM entry WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeTrash(): Flow<List<EntryEntity>>

    /** One row per deleted operation (the outgoing leg of a transfer represents it). */
    @Query(
        """SELECT e.groupId AS groupId, e.kind AS kind, e.amountMinor AS amountMinor, e.date AS date, e.deletedAt AS deletedAt,
                  e.payee AS payee, a.name AS accountName, a.currencyCode AS currency, c.name AS categoryName, c.systemKey AS categorySystemKey
           FROM entry e JOIN account a ON a.id = e.accountId LEFT JOIN category c ON c.id = e.categoryId
           WHERE e.deletedAt IS NOT NULL
             AND e.id = (SELECT e2.id FROM entry e2 WHERE e2.groupId = e.groupId
                         ORDER BY (e2.kind = 'TRANSFER' AND e2.amountMinor > 0), e2.id LIMIT 1)
           ORDER BY e.deletedAt DESC""",
    )
    fun observeTrashGroups(): Flow<List<TrashRow>>

    @Query("DELETE FROM entry WHERE groupId = :groupId AND deletedAt IS NOT NULL")
    suspend fun purgeGroup(groupId: String): Int
}

data class TrashRow(
    val groupId: String,
    val kind: EntryKind,
    val amountMinor: Long,
    val date: Long,
    val deletedAt: Long,
    val payee: String?,
    val accountName: String,
    val currency: String,
    val categoryName: String?,
    val categorySystemKey: String?,
)
