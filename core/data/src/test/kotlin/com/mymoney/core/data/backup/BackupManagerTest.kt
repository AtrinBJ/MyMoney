package com.mymoney.core.data.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.mymoney.core.data.attachments.AttachmentStore
import com.mymoney.core.data.db.MyMoneyDatabase
import com.mymoney.core.data.db.SeedData
import com.mymoney.core.data.prefs.DataStoreSettingsRepository
import com.mymoney.core.data.repository.AccountRepositoryImpl
import com.mymoney.core.data.repository.CategoryRepositoryImpl
import com.mymoney.core.data.repository.ChangeLogger
import com.mymoney.core.data.repository.CurrencyRepositoryImpl
import com.mymoney.core.data.repository.ExchangeRateRepositoryImpl
import com.mymoney.core.data.repository.LedgerRepositoryImpl
import com.mymoney.core.domain.common.IdGenerator
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.model.Account
import com.mymoney.core.domain.model.AccountType
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.settings.CalendarType
import com.mymoney.core.domain.settings.IrrUnit
import com.mymoney.core.domain.usecase.IncomeExpenseDraft
import com.mymoney.core.domain.usecase.RecordOperationUseCase
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupManagerTest {
    private val clock = object : TimeProvider {
        var t = 1_760_000_000_000
        override fun nowMillis() = t++
        override fun today(): LocalDate = LocalDate.of(2026, 10, 14)
    }
    private val ids = object : IdGenerator {
        var n = 0
        override fun newId() = "id-${n++}"
    }

    /** One simulated device: its own database, files, settings and device key. */
    private inner class Device {
        val db: MyMoneyDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MyMoneyDatabase::class.java)
            .addCallback(MyMoneyDatabase.SeedCallback).allowMainThreadQueries().build()
        val root: File = Files.createTempDirectory("device").toFile()
        val key: StreamingAead = run {
            StreamingAeadConfig.register()
            KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM_HKDF_4KB")).getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
        }
        val prefs = object : DataStore<Preferences> {
            val state = MutableStateFlow(emptyPreferences())
            override val data: Flow<Preferences> = state
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                transform(state.value).also { state.value = it }
        }
        val settings = DataStoreSettingsRepository(prefs)
        val attachments = AttachmentStore(File(root, "files/attachments"), { key }, db, ids, clock)
        val backups = BackupManager(db, attachments, settings, { key }, File(root, "cache/backup"), File(root, "files/backups"), clock, ids, "test")
        private val log = ChangeLogger(db.changeLogDao(), ids, clock)
        val accounts = AccountRepositoryImpl(db, log, ids, clock)
        val ledger = LedgerRepositoryImpl(db, log, clock)
        val record = RecordOperationUseCase(accounts, CategoryRepositoryImpl(db, log, ids, clock), CurrencyRepositoryImpl(db), ledger, ExchangeRateRepositoryImpl(db), ids, clock)
    }

    private val devices = mutableListOf<Device>()
    private fun device() = Device().also { devices += it }

    @After
    fun tearDown() = devices.forEach { it.db.close(); it.root.deleteRecursively() }

    private suspend fun Device.seed() {
        settings.update { it.copy(onboardingDone = true, calendar = CalendarType.GREGORIAN, irrUnit = IrrUnit.RIAL) }
        accounts.create(Account("bank", "بانک ملت", AccountType.BANK, "IRR", startDate = LocalDate.of(2026, 1, 1)), 100_000_000)
        accounts.create(Account("eur", "Euro", AccountType.BANK, "EUR", startDate = LocalDate.of(2026, 1, 1)), 12_345)
        repeat(30) { i ->
            record(IncomeExpenseDraft("g$i", EntryKind.EXPENSE, "bank", 1_000_000L + i, LocalDate.of(2026, 9, 1).plusDays(i.toLong()), SeedData.categoryId("expense.food"), payee = "خرید $i"))
        }
        attachments.add("g3", "receipt.jpg", "image/jpeg", ByteArray(100_000) { (it % 251).toByte() }.inputStream())
    }

    private fun bytesOf(block: (java.io.OutputStream) -> Unit): ByteArray = ByteArrayOutputStream().also(block).toByteArray()

    @Test
    fun `password backup restores everything on another device`() = runBlocking {
        val a = device()
        a.seed()
        val file = bytesOf { runBlocking { a.backups.export(it, "correct horse".toCharArray()) } }
        assertTrue(a.settings.settings.first().lastBackupAtMillis != null)
        // The ledger text is not visible in an encrypted backup.
        assertTrue(!String(file, Charsets.ISO_8859_1).contains("payee"))

        val b = device()
        assertIs<InspectResult.NeedsPassword>(b.backups.inspect({ file.inputStream() }, null))
        assertIs<InspectResult.WrongPassword>(b.backups.inspect({ file.inputStream() }, "wrong".toCharArray()))
        val ready = assertIs<InspectResult.Ready>(b.backups.inspect({ file.inputStream() }, "correct horse".toCharArray()))
        assertEquals(30, ready.summary.entries - 2) // 30 expenses + 2 opening balances
        assertEquals(1, ready.summary.attachments)

        b.backups.restore(ready)
        assertEquals(a.accounts.balance("bank"), b.accounts.balance("bank"))
        assertEquals(12_345, b.accounts.balance("eur"))
        assertEquals("خرید 7", b.ledger.getGroup("g7")!!.entries.single().payee)
        val att = b.db.attachmentDao().all().single()
        assertTrue(b.attachments.open(att).use { it.readBytes() }.contentEquals(ByteArray(100_000) { (it % 251).toByte() }))
        assertEquals(CalendarType.GREGORIAN, b.settings.settings.first().calendar)
        assertEquals(IrrUnit.RIAL, b.settings.settings.first().irrUnit)
        // A safety copy of b's previous data was kept, encrypted with b's device key.
        assertEquals(1, b.backups.safetyBackups().size)
    }

    @Test
    fun `plain backup round trip and tamper detection`() = runBlocking {
        val a = device()
        a.seed()
        val file = bytesOf { runBlocking { a.backups.export(it, null) } }
        val b = device()
        assertIs<InspectResult.Ready>(b.backups.inspect({ file.inputStream() }, null))

        assertIs<InspectResult.NotABackup>(b.backups.inspect({ "hello".toByteArray().inputStream() }, null))
        val newer = file.copyOf().also { it[4] = 9 }
        assertIs<InspectResult.NewerVersion>(b.backups.inspect({ newer.inputStream() }, null))
        // Flip a byte inside the data: the checksum (or the ZIP CRC) catches it.
        val tampered = file.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        val r = b.backups.inspect({ tampered.inputStream() }, null)
        assertTrue(r is InspectResult.Corrupt || r is InspectResult.NotABackup, "got $r")
    }

    @Test
    fun `a failing restore leaves the current data untouched`() = runBlocking {
        val a = device()
        a.seed()
        val file = bytesOf { runBlocking { a.backups.export(it, null) } }
        val b = device()
        b.accounts.create(Account("mine", "Mine", AccountType.CASH, "IRR", startDate = LocalDate.of(2026, 1, 1)), 777)
        val ready = assertIs<InspectResult.Ready>(b.backups.inspect({ file.inputStream() }, null))
        // Make the verification inside the restore transaction fail (row counts no longer match the manifest).
        val manifestCounts = ready.manifest.counts.toMutableMap().apply { put("entry", (get("entry") ?: 0) + 1) }
        val broken = InspectResult.Ready(ready.manifest.copy(counts = manifestCounts), ready.summary, ready.staging)
        val failed = runCatching { b.backups.restore(broken) }
        assertTrue(failed.isFailure)
        assertEquals(777, b.accounts.balance("mine"))
        assertEquals(1, b.accounts.getAll().size)
        assertEquals(0, b.db.attachmentDao().all().size)
    }
}
