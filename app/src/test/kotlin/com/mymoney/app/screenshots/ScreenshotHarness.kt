package com.mymoney.app.screenshots

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mymoney.core.data.db.MyMoneyDatabase
import com.mymoney.core.data.prefs.DataStoreSettingsRepository
import com.mymoney.core.data.repository.AccountRepositoryImpl
import com.mymoney.core.data.repository.CategoryRepositoryImpl
import com.mymoney.core.data.repository.ChangeLogger
import com.mymoney.core.data.repository.CurrencyRepositoryImpl
import com.mymoney.core.data.repository.EntryQueries
import com.mymoney.core.data.repository.ExchangeRateRepositoryImpl
import com.mymoney.core.data.repository.LedgerRepositoryImpl
import com.mymoney.core.data.repository.TagRepositoryImpl
import com.mymoney.core.designsystem.format.Formats
import com.mymoney.core.designsystem.format.LocalFormats
import com.mymoney.core.designsystem.theme.MyMoneyTheme
import com.mymoney.core.domain.common.IdGenerator
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.settings.AppLanguage
import com.mymoney.core.domain.settings.AppSettings
import com.mymoney.core.domain.usecase.RecordOperationUseCase
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

/**
 * Test-only wiring of the real data layer on an in-memory database, plus helpers to render a screen
 * in a given language/theme and save it as PNG under build/screenshots (visual QA without a device).
 */
class ScreenshotHarness {
    val clock = object : TimeProvider {
        var t = 1_760_000_000_000
        override fun nowMillis() = t++
        override fun today(): LocalDate = LocalDate.of(2026, 10, 13)
    }
    val ids = object : IdGenerator {
        var n = 0
        override fun newId() = "id-${n++}"
    }
    val prefs = object : DataStore<Preferences> {
        val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
    val db: MyMoneyDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MyMoneyDatabase::class.java)
        .addCallback(MyMoneyDatabase.SeedCallback)
        .allowMainThreadQueries()
        .build()
    private val log = ChangeLogger(db.changeLogDao(), ids, clock)
    val ledger = LedgerRepositoryImpl(db, log, clock)
    val accounts = AccountRepositoryImpl(db, log, ids, clock)
    val categories = CategoryRepositoryImpl(db, log, ids, clock)
    val currencies = CurrencyRepositoryImpl(db)
    val rates = ExchangeRateRepositoryImpl(db)
    val tags = TagRepositoryImpl(db, ids, clock)
    val settings = DataStoreSettingsRepository(prefs)
    val queries = EntryQueries(db)
    val record = RecordOperationUseCase(accounts, categories, currencies, ledger, rates, ids, clock)
    val budgets = com.mymoney.core.data.repository.BudgetRepositoryImpl(db, ids, clock)
    val recurring = com.mymoney.core.data.repository.RecurringRepositoryImpl(db, ids, clock)
    val obligations = com.mymoney.core.data.repository.ObligationRepositoryImpl(db)
    val assets = com.mymoney.core.data.repository.AssetRepositoryImpl(db, clock)
    val goals = com.mymoney.core.data.repository.GoalRepositoryImpl(db, ids, clock)
    val notificationLog = com.mymoney.core.data.repository.NotificationLogRepositoryImpl(db)
    val analytics = com.mymoney.core.data.repository.LedgerAnalyticsImpl(db)
    val tx = com.mymoney.core.data.repository.RoomTransactionRunner(db)
    val payOccurrence = com.mymoney.core.domain.usecase.PayOccurrenceUseCase(recurring, record, tx, clock)
    val provider = com.mymoney.app.data.FinanceSnapshotProvider(
        settings, currencies, rates, accounts, categories, budgets, recurring, obligations, assets, goals, clock,
    )

    fun formats(language: AppLanguage, settings: AppSettings = AppSettings.defaultsFor(language)): Formats = runBlocking {
        Formats(settings, language, currencies.getAll().associateBy { it.code })
    }

    fun close() = db.close()
}

@Composable
fun ScreenFrame(formats: Formats, dark: Boolean = false, glass: Boolean = true, content: @Composable () -> Unit) {
    val dir = if (formats.language == AppLanguage.FA) LayoutDirection.Rtl else LayoutDirection.Ltr
    MyMoneyTheme(darkTheme = dark, glass = glass) {
        CompositionLocalProvider(LocalFormats provides formats, LocalLayoutDirection provides dir) {
            androidx.compose.foundation.layout.Box {
                com.mymoney.core.designsystem.theme.AmbientBackground()
                content()
            }
        }
    }
}

fun ComposeContentTestRule.saveScreenshot(name: String) {
    waitForIdle()
    val bitmap = onRoot().captureToImage().asAndroidBitmap()
    val dir = File("build/screenshots").apply { mkdirs() }
    File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}
