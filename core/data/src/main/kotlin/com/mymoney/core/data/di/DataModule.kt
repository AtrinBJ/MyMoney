package com.mymoney.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.mymoney.core.data.db.MyMoneyDatabase
import com.mymoney.core.data.db.dao.CurrencyDao
import com.mymoney.core.data.prefs.DataStoreSettingsRepository
import com.mymoney.core.data.security.DbKeyProvider
import com.mymoney.core.domain.common.IdGenerator
import com.mymoney.core.domain.common.SystemTimeProvider
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.common.UuidV7Generator
import com.mymoney.core.domain.settings.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

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
        System.loadLibrary("sqlcipher")
        return Room.databaseBuilder(context, MyMoneyDatabase::class.java, MyMoneyDatabase.FILE_NAME)
            .openHelperFactory(SupportOpenHelperFactory(keyProvider.sqlCipherPassphrase()))
            .addCallback(MyMoneyDatabase.SeedCallback)
            .build()
    }

    @Provides
    fun provideCurrencyDao(db: MyMoneyDatabase): CurrencyDao = db.currencyDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindsModule {
    @Binds
    abstract fun bindSettingsRepository(impl: DataStoreSettingsRepository): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindTimeProvider(impl: SystemTimeProvider): TimeProvider

    @Binds
    @Singleton
    abstract fun bindIdGenerator(impl: UuidV7Generator): IdGenerator
}
