package com.mymoney.core.data.repository

import androidx.room.withTransaction
import com.mymoney.core.data.db.MyMoneyDatabase
import com.mymoney.core.data.db.dao.EntryDao
import com.mymoney.core.data.db.entity.EntrySplitEntity
import com.mymoney.core.data.db.entity.EntryTagEntity
import com.mymoney.core.domain.common.DomainError
import com.mymoney.core.domain.common.Outcome
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.model.Entry
import com.mymoney.core.domain.model.EntryGroup
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.repository.CategoryAmountRow
import com.mymoney.core.domain.repository.FlowRow
import com.mymoney.core.domain.repository.LedgerRepository
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class LedgerRepositoryImpl @Inject constructor(
    private val db: MyMoneyDatabase,
    private val changeLog: ChangeLogger,
    private val clock: TimeProvider,
) : LedgerRepository {
    private val dao: EntryDao get() = db.entryDao()

    override suspend fun insertGroup(group: EntryGroup, reason: String?): Outcome<Unit> = db.withTransaction {
        // Idempotency (FBR-13): the groupId is created when the form opens; a second save is a no-op.
        if (dao.groupExists(group.groupId)) return@withTransaction Outcome.Failure(DomainError.AlreadySaved)
        writeLegs(group.entries)
        changeLog.log(ChangeLogger.ENTRY_GROUP, group.groupId, if (reason != null) "ADJUST" else "CREATE", reason, after = changeLog.snapshot(group.entries))
        Outcome.Success(Unit)
    }

    override suspend fun replaceGroup(group: EntryGroup, reason: String?): Outcome<Unit> = db.withTransaction {
        val old = loadGroup(group.groupId)
        if (old.isEmpty()) return@withTransaction Outcome.Failure(DomainError.NotFound("operation", group.groupId))
        val now = clock.nowMillis()
        // Keep leg ids stable so refunds keep pointing at an edited expense.
        val reused = assignStableIds(old, group.entries).map { e ->
            val previous = old.firstOrNull { it.id == e.id }
            e.copy(createdAt = previous?.createdAt ?: e.createdAt, updatedAt = now)
        }
        val removedIds = old.map { it.id } - reused.map { it.id }.toSet()
        if (removedIds.isNotEmpty() && dao.liveRefundCount(removedIds) > 0) {
            return@withTransaction Outcome.Failure(DomainError.InUse("refunded expense"))
        }
        val oldIds = old.map { it.id }
        dao.deleteSplits(oldIds)
        dao.deleteTags(oldIds)
        if (removedIds.isNotEmpty()) dao.deleteEntries(removedIds)
        dao.upsertEntries(reused.map { it.toEntity() })
        writeChildren(reused)
        changeLog.log(
            ChangeLogger.ENTRY_GROUP, group.groupId, if (reason != null) "ADJUST" else "UPDATE", reason,
            before = changeLog.snapshot(old), after = changeLog.snapshot(reused),
        )
        Outcome.Success(Unit)
    }

    override suspend fun getGroup(groupId: String): EntryGroup? =
        loadGroup(groupId).takeIf { it.isNotEmpty() }?.let { EntryGroup(groupId, it) }

    override suspend fun getEntry(id: String): Entry? {
        val e = dao.get(id) ?: return null
        return e.toDomain(dao.splitsFor(listOf(id)), dao.tagsFor(listOf(id)).map { it.tagId }.toSet())
    }

    override suspend fun softDeleteGroup(groupId: String) {
        db.withTransaction {
            val legs = dao.groupEntries(groupId)
            dao.softDeleteGroup(groupId, clock.nowMillis())
            changeLog.log(ChangeLogger.ENTRY_GROUP, groupId, "DELETE", before = changeLog.snapshot(legs.map { it.toDomain() }))
        }
    }

    override suspend fun restoreGroup(groupId: String) {
        db.withTransaction {
            dao.restoreGroup(groupId, clock.nowMillis())
            changeLog.log(ChangeLogger.ENTRY_GROUP, groupId, "RESTORE")
        }
    }

    override suspend fun purgeDeletedBefore(cutoffMillis: Long): Int = dao.purgeDeletedBefore(cutoffMillis)

    override suspend fun groupIdsForImport(batchId: String): List<String> = dao.groupIdsForImport(batchId)

    override suspend fun entriesForAccount(accountId: String, from: LocalDate, to: LocalDate): List<Entry> =
        dao.entriesForAccount(accountId, from.toEpochDay(), to.toEpochDay()).map { it.toDomain() }

    override suspend fun refundedTotal(originalEntryId: String, excludingGroupId: String?): Long =
        dao.refundedTotal(originalEntryId, excludingGroupId)

    suspend fun hasLiveRefunds(groupId: String): Boolean {
        val ids = dao.groupEntries(groupId).map { it.id }
        return ids.isNotEmpty() && dao.liveRefundCount(ids) > 0
    }

    override suspend fun findSimilar(
        accountId: String,
        kind: EntryKind,
        amountMinor: Long,
        date: LocalDate,
        excludingGroupId: String?,
    ): List<Entry> = dao.findSimilar(
        accountId, kind, amountMinor, date.minusDays(1).toEpochDay(), date.plusDays(1).toEpochDay(), excludingGroupId,
    ).map { it.toDomain() }

    override suspend fun categoryAmounts(from: LocalDate, to: LocalDate, includePending: Boolean): List<CategoryAmountRow> =
        dao.categoryAmounts(from.toEpochDay(), to.toEpochDay(), includePending).map { it.toDomain() }

    override fun observeCategoryAmounts(from: LocalDate, to: LocalDate): Flow<List<CategoryAmountRow>> =
        dao.observeCategoryAmounts(from.toEpochDay(), to.toEpochDay()).map { rows -> rows.map { it.toDomain() } }

    override suspend fun flows(from: LocalDate, to: LocalDate): List<FlowRow> =
        dao.flows(from.toEpochDay(), to.toEpochDay()).map { it.toDomain() }

    override fun observeChanges(): Flow<Unit> = dao.observeCount().map { }

    override fun observeRecent(limit: Int): Flow<List<Entry>> = dao.observeRecent(limit).map { list -> list.map { it.toDomain() } }

    private suspend fun loadGroup(groupId: String): List<Entry> {
        val legs = dao.groupEntries(groupId)
        if (legs.isEmpty()) return emptyList()
        val ids = legs.map { it.id }
        val splits = dao.splitsFor(ids).groupBy { it.entryId }
        val tags = dao.tagsFor(ids).groupBy({ it.entryId }, { it.tagId })
        return legs.map { it.toDomain(splits[it.id].orEmpty(), tags[it.id].orEmpty().toSet()) }
    }

    private suspend fun writeLegs(entries: List<Entry>) {
        dao.insertEntries(entries.map { it.toEntity() })
        writeChildren(entries)
    }

    private suspend fun writeChildren(entries: List<Entry>) {
        val splits = entries.flatMap { e -> e.splits.map { EntrySplitEntity(it.id, e.id, it.categoryId, it.amountMinor, it.note) } }
        if (splits.isNotEmpty()) dao.insertSplits(splits)
        val tags = entries.flatMap { e -> e.tagIds.map { EntryTagEntity(e.id, it) } }
        if (tags.isNotEmpty()) dao.insertTags(tags)
    }

    /** Matches new legs to old ones by role (kind + direction) and reuses their ids. */
    private fun assignStableIds(old: List<Entry>, new: List<Entry>): List<Entry> {
        val available = old.toMutableList()
        return new.map { n ->
            val match = available.firstOrNull { it.kind == n.kind && (it.amountMinor < 0) == (n.amountMinor < 0) }
            if (match != null) {
                available.remove(match)
                n.copy(id = match.id)
            } else {
                n
            }
        }
    }
}
