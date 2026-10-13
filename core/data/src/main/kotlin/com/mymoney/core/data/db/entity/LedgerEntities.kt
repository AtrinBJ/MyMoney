package com.mymoney.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.DatabaseView
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mymoney.core.domain.model.AccountType
import com.mymoney.core.domain.model.CategoryType
import com.mymoney.core.domain.model.CostType
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.model.EntryStatus
import com.mymoney.core.domain.model.RateSource
import java.math.BigDecimal

// Schema v1 (docs/03-data-model.md §3.3). Enums are stored by name; dates as epoch days; money as minor units.

@Entity(
    tableName = "exchange_rate",
    foreignKeys = [
        ForeignKey(entity = CurrencyEntity::class, parentColumns = ["code"], childColumns = ["baseCode"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = CurrencyEntity::class, parentColumns = ["code"], childColumns = ["quoteCode"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["baseCode", "quoteCode", "date", "source"], unique = true),
        Index(value = ["quoteCode"]),
    ],
)
data class ExchangeRateEntity(
    @PrimaryKey val id: String,
    val baseCode: String,
    val quoteCode: String,
    val rate: BigDecimal,
    val date: Long,
    val source: RateSource,
    val note: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "account",
    foreignKeys = [
        ForeignKey(entity = CurrencyEntity::class, parentColumns = ["code"], childColumns = ["currencyCode"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["isArchived", "sortOrder"]), Index(value = ["currencyCode"])],
)
data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: AccountType,
    val currencyCode: String,
    val institution: String?,
    val startDate: Long,
    val note: String?,
    val colorKey: String?,
    val iconKey: String?,
    @ColumnInfo(defaultValue = "0") val isArchived: Boolean,
    @ColumnInfo(defaultValue = "1") val includeInNetWorth: Boolean,
    val includeInLiquid: Boolean,
    val creditLimitMinor: Long?,
    @ColumnInfo(defaultValue = "0") val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "category",
    foreignKeys = [
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["parentId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["type", "isArchived", "sortOrder"]),
        Index(value = ["parentId"]),
        Index(value = ["systemKey"], unique = true),
    ],
)
data class CategoryEntity(
    @PrimaryKey val id: String,
    val type: CategoryType,
    val parentId: String?,
    val systemKey: String?,
    val name: String?,
    val iconKey: String?,
    val colorKey: String?,
    val defaultCostType: CostType?,
    val defaultEssential: Boolean?,
    @ColumnInfo(defaultValue = "0") val isArchived: Boolean,
    @ColumnInfo(defaultValue = "0") val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "tag", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val colorKey: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "entry",
    foreignKeys = [
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = EntryEntity::class, parentColumns = ["id"], childColumns = ["relatedEntryId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [
        Index(value = ["accountId", "deletedAt", "date"]),
        Index(value = ["date"]),
        Index(value = ["categoryId", "date"]),
        Index(value = ["groupId"]),
        Index(value = ["relatedEntryId"]),
        Index(value = ["recurringRuleId", "occurrenceDate"]),
        Index(value = ["importBatchId"]),
    ],
)
data class EntryEntity(
    @PrimaryKey val id: String,
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
    val essential: Boolean?,
    val relatedEntryId: String?,
    val recurringRuleId: String?,
    val occurrenceDate: Long?,
    val assetTradeId: String?,
    val importBatchId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)

@Entity(
    tableName = "entry_split",
    foreignKeys = [
        ForeignKey(entity = EntryEntity::class, parentColumns = ["id"], childColumns = ["entryId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["entryId"]), Index(value = ["categoryId"])],
)
data class EntrySplitEntity(
    @PrimaryKey val id: String,
    val entryId: String,
    val categoryId: String,
    val amountMinor: Long,
    val note: String?,
)

@Entity(
    tableName = "entry_tag",
    primaryKeys = ["entryId", "tagId"],
    foreignKeys = [
        ForeignKey(entity = EntryEntity::class, parentColumns = ["id"], childColumns = ["entryId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TagEntity::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["tagId"])],
)
data class EntryTagEntity(
    val entryId: String,
    val tagId: String,
)

@Entity(tableName = "change_log", indices = [Index(value = ["entityType", "entityId", "at"])])
data class ChangeLogEntity(
    @PrimaryKey val id: String,
    val entityType: String,
    val entityId: String,
    val action: String,
    val at: Long,
    val reason: String?,
    val beforeJson: String?,
    val afterJson: String?,
)

/** Category-level amounts with splits expanded; the only source for category reports (docs/03 §3.3). */
@DatabaseView(
    viewName = "v_category_amount",
    value = """
SELECT e.id AS entryId, e.groupId AS groupId, e.accountId AS accountId, e.kind AS kind, e.status AS status, e.date AS date,
       COALESCE(s.categoryId, e.categoryId) AS categoryId,
       CASE WHEN s.id IS NULL THEN e.amountMinor
            WHEN e.amountMinor < 0 THEN -s.amountMinor ELSE s.amountMinor END AS amountMinor,
       e.relatedEntryId AS relatedEntryId
FROM entry e LEFT JOIN entry_split s ON s.entryId = e.id
WHERE e.deletedAt IS NULL""",
)
data class CategoryAmountView(
    val entryId: String,
    val groupId: String,
    val accountId: String,
    val kind: EntryKind,
    val status: EntryStatus,
    val date: Long,
    val categoryId: String?,
    val amountMinor: Long,
    val relatedEntryId: String?,
)
