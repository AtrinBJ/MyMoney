package com.mymoney.app.screenshots

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mymoney.app.feature.accounts.AccountsScreen
import com.mymoney.app.feature.accounts.AccountsViewModel
import com.mymoney.app.feature.dashboard.DashboardScreen
import com.mymoney.app.feature.dashboard.DashboardViewModel
import com.mymoney.app.feature.reports.MonthlyReportScreen
import com.mymoney.app.feature.reports.MonthlyReportViewModel
import com.mymoney.app.feature.transactions.EntryEditorScreen
import com.mymoney.app.feature.transactions.EntryEditorViewModel
import com.mymoney.app.feature.transactions.TransactionsScreen
import com.mymoney.app.feature.transactions.TransactionsViewModel
import com.mymoney.core.data.db.SeedData
import com.mymoney.core.domain.model.Account
import com.mymoney.core.domain.model.AccountType
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.model.ExchangeRate
import com.mymoney.core.domain.model.RateSource
import com.mymoney.core.domain.settings.AppLanguage
import com.mymoney.core.domain.usecase.FindPossibleDuplicatesUseCase
import com.mymoney.core.domain.usecase.IncomeExpenseDraft
import com.mymoney.core.domain.usecase.SplitDraft
import com.mymoney.core.domain.usecase.TransferDraft
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
 * Renders the stage-3 screens with clearly fake sample data (test source set only — never shipped)
 * so their layout can be reviewed as PNGs in build/screenshots.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class Stage3ScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var h: ScreenshotHarness

    @Before
    fun setUp() {
        // Real-time dispatcher: debounce/delay in ViewModels must actually elapse while rendering.
        Dispatchers.setMain(Dispatchers.Unconfined)
        h = ScreenshotHarness()
        runBlocking { seed() }
    }

    @After
    fun tearDown() {
        h.close()
        Dispatchers.resetMain()
    }

    private suspend fun seed() {
        val start = LocalDate.of(2026, 1, 1)
        h.accounts.create(Account("bank", "بانک ملت", AccountType.BANK, "IRR", startDate = start, colorKey = "c0"), 850_000_000)
        h.accounts.create(Account("cash", "کیف پول", AccountType.CASH, "IRR", startDate = start, colorKey = "c2"), 12_000_000)
        h.accounts.create(Account("card", "کارت اعتباری", AccountType.CREDIT_CARD, "IRR", startDate = start, colorKey = "c7", creditLimitMinor = 500_000_000), 0)
        h.accounts.create(Account("eur", "حساب یورو", AccountType.BANK, "EUR", startDate = start, colorKey = "c1"), 120_000)
        h.rates.save(ExchangeRate("r1", "EUR", "IRR", BigDecimal("1050000"), LocalDate.of(2026, 9, 1), RateSource.MANUAL))
        val d = LocalDate.of(2026, 10, 1)
        val food = SeedData.categoryId("expense.food")
        var n = 0
        suspend fun exp(acc: String, amount: Long, cat: String, day: Int, payee: String? = null) =
            h.record(IncomeExpenseDraft("g${n++}", EntryKind.EXPENSE, acc, amount, d.plusDays(day.toLong()), cat, payee = payee))
        h.record(IncomeExpenseDraft("g${n++}", EntryKind.INCOME, "bank", 450_000_000, d, SeedData.categoryId("income.salary"), payee = "شرکت نمونه"))
        exp("card", 18_500_000, food, 1, "سوپرمارکت")
        exp("bank", 120_000_000, SeedData.categoryId("expense.housing"), 2, "اجاره")
        exp("cash", 4_500_000, SeedData.categoryId("expense.transport"), 3)
        exp("card", 9_900_000, SeedData.categoryId("expense.subscriptions"), 5, "اشتراک فیلم")
        exp("bank", 7_300_000, SeedData.categoryId("expense.utilities"), 6, "قبض برق")
        h.record(
            IncomeExpenseDraft(
                "g${n++}", EntryKind.EXPENSE, "card", 30_000_000, d.plusDays(8), null,
                splits = listOf(SplitDraft(food, 20_000_000), SplitDraft(SeedData.categoryId("expense.health"), 10_000_000)),
                payee = "هایپرمارکت",
            ),
        )
        h.record(TransferDraft("g${n++}", "bank", "cash", 20_000_000, 20_000_000, d.plusDays(9), feeMinor = 50_000))
        // Previous month for comparison
        h.record(IncomeExpenseDraft("g${n++}", EntryKind.INCOME, "bank", 430_000_000, LocalDate.of(2026, 9, 25), SeedData.categoryId("income.salary")))
        exp("bank", 140_000_000, SeedData.categoryId("expense.housing"), -8)
    }

    private fun dashboardVm() = DashboardViewModel(h.accounts, h.rates, h.currencies, h.settings, h.ledger, h.queries, h.clock)

    @Test
    @Config(qualifiers = "fa-w411dp-h891dp-xxhdpi")
    fun dashboard_fa_light() {
        val formats = h.formats(AppLanguage.FA)
        val vm = dashboardVm()
        compose.setContent {
            ScreenFrame(formats) {
                DashboardScreen(PaddingValues(), {}, {}, {}, {}, {}, {}, viewModel = vm)
            }
        }
        compose.saveScreenshot("dashboard_fa_light")
    }

    @Test
    fun dashboard_en_dark() {
        val formats = h.formats(AppLanguage.EN)
        val vm = dashboardVm()
        compose.setContent {
            ScreenFrame(formats, dark = true) {
                DashboardScreen(PaddingValues(), {}, {}, {}, {}, {}, {}, viewModel = vm)
            }
        }
        compose.saveScreenshot("dashboard_en_dark")
    }

    @Test
    @Config(qualifiers = "fa-w411dp-h891dp-xxhdpi")
    fun accounts_fa() {
        val formats = h.formats(AppLanguage.FA)
        val vm = AccountsViewModel(h.accounts, h.rates, h.currencies, h.settings, h.clock)
        compose.setContent {
            ScreenFrame(formats) {
                AccountsScreen(PaddingValues(), {}, {}, {}, viewModel = vm)
            }
        }
        compose.saveScreenshot("accounts_fa")
    }

    @Test
    @Config(qualifiers = "fa-w411dp-h891dp-xxhdpi")
    fun transactions_fa() {
        val formats = h.formats(AppLanguage.FA)
        val vm = TransactionsViewModel(h.queries, h.ledger, h.accounts, h.categories, h.tags)
        compose.setContent {
            ScreenFrame(formats) {
                TransactionsScreen(
                    PaddingValues(), SnackbarHostState(), h.clock.today(), onOpen = {},
                    viewModel = vm,
                )
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("هایپرمارکت", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.saveScreenshot("transactions_fa")
    }

    @Test
    @Config(qualifiers = "fa-w411dp-h891dp-xxhdpi")
    fun report_fa() {
        val formats = h.formats(AppLanguage.FA)
        val vm = MonthlyReportViewModel(h.ledger, h.categories, h.rates, h.currencies, h.settings, h.clock)
        compose.setContent {
            ScreenFrame(formats) {
                MonthlyReportScreen(PaddingValues(), {}, viewModel = vm)
            }
        }
        compose.saveScreenshot("report_fa")
    }

    @Test
    @Config(qualifiers = "fa-w411dp-h891dp-xxhdpi")
    fun editor_transfer_fa() {
        val formats = h.formats(AppLanguage.FA)
        val vm = EntryEditorViewModel(
            SavedStateHandle(mapOf("mode" to "TRANSFER", "accountId" to "eur")),
            h.accounts, h.categories, h.tags, h.currencies, h.ledger, h.settings, h.record,
            FindPossibleDuplicatesUseCase(h.ledger), h.ids, h.clock,
        )
        compose.setContent { ScreenFrame(formats) { EntryEditorScreen(onDone = {}, viewModel = vm) } }
        compose.waitForIdle()
        vm.setToAccount("bank")
        vm.setAmount("100")
        vm.setAmountIn("10500000")
        vm.setFee("2")
        compose.saveScreenshot("editor_transfer_fa")
    }
}
