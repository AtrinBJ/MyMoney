package com.mymoney.core.data.repository

import com.mymoney.core.data.db.dao.ChangeLogDao
import com.mymoney.core.data.db.entity.ChangeLogEntity
import com.mymoney.core.domain.common.IdGenerator
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.model.Entry
import javax.inject.Inject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Writes the audit trail (docs/03 change_log, FBR-15). Snapshots are compact JSON of the economic fields
 * only; they live inside the encrypted database and are never logged to logcat.
 */
class ChangeLogger @Inject constructor(
    private val dao: ChangeLogDao,
    private val ids: IdGenerator,
    private val clock: TimeProvider,
) {
    suspend fun log(type: String, entityId: String, action: String, reason: String? = null, before: String? = null, after: String? = null) {
        dao.insert(ChangeLogEntity(ids.newId(), type, entityId, action, clock.nowMillis(), reason, before, after))
    }

    fun snapshot(entries: List<Entry>): String = buildJsonArray {
        entries.forEach { e ->
            add(
                buildJsonObject {
                    put("a", e.accountId)
                    put("k", e.kind.name)
                    put("m", e.amountMinor)
                    put("d", e.date.toString())
                    put("s", e.status.name)
                    e.categoryId?.let { put("c", it) }
                    if (e.splits.isNotEmpty()) put("sp", e.splits.joinToString(";") { "${it.categoryId}:${it.amountMinor}" })
                },
            )
        }
    }.toString()

    companion object {
        const val ENTRY_GROUP = "ENTRY_GROUP"
        const val ACCOUNT = "ACCOUNT"
        const val CATEGORY = "CATEGORY"
    }
}
