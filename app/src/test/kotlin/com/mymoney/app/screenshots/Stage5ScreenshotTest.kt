package com.mymoney.app.screenshots

import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mymoney.app.feature.data.BackupScreen
import com.mymoney.app.feature.data.DataViewModel
import com.mymoney.app.feature.data.DeleteAllScreen
import com.mymoney.app.feature.data.ExportScreen
import com.mymoney.app.feature.data.ImportScreen
import com.mymoney.app.feature.data.ImportViewModel
import com.mymoney.app.feature.data.ReportPdfWriter
import com.mymoney.app.feature.data.TrashScreen
import com.mymoney.app.feature.settings.SettingsContent
import com.mymoney.app.feature.settings.SettingsUiState
import com.mymoney.app.lock.AppLockManager
import com.mymoney.app.lock.LockScreen
import com.mymoney.app.lock.LockViewModel
import com.mymoney.app.lock.PinSetupScreen
import com.mymoney.app.lock.PinSetupViewModel
import com.mymoney.app.locale.AppCompatLocaleController
import com.mymoney.core.data.attachments.AttachmentStore
import com.mymoney.core.data.backup.BackupManager
import com.mymoney.core.data.db.SeedData
import com.mymoney.core.data.exchange.DataExporter
import com.mymoney.core.data.exchange.DataWiper
import com.mymoney.core.data.exchange.ExportFilter
import com.mymoney.core.data.exchange.TrashRepository
import com.mymoney.core.data.repository.ImportBatchRepositoryImpl
import com.mymoney.core.data.security.CryptoProvider
import com.mymoney.core.data.security.PinStore
import com.mymoney.core.domain.model.Account
import com.mymoney.core.domain.model.AccountType
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.settings.AppLanguage
import com.mymoney.core.domain.settings.AppSettings
import com.mymoney.core.domain.usecase.ImportCsvUseCase
import com.mymoney.core.domain.usecase.IncomeExpenseDraft
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Stage-5 screens with fake sample data (test source set only). Keystore-backed encryption is not available
 * on the JVM, so these cover plain (unencrypted) backups; encrypted flows are tested in core:data.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class Stage5ScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var h: ScreenshotHarness
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val crypto by lazy { CryptoProvider(context) }
    private val pins by lazy { PinStore(context, crypto, h.clock) }
    private val attachments by lazy { AttachmentStore(context, crypto, h.db, h.ids, h.clock) }
    private val backups by lazy { BackupManager(context, h.db, attachments, h.settings, crypto, h.clock, h.ids) }
    private val lock by lazy { AppLockManager(pins, h.settings, h.clock) }

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        h = ScreenshotHarness()
        runBlocking {
            h.settings.update { it.copy(onboardingDone = true) }
            h.accounts.create(Account("bank", "بانک ملت", AccountType.BANK, "IRR", startDate = LocalDate.of(2026, 1, 1), colorKey = "c0"), 500_000_000)
            h.accounts.create(Account("cash", "کیف پول", AccountType.CASH, "IRR", startDate = LocalDate.of(2026, 1, 1), colorKey = "c2"), 10_000_000)
            repeat(12) { i ->
                h.record(IncomeExpenseDraft("g$i", EntryKind.EXPENSE, "bank", 2_000_000L + i * 350_000, LocalDate.of(2026, 10, 1).plusDays(i.toLong()), SeedData.categoryId("expense.food"), payee = "سوپرمارکت ${i + 1}"))
            }
            h.record(IncomeExpenseDraft("sal", EntryKind.INCOME, "bank", 450_000_000, LocalDate.of(2026, 10, 1), SeedData.categoryId("income.salary"), payee = "شرکت نمونه"))
        }
    }

    @After
    fun tearDown() {
        h.close()
        Dispatchers.resetMain()
        File(context.filesDir, "backups").deleteRecursively()
        File(context.cacheDir, "backup").deleteRecursively()
    }

    private fun settle() = repeat(3) { Thread.sleep(400); compose.waitForIdle() }

    private fun dataVm() = DataViewModel(
        context, backups, DataExporter(h.db), TrashRepository(h.db, h.ledger, attachments),
        DataWiper(h.db, attachments, backups, pins, h.settings, h.clock), pins, lock, h.settings, h.currencies, h.accounts,
        ReportPdfWriter(context, h.ledger, h.queries, h.categories, h.currencies, h.rates, h.settings, AppCompatLocaleController(), h.clock), h.clock,
    )

    private fun shot(name: String, dark: Boolean = false, content: @androidx.compose.runtime.Composable () -> Unit) {
        val lang = if (name.endsWith("_fa") || name.contains("_fa_")) AppLanguage.FA else AppLanguage.EN
        compose.setContent { ScreenFrame(h.formats(lang), dark) { content() } }
        settle()
        compose.saveScreenshot(name)
    }

    @Test
    @Config(qualifiers = "fa-w411dp-h891dp-xxhdpi")
    fun s5_lock_fa() {
        val vm = LockViewModel(pins, lock, h.clock, h.settings)
        vm.digit('1'); vm.digit('3')
        shot("s5_lock_fa") { LockScreen(viewModel = vm) }
    }

    @Test
    fun s5_pin_setup_en_dark() {
        val vm = PinSetupViewModel(SavedStateHandle(mapOf("mode" to "SET")), pins, h.settings, lock)
        vm.digit('1'); vm.digit('2'); vm.digit('3'); vm.digit('4'); vm.submit() // too simple → hint
        shot("s5_pin_setup_en_dark", dark = true) { PinSetupScreen(onDone = {}, viewModel = vm) }
    }

    @Test
    @Config(qualifiers = "fa-w411dp-h2000dp-xxhdpi")
    fun s5_settings_security_fa() {
        val s = runBlocking { h.settings.settings.first() }.copy(biometricEnabled = false, autoLockMinutes = 5)
        shot("s5_settings_security_fa") { SettingsContent(SettingsUiState(s, AppLanguage.FA, pinSet = true, biometricAvailable = false), {}, {}, {}) }
    }

    @Test
    @Config(qualifiers = "fa-w411dp-h1400dp-xxhdpi")
    fun s5_backup_fa() {
        runBlocking { backups.export(ByteArrayOutputStream(), null) }
        val vm = dataVm()
        shot("s5_backup_fa") { BackupScreen(onBack = {}, viewModel = vm) }
    }

    @Test
    @Config(qualifiers = "fa-w411dp-h1400dp-xxhdpi")
    fun s5_restore_confirm_fa() {
        val file = File(context.cacheDir, "test.mmbak")
        file.outputStream().use { runBlocking { backups.export(it, null) } }
        // Some extra data on "this phone" so the summary shows a difference.
        runBlocking { h.record(IncomeExpenseDraft("extra", EntryKind.EXPENSE, "cash", 120_000, LocalDate.of(2026, 10, 13), SeedData.categoryId("expense.transport"))) }
        val vm = dataVm()
        vm.pickedRestoreFile(Uri.fromFile(file))
        shot("s5_restore_confirm_fa") { BackupScreen(onBack = {}, viewModel = vm) }
    }

    @Test
    @Config(qualifiers = "fa-w411dp-h1600dp-xxhdpi")
    fun s5_import_fa() {
        val csv = File(context.cacheDir, "bank-mellat.csv")
        csv.writeText(
            "تاریخ,شرح,برداشت,واریز\n" +
                "۱۴۰۵/۰۷/۰۹,سوپرمارکت 1,2000000,\n" + // same as the existing expense of 2026-10-01: flagged duplicate
                "۱۴۰۵/۰۷/۲۰,کافه,850000,\n" +
                "۱۴۰۵/۰۷/۲۱,واریز حقوق,,300000000\n" +
                "۱۴۰۵/۱۳/۰۱,خطا,100,\n",
        )
        val vm = ImportViewModel(
            context, ImportCsvUseCase(h.record, h.ledger, ImportBatchRepositoryImpl(h.db), h.tx, h.ids, h.clock), h.accounts, h.categories, h.currencies,
            ImportBatchRepositoryImpl(h.db),
        )
        compose.setContent { ScreenFrame(h.formats(AppLanguage.FA)) { ImportScreen(onBack = {}, viewModel = vm) } }
        settle()
        vm.pick(Uri.fromFile(csv), csv.name)
        settle()
        compose.saveScreenshot("s5_import_mapping_fa")
        vm.toPreview()
        settle()
        compose.saveScreenshot("s5_import_preview_fa")
    }

    @Test
    @Config(qualifiers = "fa-w411dp-h891dp-xxhdpi")
    fun s5_trash_fa() {
        runBlocking { h.ledger.softDeleteGroup("g3"); h.ledger.softDeleteGroup("g7") }
        val vm = dataVm()
        shot("s5_trash_fa") { TrashScreen(onBack = {}, viewModel = vm) }
    }

    @Test
    @Config(qualifiers = "fa-w411dp-h1200dp-xxhdpi")
    fun s5_export_fa() {
        val vm = dataVm()
        shot("s5_export_fa") { ExportScreen(onBack = {}, viewModel = vm) }
    }

    @Test
    fun s5_delete_all_en() {
        val vm = dataVm()
        shot("s5_delete_all_en") { DeleteAllScreen(onBack = {}, onBackup = {}, viewModel = vm) }
    }

    @Test
    @Config(qualifiers = "fa")
    fun pdf_statement_is_written() {
        // Robolectric has no native PdfDocument ("document is closed"); the PDF is verified on a device (Stage 6).
        val probe = runCatching { android.graphics.pdf.PdfDocument().apply { startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(10, 10, 1).create()) } }
        org.junit.Assume.assumeTrue("PdfDocument is not available in Robolectric", probe.isSuccess)
        val out = ByteArrayOutputStream()
        val n = runBlocking {
            ReportPdfWriter(context, h.ledger, h.queries, h.categories, h.currencies, h.rates, h.settings, AppCompatLocaleController(), h.clock)
                .write(out, ExportFilter(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)))
        }
        val bytes = out.toByteArray()
        assertTrue(n >= 13)
        assertTrue(String(bytes.copyOf(5), Charsets.US_ASCII) == "%PDF-", "not a PDF")
        File("build/screenshots").mkdirs()
        File("build/screenshots/s5_statement_fa.pdf").writeBytes(bytes)
        // Settings are untouched by an export.
        assertTrue(runBlocking { h.settings.settings.first() } != AppSettings())
    }
}
