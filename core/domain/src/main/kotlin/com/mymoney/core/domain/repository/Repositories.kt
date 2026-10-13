package com.mymoney.core.domain.repository

import com.mymoney.core.domain.common.Outcome
import com.mymoney.core.domain.model.Account
import com.mymoney.core.domain.model.AccountWithBalance
import com.mymoney.core.domain.model.Category
import com.mymoney.core.domain.model.CategoryType
import com.mymoney.core.domain.model.Entry
import com.mymoney.core.domain.model.EntryGroup
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.model.ExchangeRate
import com.mymoney.core.domain.model.Tag
import com.mymoney.core.domain.money.Currency
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

interface CurrencyRepository {
    fun observeAll(): Flow<List<Currency>>
    suspend fun getAll(): List<Currency>
    suspend fun get(code: String): Currency?
    suspend fun addCustom(currency: Currency): Outcome<Unit>
    suspend fun setEnabled(code: String, enabled: Boolean)
    suspend fun isInUse(code: String): Boolean
}

interface ExchangeRateRepository {
    fun observeAll(): Flow<List<ExchangeRate>>
    suspend fun getAll(): List<ExchangeRate>
    suspend fun save(rate: ExchangeRate): Outcome<Unit>
    suspend fun delete(id: String)
}

interface AccountRepository {
    fun observeAccounts(includeArchived: Boolean = false): Flow<List<Account>>
    fun observeWithBalances(includeArchived: Boolean = false): Flow<List<AccountWithBalance>>
    suspend fun getAll(): List<Account>
    suspend fun get(id: String): Account?

    /** Creates the account and, when non-zero, its OPENING_BALANCE entry in one transaction (FBR-33). */
    suspend fun create(account: Account, openingBalanceMinor: Long): Outcome<Unit>

    /** Updates account fields and the opening balance (replacing its entry) atomically. */
    suspend fun update(account: Account, openingBalanceMinor: Long): Outcome<Unit>
    suspend fun setArchived(id: String, archived: Boolean)
    suspend fun entryCount(id: String): Int
    suspend fun openingBalance(id: String): Long

    /** Balance from COMPLETED entries up to and including [upTo] (FBR-17). */
    suspend fun balance(id: String, upTo: LocalDate? = null): Long
}

interface CategoryRepository {
    fun observe(type: CategoryType? = null, includeArchived: Boolean = false): Flow<List<Category>>
    suspend fun getAll(): List<Category>
    suspend fun get(id: String): Category?
    suspend fun getBySystemKey(key: String): Category?
    suspend fun save(category: Category): Outcome<Unit>
    suspend fun setArchived(id: String, archived: Boolean): Outcome<Unit>

    /** Deletes a category; when it is in use, entries are first moved to [reassignTo]. */
    suspend fun delete(id: String, reassignTo: String?): Outcome<Unit>
    suspend fun usageCount(id: String): Int
}

interface TagRepository {
    fun observeAll(): Flow<List<Tag>>
    suspend fun save(tag: Tag): Outcome<Unit>
    suspend fun delete(id: String)
}

/** Summed amounts for income-statement style reports, one row per (currency, day, category, kind). */
data class CategoryAmountRow(
    val currency: String,
    val date: LocalDate,
    val categoryId: String?,
    val kind: EntryKind,
    val amountMinor: Long,
)

/** Summed amounts for cash-flow style reports, one row per (account, day, kind, counterpart type). */
data class FlowRow(
    val accountId: String,
    val currency: String,
    val date: LocalDate,
    val kind: EntryKind,
    val counterpartAccountId: String?,
    val amountMinor: Long,
)

interface LedgerRepository {
    /**
     * Saves a new operation atomically. Returns [com.mymoney.core.domain.common.DomainError.AlreadySaved]
     * when an operation with the same groupId exists (FBR-13).
     */
    suspend fun insertGroup(group: EntryGroup, reason: String? = null): Outcome<Unit>

    /** Replaces all legs of an existing operation atomically and records the change (FBR-11). */
    suspend fun replaceGroup(group: EntryGroup, reason: String? = null): Outcome<Unit>
    suspend fun getGroup(groupId: String): EntryGroup?
    suspend fun getEntry(id: String): Entry?
    suspend fun softDeleteGroup(groupId: String)
    suspend fun restoreGroup(groupId: String)
    suspend fun purgeDeletedBefore(cutoffMillis: Long): Int

    /** Sum of refunds linked to [originalEntryId], optionally excluding one operation (when editing it). */
    suspend fun refundedTotal(originalEntryId: String, excludingGroupId: String? = null): Long

    /** FBR-36 candidates: same account, kind and amount within ±1 day. */
    suspend fun findSimilar(accountId: String, kind: EntryKind, amountMinor: Long, date: LocalDate, excludingGroupId: String?): List<Entry>

    suspend fun categoryAmounts(from: LocalDate, to: LocalDate, includePending: Boolean = false): List<CategoryAmountRow>
    fun observeCategoryAmounts(from: LocalDate, to: LocalDate): Flow<List<CategoryAmountRow>>
    suspend fun flows(from: LocalDate, to: LocalDate): List<FlowRow>

    /** Emits whenever any entry changes; used to refresh derived views cheaply. */
    fun observeChanges(): Flow<Unit>
    fun observeRecent(limit: Int): Flow<List<Entry>>
}
