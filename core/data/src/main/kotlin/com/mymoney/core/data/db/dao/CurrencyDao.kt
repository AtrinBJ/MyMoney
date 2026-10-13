package com.mymoney.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mymoney.core.data.db.entity.CurrencyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CurrencyDao {
    @Query("SELECT * FROM currency ORDER BY sortOrder, code")
    fun observeAll(): Flow<List<CurrencyEntity>>

    @Query("SELECT * FROM currency ORDER BY sortOrder, code")
    suspend fun getAll(): List<CurrencyEntity>

    @Query("SELECT * FROM currency WHERE code = :code")
    suspend fun get(code: String): CurrencyEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(currency: CurrencyEntity)

    @Update
    suspend fun update(currency: CurrencyEntity)
}
