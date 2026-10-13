package com.mymoney.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mymoney.core.data.db.dao.AccountDao
import com.mymoney.core.data.db.dao.CategoryDao
import com.mymoney.core.data.db.dao.ChangeLogDao
import com.mymoney.core.data.db.dao.CurrencyDao
import com.mymoney.core.data.db.dao.EntryDao
import com.mymoney.core.data.db.dao.RateDao
import com.mymoney.core.data.db.dao.TagDao
import com.mymoney.core.data.db.entity.AccountEntity
import com.mymoney.core.data.db.entity.CategoryAmountView
import com.mymoney.core.data.db.entity.CategoryEntity
import com.mymoney.core.data.db.entity.ChangeLogEntity
import com.mymoney.core.data.db.entity.CurrencyEntity
import com.mymoney.core.data.db.entity.EntryEntity
import com.mymoney.core.data.db.entity.EntrySplitEntity
import com.mymoney.core.data.db.entity.EntryTagEntity
import com.mymoney.core.data.db.entity.ExchangeRateEntity
import com.mymoney.core.data.db.entity.TagEntity

/**
 * Schema history (docs/03 §3.2). Every version bump needs an explicit Migration plus a test;
 * destructive migration is never allowed.
 */
@Database(
    entities = [
        CurrencyEntity::class,
        ExchangeRateEntity::class,
        AccountEntity::class,
        CategoryEntity::class,
        TagEntity::class,
        EntryEntity::class,
        EntrySplitEntity::class,
        EntryTagEntity::class,
        ChangeLogEntity::class,
    ],
    views = [CategoryAmountView::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MyMoneyDatabase : RoomDatabase() {
    abstract fun currencyDao(): CurrencyDao
    abstract fun rateDao(): RateDao
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun tagDao(): TagDao
    abstract fun entryDao(): EntryDao
    abstract fun changeLogDao(): ChangeLogDao

    /** Seeds reference data the first time the database file is created. */
    object SeedCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            SeedData.insertReferenceData(db)
        }
    }

    companion object {
        const val FILE_NAME = "mymoney.db"
    }
}
