package com.mymoney.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mymoney.core.data.db.entity.ChangeLogEntity
import com.mymoney.core.data.db.entity.ExchangeRateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RateDao {
    @Query("SELECT * FROM exchange_rate ORDER BY date DESC, baseCode, quoteCode")
    fun observeAll(): Flow<List<ExchangeRateEntity>>

    @Query("SELECT * FROM exchange_rate")
    suspend fun getAll(): List<ExchangeRateEntity>

    /** REPLACE resolves the (base, quote, date, source) unique key: the newest value for that key wins. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rate: ExchangeRateEntity)

    @Query("DELETE FROM exchange_rate WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ChangeLogDao {
    @Insert
    suspend fun insert(entry: ChangeLogEntity)

    @Query("SELECT * FROM change_log WHERE entityType = :type AND entityId = :id ORDER BY at DESC")
    suspend fun history(type: String, id: String): List<ChangeLogEntity>
}
