package com.mymoney.core.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mymoney.core.data.db.dao.AccountDao
import com.mymoney.core.data.db.dao.AnalyticsDao
import com.mymoney.core.data.db.dao.AssetDao
import com.mymoney.core.data.db.dao.AttachmentDao
import com.mymoney.core.data.db.dao.BackupRecordDao
import com.mymoney.core.data.db.dao.ImportBatchDao
import com.mymoney.core.data.db.dao.BudgetDao
import com.mymoney.core.data.db.dao.GoalDao
import com.mymoney.core.data.db.dao.NotificationLogDao
import com.mymoney.core.data.db.dao.ObligationDao
import com.mymoney.core.data.db.dao.RecurringDao
import com.mymoney.core.data.db.dao.CategoryDao
import com.mymoney.core.data.db.dao.ChangeLogDao
import com.mymoney.core.data.db.dao.CurrencyDao
import com.mymoney.core.data.db.dao.EntryDao
import com.mymoney.core.data.db.dao.RateDao
import com.mymoney.core.data.db.dao.TagDao
import com.mymoney.core.data.db.entity.AccountEntity
import com.mymoney.core.data.db.entity.AssetEntity
import com.mymoney.core.data.db.entity.AssetTradeEntity
import com.mymoney.core.data.db.entity.AssetValuationEntity
import com.mymoney.core.data.db.entity.AttachmentEntity
import com.mymoney.core.data.db.entity.BackupRecordEntity
import com.mymoney.core.data.db.entity.ImportBatchEntity
import com.mymoney.core.data.db.entity.BudgetAlertStateEntity
import com.mymoney.core.data.db.entity.BudgetCategoryEntity
import com.mymoney.core.data.db.entity.BudgetEntity
import com.mymoney.core.data.db.entity.BudgetPeriodOverrideEntity
import com.mymoney.core.data.db.entity.GoalAccountEntity
import com.mymoney.core.data.db.entity.GoalContributionEntity
import com.mymoney.core.data.db.entity.NotificationLogEntity
import com.mymoney.core.data.db.entity.ObligationEntity
import com.mymoney.core.data.db.entity.OccurrenceStateEntity
import com.mymoney.core.data.db.entity.RecurringAmountChangeEntity
import com.mymoney.core.data.db.entity.RecurringRuleEntity
import com.mymoney.core.data.db.entity.SavingsGoalEntity
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
        // v2
        BudgetEntity::class,
        BudgetCategoryEntity::class,
        BudgetPeriodOverrideEntity::class,
        BudgetAlertStateEntity::class,
        RecurringRuleEntity::class,
        RecurringAmountChangeEntity::class,
        OccurrenceStateEntity::class,
        ObligationEntity::class,
        AssetEntity::class,
        AssetTradeEntity::class,
        AssetValuationEntity::class,
        SavingsGoalEntity::class,
        GoalAccountEntity::class,
        GoalContributionEntity::class,
        NotificationLogEntity::class,
        // v3
        AttachmentEntity::class,
        ImportBatchEntity::class,
        BackupRecordEntity::class,
    ],
    views = [CategoryAmountView::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [
        // v1 → v2 only adds tables (stage 4); verified by MigrationTest.
        AutoMigration(from = 1, to = 2),
        // v2 → v3 only adds tables (stage 5); verified by MigrationTest.
        AutoMigration(from = 2, to = 3),
    ],
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
    abstract fun budgetDao(): BudgetDao
    abstract fun recurringDao(): RecurringDao
    abstract fun obligationDao(): ObligationDao
    abstract fun assetDao(): AssetDao
    abstract fun goalDao(): GoalDao
    abstract fun notificationLogDao(): NotificationLogDao
    abstract fun analyticsDao(): AnalyticsDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun importBatchDao(): ImportBatchDao
    abstract fun backupRecordDao(): BackupRecordDao

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
