package com.mymoney.core.domain.model

import java.time.LocalDate

/** Economic nature of a ledger leg (docs/04 table A). Renaming constants is forbidden: they are stored. */
enum class EntryKind {
    OPENING_BALANCE,
    INCOME,
    EXPENSE,
    REFUND,
    TRANSFER,
    ADJUSTMENT,
    ASSET_BUY,
    ASSET_SELL,
    WRITE_OFF,
}

enum class EntryStatus { COMPLETED, PENDING }

/**
 * One movement of money on one account (a "leg"). An operation such as a transfer with a fee is several
 * entries sharing a [groupId]. Amount is signed in the account's currency: positive = money in.
 */
data class Entry(
    val id: String,
    val groupId: String,
    val accountId: String,
    val kind: EntryKind,
    val amountMinor: Long,
    val date: LocalDate,
    val timeMinutes: Int? = null,
    val status: EntryStatus = EntryStatus.COMPLETED,
    val categoryId: String? = null,
    val payee: String? = null,
    val note: String? = null,
    val costType: CostType? = null,
    val essential: Boolean? = null,
    val relatedEntryId: String? = null,
    val recurringRuleId: String? = null,
    val occurrenceDate: LocalDate? = null,
    val assetTradeId: String? = null,
    val importBatchId: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val deletedAt: Long? = null,
    val splits: List<EntrySplit> = emptyList(),
    val tagIds: Set<String> = emptySet(),
)

/** Part of an income/expense entry allocated to one category. Amount is positive, in the entry's currency. */
data class EntrySplit(
    val id: String,
    val categoryId: String,
    val amountMinor: Long,
    val note: String? = null,
)

/** All legs of one operation; saved, edited and deleted atomically (FBR-13). */
data class EntryGroup(
    val groupId: String,
    val entries: List<Entry>,
) {
    init {
        require(entries.isNotEmpty()) { "a group needs at least one entry" }
        require(entries.all { it.groupId == groupId }) { "all entries must share the groupId" }
    }
}
