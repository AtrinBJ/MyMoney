package com.mymoney.core.data.exchange

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.mymoney.core.data.attachments.AttachmentStore
import com.mymoney.core.data.backup.BackupManager
import com.mymoney.core.data.db.MyMoneyDatabase
import com.mymoney.core.data.db.SeedData
import com.mymoney.core.data.prefs.DataStoreSettingsRepository
import com.mymoney.core.data.repository.AccountRepositoryImpl
import com.mymoney.core.data.repository.CategoryRepositoryImpl
import com.mymoney.core.data.repository.ChangeLogger
import com.mymoney.core.data.repository.CurrencyRepositoryImpl
import com.mymoney.core.data.repository.ExchangeRateRepositoryImpl
import com.mymoney.core.data.repository.LedgerRepositoryImpl
import com.mymoney.core.data.security.PinStore
import com.mymoney.core.domain.common.IdGenerator
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.csv.Csv
import com.mymoney.core.domain.model.Account
import com.mymoney.core.domain.model.AccountType
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.settings.IrrUnit
import com.mymoney.core.domain.usecase.IncomeExpenseDraft
import com.mymoney.core.domain.usecase.RecordOperationUseCase
import com.mymoney.core.domain.usecase.TransferDraft
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataExchangeTest {
    private val clock = object : TimeProvider {
        var t = 1_760_000_000_000
        override fun nowMillis() = t++
        override fun today(): LocalDate = LocalDate.of(2026, 10, 14)
    }
    private val ids = object : IdGenerator {
        var n = 0
        override fun newId() = "id-${n++}"
    }
    private lateinit var db: MyMoneyDatabase
    private val root: File = Files.createTempDirectory("x").toFile()
    private val streaming: StreamingAead = run {
        StreamingAeadConfig.register()
        KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM_HKDF_4KB")).getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
    }
    private val aead: Aead = run {
        AeadConfig.register()
        KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM")).getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }
    private val prefs = object : DataStore<Preferences> {
        val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = transform(state.value).also { state.value = it }
    }
    private val settings = DataStoreSettingsRepository(prefs)
    private lateinit var ledger: LedgerRepositoryImpl
    private lateinit var accounts: AccountRepositoryImpl
    private lateinit var record: RecordOperationUseCase
    private lateinit var attachments: AttachmentStore

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MyMoneyDatabase::class.java)
            .addCallback(MyMoneyDatabase.SeedCallback).allowMainThreadQueries().build()
        val log = ChangeLogger(db.changeLogDao(), ids, clock)
        ledger = LedgerRepositoryImpl(db, log, clock)
        accounts = AccountRepositoryImpl(db, log, ids, clock)
        record = RecordOperationUseCase(accounts, CategoryRepositoryImpl(db, log, ids, clock), CurrencyRepositoryImpl(db), ledger, ExchangeRateRepositoryImpl(db), ids, clock)
        attachments = AttachmentStore(File(root, "attachments"), { streaming }, db, ids, clock)
        accounts.create(Account("bank", "بانک, ملی", AccountType.BANK, "IRR", startDate = LocalDate.of(2026, 1, 1)), 10_000_000)
        accounts.create(Account("cash", "Cash", AccountType.CASH, "IRR", startDate = LocalDate.of(2026, 1, 1)), 0)
        record(IncomeExpenseDraft("g1", EntryKind.EXPENSE, "bank", 1_234_565, LocalDate.of(2026, 10, 1), SeedData.categoryId("expense.food"), payee = "=SUM(A1)", note = "line\nbreak"))
        record(TransferDraft("g2", "bank", "cash", 500_000, 500_000, LocalDate.of(2026, 10, 2)))
        Unit
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    private val labels = ExportLabels(category = { key, name -> name ?: "cat:$key" }, kind = { it.lowercase() }, localDate = { it.toString() })

    @Test
    fun `CSV export - one row per leg, BOM, toman unit, injection-safe, re-readable`() = runBlocking {
        val out = ByteArrayOutputStream()
        val n = DataExporter(db).exportCsv(out, ExportFilter(), labels, IrrUnit.TOMAN, mapOf("IRR" to 0))
        assertEquals(4, n) // bank opening balance (cash opened at 0 has none) + expense + 2 transfer legs
        val text = out.toString(Charsets.UTF_8.name())
        assertTrue(text.startsWith("﻿"))
        val rows = Csv.parse(text)
        val header = rows[0]
        val expense = rows.first { it[header.indexOf("type")] == "expense" }
        assertEquals("-123456.5", expense[header.indexOf("amount")]) // exact: 1,234,565 rial = 123,456.5 toman
        assertEquals("toman", expense[header.indexOf("unit")])
        assertEquals("'=SUM(A1)", expense[header.indexOf("payee")])
        assertEquals("line\nbreak", expense[header.indexOf("note")])
        assertEquals("cat:expense.food", expense[header.indexOf("category")])
        assertEquals("بانک, ملی", expense[header.indexOf("account")])
        // Filtered by account and date.
        val only = DataExporter(db).exportCsv(ByteArrayOutputStream(), ExportFilter(from = LocalDate.of(2026, 10, 2), accountIds = setOf("cash")), labels, IrrUnit.RIAL, mapOf("IRR" to 0))
        assertEquals(1, only)
        val json = ByteArrayOutputStream().also { DataExporter(db).exportJson(it, ExportFilter(), labels) }.toString(Charsets.UTF_8.name())
        assertTrue(json.contains("\"amountMinor\": -1234565"))
    }

    @Test
    fun `trash - restore, delete permanently with its attachment, empty`() = runBlocking {
        val trash = TrashRepository(db, ledger, attachments)
        attachments.add("g1", "r.png", "image/png", ByteArray(10).inputStream())
        ledger.softDeleteGroup("g1")
        ledger.softDeleteGroup("g2")
        val rows = trash.observe().first()
        assertEquals(listOf("g2", "g1").toSet(), rows.map { it.groupId }.toSet())
        assertEquals(-500_000, rows.single { it.groupId == "g2" }.amountMinor) // the transfer shows once, as its outgoing leg
        trash.restore("g2")
        assertEquals(10_000_000 - 500_000, accounts.balance("bank"))
        trash.deletePermanently("g1")
        assertEquals(0, db.attachmentDao().all().size)
        assertEquals(0, File(root, "attachments").listFiles()?.size ?: 0)
        assertTrue(trash.observe().first().isEmpty())
        ledger.softDeleteGroup("g2")
        assertEquals(2, trash.empty())
    }

    @Test
    fun `delete all data leaves only reference data and default settings`() = runBlocking {
        settings.update { it.copy(onboardingDone = true, irrUnit = IrrUnit.RIAL) }
        attachments.add("g1", "r.png", "image/png", ByteArray(10).inputStream())
        val pins = PinStore(File(root, "nobackup").apply { mkdirs() }, { aead }, clock)
        pins.setPin("2580")
        val backups = BackupManager(db, attachments, settings, { streaming }, File(root, "cache"), File(root, "backups"), clock, ids, "t")
        backups.safetyBackup()
        val categoriesBefore = db.categoryDao().let { CategoryRepositoryImpl(db, ChangeLogger(db.changeLogDao(), ids, clock), ids, clock).getAll().size }

        DataWiper(db, attachments, backups, pins, settings, clock).wipeAll()

        assertTrue(accounts.getAll().isEmpty())
        assertEquals(0, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM entry").use { it.moveToFirst(); it.getInt(0) })
        assertEquals(categoriesBefore, CategoryRepositoryImpl(db, ChangeLogger(db.changeLogDao(), ids, clock), ids, clock).getAll().size) // seeds back
        assertFalse(pins.hasPin())
        assertFalse(settings.settings.first().onboardingDone)
        assertTrue(backups.safetyBackups().isEmpty())
        assertFalse(File(root, "attachments").exists())
    }

    @Test
    fun `balances at many dates in one query equal the per-date query`() = runBlocking {
        val analytics = com.mymoney.core.data.repository.LedgerAnalyticsImpl(db)
        val dates = listOf(LocalDate.of(2026, 10, 2), LocalDate.of(2025, 12, 31), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 1, 1), LocalDate.of(2030, 1, 1))
        assertEquals(dates.map { analytics.balancesOn(it) }, analytics.balancesAt(dates))
    }
}
