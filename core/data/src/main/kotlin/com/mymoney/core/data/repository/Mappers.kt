package com.mymoney.core.data.repository

import com.mymoney.core.data.db.dao.CategoryAmountRowDb
import com.mymoney.core.data.db.dao.FlowRowDb
import com.mymoney.core.data.db.entity.AccountEntity
import com.mymoney.core.data.db.entity.CategoryEntity
import com.mymoney.core.data.db.entity.CurrencyEntity
import com.mymoney.core.data.db.entity.EntryEntity
import com.mymoney.core.data.db.entity.EntrySplitEntity
import com.mymoney.core.data.db.entity.ExchangeRateEntity
import com.mymoney.core.data.db.entity.TagEntity
import com.mymoney.core.domain.model.Account
import com.mymoney.core.domain.model.Category
import com.mymoney.core.domain.model.Entry
import com.mymoney.core.domain.model.EntrySplit
import com.mymoney.core.domain.model.ExchangeRate
import com.mymoney.core.domain.model.Tag
import com.mymoney.core.domain.money.Currency
import com.mymoney.core.domain.repository.CategoryAmountRow
import com.mymoney.core.domain.repository.FlowRow
import java.time.LocalDate

internal fun Long.toDate(): LocalDate = LocalDate.ofEpochDay(this)

internal fun CurrencyEntity.toDomain() = Currency(code, minorDigits, symbol, nameFa, nameEn, isCustom, isEnabled, sortOrder)
internal fun Currency.toEntity() = CurrencyEntity(code, minorDigits, symbol, nameFa, nameEn, isCustom, isEnabled, sortOrder)

internal fun ExchangeRateEntity.toDomain() = ExchangeRate(id, baseCode, quoteCode, rate, date.toDate(), source, note, createdAt)
internal fun ExchangeRate.toEntity() = ExchangeRateEntity(id, base, quote, rate, date.toEpochDay(), source, note, createdAt)

internal fun AccountEntity.toDomain() = Account(
    id = id, name = name, type = type, currency = currencyCode, institution = institution,
    startDate = startDate.toDate(), note = note, colorKey = colorKey, iconKey = iconKey,
    isArchived = isArchived, includeInNetWorth = includeInNetWorth, includeInLiquid = includeInLiquid,
    creditLimitMinor = creditLimitMinor, sortOrder = sortOrder, createdAt = createdAt, updatedAt = updatedAt,
)

internal fun Account.toEntity() = AccountEntity(
    id = id, name = name, type = type, currencyCode = currency, institution = institution,
    startDate = startDate.toEpochDay(), note = note, colorKey = colorKey, iconKey = iconKey,
    isArchived = isArchived, includeInNetWorth = includeInNetWorth, includeInLiquid = includeInLiquid,
    creditLimitMinor = creditLimitMinor, sortOrder = sortOrder, createdAt = createdAt, updatedAt = updatedAt,
)

internal fun CategoryEntity.toDomain() = Category(
    id, type, parentId, systemKey, name, iconKey, colorKey, defaultCostType, defaultEssential,
    isArchived, sortOrder, createdAt, updatedAt,
)

internal fun Category.toEntity() = CategoryEntity(
    id, type, parentId, systemKey, name, iconKey, colorKey, defaultCostType, defaultEssential,
    isArchived, sortOrder, createdAt, updatedAt,
)

internal fun TagEntity.toDomain() = Tag(id, name, colorKey, createdAt)
internal fun Tag.toEntity() = TagEntity(id, name, colorKey, createdAt)

internal fun EntryEntity.toDomain(splits: List<EntrySplitEntity> = emptyList(), tagIds: Set<String> = emptySet()) = Entry(
    id = id, groupId = groupId, accountId = accountId, kind = kind, amountMinor = amountMinor,
    date = date.toDate(), timeMinutes = timeMinutes, status = status, categoryId = categoryId,
    payee = payee, note = note, costType = costType, essential = essential, relatedEntryId = relatedEntryId,
    recurringRuleId = recurringRuleId, occurrenceDate = occurrenceDate?.toDate(), assetTradeId = assetTradeId,
    importBatchId = importBatchId, createdAt = createdAt, updatedAt = updatedAt, deletedAt = deletedAt,
    splits = splits.map { EntrySplit(it.id, it.categoryId, it.amountMinor, it.note) },
    tagIds = tagIds,
)

internal fun Entry.toEntity() = EntryEntity(
    id = id, groupId = groupId, accountId = accountId, kind = kind, amountMinor = amountMinor,
    date = date.toEpochDay(), timeMinutes = timeMinutes, status = status, categoryId = categoryId,
    payee = payee, note = note, costType = costType, essential = essential, relatedEntryId = relatedEntryId,
    recurringRuleId = recurringRuleId, occurrenceDate = occurrenceDate?.toEpochDay(), assetTradeId = assetTradeId,
    importBatchId = importBatchId, createdAt = createdAt, updatedAt = updatedAt, deletedAt = deletedAt,
)

internal fun CategoryAmountRowDb.toDomain() = CategoryAmountRow(currency, date.toDate(), categoryId, kind, amountMinor)
internal fun FlowRowDb.toDomain() = FlowRow(accountId, currency, date.toDate(), kind, counterpartAccountId, amountMinor)
