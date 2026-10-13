package com.mymoney.app.di

import com.mymoney.app.locale.AppCompatLocaleController
import com.mymoney.app.locale.LocaleController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds
    abstract fun bindLocaleController(impl: AppCompatLocaleController): LocaleController
}
