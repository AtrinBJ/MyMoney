package com.mymoney.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.mymoney.core.data.db.MyMoneyDatabase
import com.mymoney.core.data.db.dao.ChangeLogDao
import com.mymoney.core.data.prefs.DataStoreSettingsRepository
import com.mymoney.core.data.repository.AccountRepositoryImpl
import com.mymoney.core.data.repository.CategoryRepositoryImpl
import com.mymoney.core.data.repository.CurrencyRepositoryImpl
import com.mymoney.core.data.repository.ExchangeRateRepositoryImpl
import com.mymoney.core.data.repository.LedgerRepositoryImpl
import com.mymoney.core.data.repository.TagRepositoryImpl
import com.mymoney.core.data.repository.AssetRepositoryImpl
import com.mymoney.core.data.repository.BudgetRepositoryImpl
import com.mymoney.core.data.repository.GoalRepositoryImpl
import com.mymoney.core.data.repository.LedgerAnalyticsImpl
import com.mymoney.core.data.repository.NotificationLogRepositoryImpl
import com.mymoney.core.data.repository.ObligationRepositoryImpl
import com.mymoney.core.data.repository.RecurringRepositoryImpl
import com.mymoney.core.data.repository.RoomTransactionRunner
import com.mymoney.core.domain.repository.AssetRepository
import com.mymoney.core.domain.repository.BudgetRepository
import com.mymoney.core.domain.repository.GoalRepository
import com.mymoney.core.domain.repository.LedgerAnalytics
import com.mymoney.core.domain.repository.NotificationLogRepository
import com.mymoney.core.domain.repository.ObligationRepository
import com.mymoney.core.domain.repository.RecurringRepository
import com.mymoney.core.domain.repository.TransactionRunner
import com.mymoney.core.data.security.DbKeyProvider
import com.mymoney.core.domain.common.IdGenerator
import com.mymoney.core.domain.common.SystemTimeProvider
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.common.UuidV7Generator
import com.mymoney.core.domain.repository.AccountRepository
import com.mymoney.core.domain.repository.CategoryRepository
import com.mymoney.core.domain.repository.CurrencyRepository
import com.mymoney.core.domain.repository.ExchangeRateRepository
import com.mymoney.core.domain.repository.LedgerRepository
import com.mymoney.core.domain.repository.TagRepository
import com.mymoney.core.domain.settings.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataProvidesModule {

    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile("settings") })

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        keyProvider: DbKeyProvider,
    ): MyMoneyDatabase {
        // Native library + Keystore key are loaded on first use, off the main thread (DeferredOpenHelperFactory).
        return Room.databaseBuilder(context, MyMoneyDatabase::class.java, MyMoneyDatabase.FILE_NAME)
            .openHelperFactory(com.mymoney.core.data.db.DeferredOpenHelperFactory { keyProvider.sqlCipherPassphrase() })
            .addCallback(MyMoneyDatabase.SeedCallback)
            .build()
    }

    @Provides
    fun provideChangeLogDao(db: MyMoneyDatabase): ChangeLogDao = db.changeLogDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindsModule {
    @Binds abstract fun bindSettingsRepository(impl: DataStoreSettingsRepository): SettingsRepository

    @Binds @Singleton abstract fun bindTimeProvider(impl: SystemTimeProvider): TimeProvider

    @Binds @Singleton abstract fun bindIdGenerator(impl: UuidV7Generator): IdGenerator

    @Binds abstract fun bindCurrencyRepository(impl: CurrencyRepositoryImpl): CurrencyRepository

    @Binds abstract fun bindRateRepository(impl: ExchangeRateRepositoryImpl): ExchangeRateRepository

    @Binds abstract fun bindAccountRepository(impl: AccountRepositoryImpl): AccountRepository

    @Binds abstract fun bindCategoryRepository(impl: CategoryRepositoryImpl): CategoryRepository

    @Binds abstract fun bindTagRepository(impl: TagRepositoryImpl): TagRepository

    @Binds abstract fun bindLedgerRepository(impl: LedgerRepositoryImpl): LedgerRepository

    @Binds abstract fun bindTransactionRunner(impl: RoomTransactionRunner): TransactionRunner

    @Binds abstract fun bindBudgetRepository(impl: BudgetRepositoryImpl): BudgetRepository

    @Binds abstract fun bindRecurringRepository(impl: RecurringRepositoryImpl): RecurringRepository

    @Binds abstract fun bindObligationRepository(impl: ObligationRepositoryImpl): ObligationRepository

    @Binds abstract fun bindAssetRepository(impl: AssetRepositoryImpl): AssetRepository

    @Binds abstract fun bindGoalRepository(impl: GoalRepositoryImpl): GoalRepository

    @Binds abstract fun bindNotificationLog(impl: NotificationLogRepositoryImpl): NotificationLogRepository

    @Binds abstract fun bindLedgerAnalytics(impl: LedgerAnalyticsImpl): LedgerAnalytics

    @Binds abstract fun bindAttachmentCleaner(impl: com.mymoney.core.data.attachments.AttachmentStore): com.mymoney.core.domain.repository.AttachmentCleaner

    @Binds abstract fun bindImportBatches(impl: com.mymoney.core.data.repository.ImportBatchRepositoryImpl): com.mymoney.core.domain.repository.ImportBatchRepository
}
