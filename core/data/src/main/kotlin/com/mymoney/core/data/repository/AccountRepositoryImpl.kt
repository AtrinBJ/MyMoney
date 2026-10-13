package com.mymoney.core.data.repository

import androidx.room.withTransaction
import com.mymoney.core.data.db.MyMoneyDatabase
import com.mymoney.core.data.db.entity.EntryEntity
import com.mymoney.core.domain.common.IdGenerator
import com.mymoney.core.domain.common.Outcome
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.model.Account
import com.mymoney.core.domain.model.AccountWithBalance
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.model.EntryStatus
import com.mymoney.core.domain.repository.AccountRepository
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class AccountRepositoryImpl @Inject constructor(
    private val db: MyMoneyDatabase,
    private val changeLog: ChangeLogger,
    private val ids: IdGenerator,
    private val clock: TimeProvider,
) : AccountRepository {
    private val dao get() = db.accountDao()

    override fun observeAccounts(includeArchived: Boolean): Flow<List<Account>> =
        dao.observeAll(includeArchived).map { list -> list.map { it.toDomain() } }

    override fun observeWithBalances(includeArchived: Boolean): Flow<List<AccountWithBalance>> =
        dao.observeWithBalances(includeArchived).map { rows ->
            rows.map { AccountWithBalance(it.account.toDomain(), it.balanceMinor, it.pendingMinor) }
        }

    override suspend fun getAll(): List<Account> = dao.getAll().map { it.toDomain() }

    override suspend fun get(id: String): Account? = dao.get(id)?.toDomain()

    override suspend fun create(account: Account, openingBalanceMinor: Long): Outcome<Unit> = db.withTransaction {
        val withOrder = account.copy(sortOrder = if (account.sortOrder == 0) dao.nextSortOrder() else account.sortOrder)
        dao.insert(withOrder.toEntity())
        if (openingBalanceMinor != 0L) db.entryDao().insertEntries(listOf(openingEntry(withOrder, openingBalanceMinor)))
        changeLog.log(ChangeLogger.ACCOUNT, account.id, "CREATE", after = "opening=$openingBalanceMinor")
        Outcome.Success(Unit)
    }

    override suspend fun update(account: Account, openingBalanceMinor: Long): Outcome<Unit> = db.withTransaction {
        dao.update(account.toEntity())
        val existing = dao.openingEntry(account.id)
        val entryDao = db.entryDao()
        when {
            existing == null && openingBalanceMinor != 0L ->
                entryDao.insertEntries(listOf(openingEntry(account, openingBalanceMinor)))
            existing != null && openingBalanceMinor == 0L ->
                entryDao.deleteEntries(listOf(existing.id))
            existing != null &&
                (existing.amountMinor != openingBalanceMinor || existing.date != account.startDate.toEpochDay()) ->
                entryDao.upsertEntries(
                    listOf(
                        existing.copy(
                            amountMinor = openingBalanceMinor,
                            date = account.startDate.toEpochDay(),
                            updatedAt = clock.nowMillis(),
                        ),
                    ),
                )
        }
        changeLog.log(
            ChangeLogger.ACCOUNT, account.id, "UPDATE",
            before = existing?.let { "opening=${it.amountMinor}" }, after = "opening=$openingBalanceMinor",
        )
        Outcome.Success(Unit)
    }

    override suspend fun setArchived(id: String, archived: Boolean) {
        db.withTransaction {
            dao.setArchived(id, archived, clock.nowMillis())
            changeLog.log(ChangeLogger.ACCOUNT, id, if (archived) "ARCHIVE" else "UNARCHIVE")
        }
    }

    override suspend fun entryCount(id: String): Int = dao.entryCount(id)

    override suspend fun openingBalance(id: String): Long = dao.openingEntry(id)?.amountMinor ?: 0L

    override suspend fun balance(id: String, upTo: LocalDate?): Long =
        dao.balance(id, (upTo ?: LocalDate.of(9999, 12, 31)).toEpochDay())

    private fun openingEntry(account: Account, amount: Long): EntryEntity {
        val now = clock.nowMillis()
        return EntryEntity(
            id = ids.newId(), groupId = ids.newId(), accountId = account.id, kind = EntryKind.OPENING_BALANCE,
            amountMinor = amount, date = account.startDate.toEpochDay(), timeMinutes = null, status = EntryStatus.COMPLETED,
            categoryId = null, payee = null, note = null, costType = null, essential = null, relatedEntryId = null,
            recurringRuleId = null, occurrenceDate = null, assetTradeId = null, importBatchId = null,
            createdAt = now, updatedAt = now, deletedAt = null,
        )
    }
}
