package com.mymoney.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mymoney.core.data.db.dao.CurrencyDao
import com.mymoney.core.data.db.entity.CurrencyEntity

@Database(
    entities = [CurrencyEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class MyMoneyDatabase : RoomDatabase() {
    abstract fun currencyDao(): CurrencyDao

    /** Seeds reference data the first time the database file is created. */
    object SeedCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            SeedData.insertCurrencies(db)
        }
    }

    companion object {
        const val FILE_NAME = "mymoney.db"
    }
}
