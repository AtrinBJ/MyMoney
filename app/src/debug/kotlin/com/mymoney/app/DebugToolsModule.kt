package com.mymoney.app

import com.mymoney.core.data.debug.DevDataSeeder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Debug build: QA helpers. All generated data is clearly labelled TEST / آزمایشی. */
@Module
@InstallIn(SingletonComponent::class)
object DebugToolsModule {
    @Provides
    fun provideDebugTools(seeder: DevDataSeeder): DebugTools = object : DebugTools {
        override val actions = listOf(
            DebugAction("Add 500 TEST transactions") { "Added ${seeder.seedPerformanceData(500, seed = 7)} rows" },
            DebugAction("Add 100,000 TEST transactions (performance)") { "Added ${seeder.seedPerformanceData(100_000)} rows" },
        )
    }
}
