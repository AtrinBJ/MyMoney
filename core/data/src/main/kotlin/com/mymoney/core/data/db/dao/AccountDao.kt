package com.mymoney.core.data.db.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mymoney.core.data.db.entity.AccountEntity
import com.mymoney.core.data.db.entity.EntryEntity
import kotlinx.coroutines.flow.Flow

data class AccountBalanceRow(
    @Embedded val account: AccountEntity,
    val balanceMinor: Long,
    val pendingMinor: Long,
)

@Dao
interface AccountDao {
    @Query("SELECT * FROM account WHERE (:includeArchived OR isArchived = 0) ORDER BY isArchived, sortOrder, createdAt")
    fun observeAll(includeArchived: Boolean): Flow<List<AccountEntity>>

    /** Balances are derived in SQL from the ledger (FBR-17) — one query for all accounts. */
    @Query(
        """
        SELECT a.*,
          COALESCE((SELECT SUM(e.amountMinor) FROM entry e
                    WHERE e.accountId = a.id AND e.deletedAt IS NULL AND e.status = 'COMPLETED'), 0) AS balanceMinor,
          COALESCE((SELECT SUM(e.amountMinor) FROM entry e
                    WHERE e.accountId = a.id AND e.deletedAt IS NULL AND e.status = 'PENDING'), 0) AS pendingMinor
        FROM account a
        WHERE (:includeArchived OR a.isArchived = 0)
        ORDER BY a.isArchived, a.sortOrder, a.createdAt
        """,
    )
    fun observeWithBalances(includeArchived: Boolean): Flow<List<AccountBalanceRow>>

    @Query("SELECT * FROM account ORDER BY sortOrder, createdAt")
    suspend fun getAll(): List<AccountEntity>

    @Query("SELECT * FROM account WHERE id = :id")
    suspend fun get(id: String): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(account: AccountEntity)

    @Update
    suspend fun update(account: AccountEntity)

    @Query("UPDATE account SET isArchived = :archived, updatedAt = :now WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean, now: Long)

    @Query("SELECT COUNT(*) FROM entry WHERE accountId = :id AND deletedAt IS NULL")
    suspend fun entryCount(id: String): Int

    @Query(
        """SELECT COALESCE(SUM(amountMinor), 0) FROM entry
           WHERE accountId = :id AND deletedAt IS NULL AND status = 'COMPLETED' AND date <= :upToEpochDay""",
    )
    suspend fun balance(id: String, upToEpochDay: Long): Long

    @Query("SELECT * FROM entry WHERE accountId = :id AND kind = 'OPENING_BALANCE' AND deletedAt IS NULL LIMIT 1")
    suspend fun openingEntry(id: String): EntryEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM account")
    suspend fun nextSortOrder(): Int
}
