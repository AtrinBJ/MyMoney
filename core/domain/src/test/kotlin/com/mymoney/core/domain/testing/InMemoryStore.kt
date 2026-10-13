package com.mymoney.core.domain.testing

import com.mymoney.core.domain.common.DomainError
import com.mymoney.core.domain.common.IdGenerator
import com.mymoney.core.domain.common.Outcome
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.model.Account
import com.mymoney.core.domain.model.AccountWithBalance
import com.mymoney.core.domain.model.Category
import com.mymoney.core.domain.model.CategoryType
import com.mymoney.core.domain.model.Entry
import com.mymoney.core.domain.model.EntryGroup
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.model.EntryStatus
import com.mymoney.core.domain.model.ExchangeRate
import com.mymoney.core.domain.model.SystemCategories
import com.mymoney.core.domain.model.Tag
import com.mymoney.core.domain.money.Currency
import com.mymoney.core.domain.repository.AccountRepository
import com.mymoney.core.domain.repository.CategoryAmountRow
import com.mymoney.core.domain.repository.CategoryRepository
import com.mymoney.core.domain.repository.CurrencyRepository
import com.mymoney.core.domain.repository.ExchangeRateRepository
import com.mymoney.core.domain.repository.FlowRow
import com.mymoney.core.domain.repository.LedgerRepository
import com.mymoney.core.domain.repository.TagRepository
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FixedClock(var millis: Long = 1_760_000_000_000, var date: LocalDate = LocalDate.of(2026, 10, 13)) : TimeProvider {
    override fun nowMillis() = millis
    override fun today() = date
}

class SequentialIds(private val prefix: String = "id") : IdGenerator {
    private var n = 0
    override fun newId() = "$prefix-${n++}"
}

/**
 * Behavioural twin of the Room repositories, used to test use cases and ledger invariants on the JVM.
 * Semantics (balances, soft delete, idempotency) mirror docs/03 and docs/04.
 */
class InMemoryStore {
    val currencies = mutableMapOf(
        "IRR" to Currency("IRR", 0, "ریال", "ریال", "Rial"),
        "USD" to Currency("USD", 2, "$", "دلار", "US Dollar"),
        "EUR" to Currency("EUR", 2, "€", "یورو", "Euro"),
        "JPY" to Currency("JPY", 0, "¥", "ین", "Yen"),
        "KWD" to Currency("KWD", 3, "KD", "دینار", "Kuwaiti Dinar"),
    )
    val accounts = linkedMapOf<String, Account>()
    val categories = linkedMapOf<String, Category>()
    val entries = linkedMapOf<String, Entry>()
    val rates = mutableListOf<ExchangeRate>()
    val tags = linkedMapOf<String, Tag>()
    val changeReasons = mutableListOf<String?>()
    private val version = MutableStateFlow(0)
    private fun bump() { version.value++ }

    init {
        listOf(
            Category("cat-food", CategoryType.EXPENSE, name = "Food"),
            Category("cat-rent", CategoryType.EXPENSE, name = "Rent"),
            Category("cat-fees", CategoryType.EXPENSE, systemKey = SystemCategories.FEES),
            Category("cat-interest", CategoryType.EXPENSE, systemKey = SystemCategories.INTEREST),
            Category("cat-salary", CategoryType.INCOME, name = "Salary"),
            Category("cat-gift", CategoryType.INCOME, name = "Gift"),
        ).forEach { categories[it.id] = it }
    }

    private fun live() = entries.values.filter { it.deletedAt == null }

    fun balanceOf(accountId: String, upTo: LocalDate? = null, includePending: Boolean = false): Long =
        live().filter { it.accountId == accountId && (includePending || it.status == EntryStatus.COMPLETED) && (upTo == null || !it.date.isAfter(upTo)) }
            .sumOf { it.amountMinor }

    val currencyRepo = object : CurrencyRepository {
        override fun observeAll(): Flow<List<Currency>> = version.map { currencies.values.toList() }
        override suspend fun getAll() = currencies.values.toList()
        override suspend fun get(code: String) = currencies[code]
        override suspend fun addCustom(currency: Currency): Outcome<Unit> { currencies[currency.code] = currency; return Outcome.Success(Unit) }
        override suspend fun setEnabled(code: String, enabled: Boolean) { currencies[code] = currencies.getValue(code).copy(isEnabled = enabled) }
        override suspend fun isInUse(code: String) = accounts.values.any { it.currency == code }
    }

    val rateRepo = object : ExchangeRateRepository {
        override fun observeAll(): Flow<List<ExchangeRate>> = version.map { rates.toList() }
        override suspend fun getAll() = rates.toList()
        override suspend fun save(rate: ExchangeRate): Outcome<Unit> {
            rates.removeAll { it.base == rate.base && it.quote == rate.quote && it.date == rate.date && it.source == rate.source }
            rates += rate
            bump()
            return Outcome.Success(Unit)
        }
        override suspend fun delete(id: String) { rates.removeAll { it.id == id } }
    }

    val accountRepo = object : AccountRepository {
        override fun observeAccounts(includeArchived: Boolean): Flow<List<Account>> =
            version.map { accounts.values.filter { includeArchived || !it.isArchived } }
        override fun observeWithBalances(includeArchived: Boolean): Flow<List<AccountWithBalance>> = version.map {
            accounts.values.filter { includeArchived || !it.isArchived }.map {
                AccountWithBalance(it, balanceOf(it.id), balanceOf(it.id, includePending = true) - balanceOf(it.id))
            }
        }
        override suspend fun getAll() = accounts.values.toList()
        override suspend fun get(id: String) = accounts[id]
        override suspend fun create(account: Account, openingBalanceMinor: Long): Outcome<Unit> {
            accounts[account.id] = account
            if (openingBalanceMinor != 0L) {
                val e = Entry("ob-${account.id}", "obg-${account.id}", account.id, EntryKind.OPENING_BALANCE, openingBalanceMinor, account.startDate)
                entries[e.id] = e
            }
            bump()
            return Outcome.Success(Unit)
        }
        override suspend fun update(account: Account, openingBalanceMinor: Long): Outcome<Unit> {
            accounts[account.id] = account
            entries.remove("ob-${account.id}")
            if (openingBalanceMinor != 0L) {
                entries["ob-${account.id}"] = Entry("ob-${account.id}", "obg-${account.id}", account.id, EntryKind.OPENING_BALANCE, openingBalanceMinor, account.startDate)
            }
            bump()
            return Outcome.Success(Unit)
        }
        override suspend fun setArchived(id: String, archived: Boolean) { accounts[id] = accounts.getValue(id).copy(isArchived = archived) }
        override suspend fun entryCount(id: String) = live().count { it.accountId == id }
        override suspend fun openingBalance(id: String) = entries["ob-$id"]?.amountMinor ?: 0L
        override suspend fun balance(id: String, upTo: LocalDate?) = balanceOf(id, upTo)
    }

    val categoryRepo = object : CategoryRepository {
        override fun observe(type: CategoryType?, includeArchived: Boolean): Flow<List<Category>> =
            version.map { categories.values.filter { (type == null || it.type == type) && (includeArchived || !it.isArchived) } }
        override suspend fun getAll() = categories.values.toList()
        override suspend fun get(id: String) = categories[id]
        override suspend fun getBySystemKey(key: String) = categories.values.firstOrNull { it.systemKey == key }
        override suspend fun save(category: Category): Outcome<Unit> { categories[category.id] = category; return Outcome.Success(Unit) }
        override suspend fun setArchived(id: String, archived: Boolean): Outcome<Unit> { categories[id] = categories.getValue(id).copy(isArchived = archived); return Outcome.Success(Unit) }
        override suspend fun delete(id: String, reassignTo: String?): Outcome<Unit> { categories.remove(id); return Outcome.Success(Unit) }
        override suspend fun usageCount(id: String) = live().count { it.categoryId == id || it.splits.any { s -> s.categoryId == id } }
    }

    val tagRepo = object : TagRepository {
        override fun observeAll(): Flow<List<Tag>> = version.map { tags.values.toList() }
        override suspend fun save(tag: Tag): Outcome<Unit> { tags[tag.id] = tag; return Outcome.Success(Unit) }
        override suspend fun delete(id: String) { tags.remove(id) }
    }

    val ledgerRepo = object : LedgerRepository {
        override suspend fun insertGroup(group: EntryGroup, reason: String?): Outcome<Unit> {
            if (entries.values.any { it.groupId == group.groupId }) return Outcome.Failure(DomainError.AlreadySaved)
            group.entries.forEach { entries[it.id] = it }
            changeReasons += reason
            bump()
            return Outcome.Success(Unit)
        }
        override suspend fun replaceGroup(group: EntryGroup, reason: String?): Outcome<Unit> {
            entries.values.removeAll { it.groupId == group.groupId }
            group.entries.forEach { entries[it.id] = it }
            changeReasons += reason
            bump()
            return Outcome.Success(Unit)
        }
        override suspend fun getGroup(groupId: String): EntryGroup? =
            entries.values.filter { it.groupId == groupId && it.deletedAt == null }.takeIf { it.isNotEmpty() }?.let { EntryGroup(groupId, it) }
        override suspend fun getEntry(id: String) = entries[id]?.takeIf { it.deletedAt == null }
        override suspend fun softDeleteGroup(groupId: String) {
            entries.replaceAll { _, e -> if (e.groupId == groupId) e.copy(deletedAt = 1) else e }
            bump()
        }
        override suspend fun restoreGroup(groupId: String) {
            entries.replaceAll { _, e -> if (e.groupId == groupId) e.copy(deletedAt = null) else e }
            bump()
        }
        override suspend fun purgeDeletedBefore(cutoffMillis: Long): Int {
            val before = entries.size
            entries.values.removeAll { it.deletedAt != null && it.deletedAt < cutoffMillis }
            return before - entries.size
        }
        override suspend fun groupIdsForImport(batchId: String) = live().filter { it.importBatchId == batchId }.map { it.groupId }.distinct()
        override suspend fun entriesForAccount(accountId: String, from: LocalDate, to: LocalDate) =
            live().filter { it.accountId == accountId && it.date in from..to }
        override suspend fun refundedTotal(originalEntryId: String, excludingGroupId: String?) =
            live().filter { it.kind == EntryKind.REFUND && it.relatedEntryId == originalEntryId && it.groupId != excludingGroupId }.sumOf { it.amountMinor }
        override suspend fun findSimilar(accountId: String, kind: EntryKind, amountMinor: Long, date: LocalDate, excludingGroupId: String?) =
            live().filter {
                it.accountId == accountId && it.kind == kind && it.amountMinor == amountMinor &&
                    !it.date.isBefore(date.minusDays(1)) && !it.date.isAfter(date.plusDays(1)) && it.groupId != excludingGroupId
            }
        override suspend fun categoryAmounts(from: LocalDate, to: LocalDate, includePending: Boolean): List<CategoryAmountRow> =
            live().filter { !it.date.isBefore(from) && !it.date.isAfter(to) && (includePending || it.status == EntryStatus.COMPLETED) }
                .flatMap { e ->
                    val cur = accounts.getValue(e.accountId).currency
                    if (e.splits.isEmpty()) {
                        listOf(CategoryAmountRow(cur, e.date, e.categoryId, e.kind, e.amountMinor))
                    } else {
                        e.splits.map { s -> CategoryAmountRow(cur, e.date, s.categoryId, e.kind, if (e.amountMinor < 0) -s.amountMinor else s.amountMinor) }
                    }
                }
        override fun observeCategoryAmounts(from: LocalDate, to: LocalDate): Flow<List<CategoryAmountRow>> = version.map { categoryAmounts(from, to) }
        override suspend fun flows(from: LocalDate, to: LocalDate): List<FlowRow> =
            live().filter { !it.date.isBefore(from) && !it.date.isAfter(to) && it.status == EntryStatus.COMPLETED }.map { e ->
                val counterpart = if (e.kind == EntryKind.TRANSFER) live().firstOrNull { it.groupId == e.groupId && it.kind == EntryKind.TRANSFER && it.id != e.id }?.accountId else null
                FlowRow(e.accountId, accounts.getValue(e.accountId).currency, e.date, e.kind, counterpart, e.amountMinor)
            }
        override fun observeChanges(): Flow<Unit> = version.map { }
        override fun observeRecent(limit: Int): Flow<List<Entry>> = version.map { live().sortedByDescending { it.date }.take(limit) }
    }
}
