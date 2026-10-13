package com.mymoney.core.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.sqlite.db.SimpleSQLiteQuery
import com.mymoney.core.data.db.MyMoneyDatabase
import com.mymoney.core.data.db.dao.EntryListRow
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.model.EntryStatus
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** Filter for the transaction list (docs/05 §5.1 FilterSheet). Empty sets mean "no restriction". */
data class EntryFilter(
    val accountId: String? = null,
    val categoryIds: Set<String> = emptySet(),
    val kinds: Set<EntryKind> = emptySet(),
    val tagId: String? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val minAbsAmountMinor: Long? = null,
    val maxAbsAmountMinor: Long? = null,
    val status: EntryStatus? = null,
    val text: String? = null,
) {
    val isActive: Boolean
        get() = categoryIds.isNotEmpty() || kinds.isNotEmpty() || tagId != null || from != null || to != null ||
            minAbsAmountMinor != null || maxAbsAmountMinor != null || status != null || !text.isNullOrBlank()
}

/**
 * Builds the parameterized SQL for the transaction list. Values are always bound, never concatenated.
 * Without an account filter, the incoming leg of a transfer is hidden so each transfer appears once.
 */
internal object EntryListQuery {
    private const val SELECT = """
SELECT e.id, e.groupId, e.accountId, e.kind, e.amountMinor, e.date, e.timeMinutes, e.status, e.categoryId,
       e.payee, e.note, e.costType, e.relatedEntryId, e.createdAt,
       a.name AS accountName, a.type AS accountType, a.currencyCode AS currency,
       c.name AS categoryName, c.systemKey AS categorySystemKey, c.iconKey AS categoryIconKey, c.colorKey AS categoryColorKey,
       (SELECT a2.name FROM entry e2 JOIN account a2 ON a2.id = e2.accountId
         WHERE e2.groupId = e.groupId AND e2.kind = 'TRANSFER' AND e2.id <> e.id LIMIT 1) AS counterpartAccountName,
       (SELECT COUNT(*) FROM entry_split s WHERE s.entryId = e.id) AS splitCount
FROM entry e
JOIN account a ON a.id = e.accountId
LEFT JOIN category c ON c.id = e.categoryId
WHERE e.deletedAt IS NULL"""

    fun build(filter: EntryFilter, limit: Int? = null): SimpleSQLiteQuery {
        val sql = StringBuilder(SELECT)
        val args = mutableListOf<Any>()
        if (filter.accountId != null) {
            sql.append(" AND e.accountId = ?")
            args += filter.accountId
        } else {
            sql.append(" AND NOT (e.kind = 'TRANSFER' AND e.amountMinor > 0)")
        }
        if (filter.categoryIds.isNotEmpty()) {
            val marks = filter.categoryIds.joinToString(",") { "?" }
            sql.append(" AND (e.categoryId IN ($marks) OR EXISTS (SELECT 1 FROM entry_split s WHERE s.entryId = e.id AND s.categoryId IN ($marks)))")
            args.addAll(filter.categoryIds)
            args.addAll(filter.categoryIds)
        }
        if (filter.kinds.isNotEmpty()) {
            sql.append(" AND e.kind IN (${filter.kinds.joinToString(",") { "?" }})")
            args.addAll(filter.kinds.map { it.name })
        }
        filter.tagId?.let {
            sql.append(" AND EXISTS (SELECT 1 FROM entry_tag t WHERE t.entryId = e.id AND t.tagId = ?)")
            args += it
        }
        filter.from?.let { sql.append(" AND e.date >= ?"); args += it.toEpochDay() }
        filter.to?.let { sql.append(" AND e.date <= ?"); args += it.toEpochDay() }
        filter.minAbsAmountMinor?.let { sql.append(" AND ABS(e.amountMinor) >= ?"); args += it }
        filter.maxAbsAmountMinor?.let { sql.append(" AND ABS(e.amountMinor) <= ?"); args += it }
        filter.status?.let { sql.append(" AND e.status = ?"); args += it.name }
        filter.text?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            val like = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            sql.append(" AND (e.payee LIKE ? ESCAPE '\\' OR e.note LIKE ? ESCAPE '\\' OR c.name LIKE ? ESCAPE '\\' OR a.name LIKE ? ESCAPE '\\')")
            repeat(4) { args += like }
        }
        sql.append(" ORDER BY e.date DESC, COALESCE(e.timeMinutes, 1440) DESC, e.createdAt DESC, e.id DESC")
        limit?.let { sql.append(" LIMIT ?"); args += it }
        return SimpleSQLiteQuery(sql.toString(), args.toTypedArray())
    }
}

/** Read-side queries that return Android paging types; used directly by the presentation layer. */
@Singleton
class EntryQueries @Inject constructor(private val db: MyMoneyDatabase) {

    fun paged(filter: EntryFilter): Flow<PagingData<EntryListRow>> = Pager(
        config = PagingConfig(pageSize = 50, prefetchDistance = 30, enablePlaceholders = false, initialLoadSize = 80),
        pagingSourceFactory = { db.entryDao().pagingSource(EntryListQuery.build(filter)) },
    ).flow

    suspend fun list(filter: EntryFilter, limit: Int): List<EntryListRow> = db.entryDao().list(EntryListQuery.build(filter, limit))

    fun observeTrashCount(): Flow<List<com.mymoney.core.data.db.entity.EntryEntity>> = db.entryDao().observeTrash()
}
