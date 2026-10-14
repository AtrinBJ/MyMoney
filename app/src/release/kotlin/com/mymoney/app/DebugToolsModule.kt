package com.mymoney.app

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Release build: no developer actions, no seeders. */
@Module
@InstallIn(SingletonComponent::class)
object DebugToolsModule {
    @Provides
    fun provideDebugTools(): DebugTools = object : DebugTools {
        override val actions: List<DebugAction> = emptyList()
    }
}
