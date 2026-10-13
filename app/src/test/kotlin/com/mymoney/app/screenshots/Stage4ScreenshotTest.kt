package com.mymoney.app.screenshots

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mymoney.app.feature.dashboard.DashboardScreen
import com.mymoney.app.feature.dashboard.DashboardViewModel
import com.mymoney.app.feature.datahealth.DataHealthScreen
import com.mymoney.app.feature.datahealth.DataHealthViewModel
import com.mymoney.app.feature.reports.InsightsScreen
import com.mymoney.app.feature.reports.InsightsViewModel
import com.mymoney.app.feature.reports.ReportDetailScreen
import com.mymoney.app.feature.reports.ReportDetailViewModel
import com.mymoney.app.feature.reports.ReportType
import com.mymoney.app.feature.reports.ReportsHomeScreen
import com.mymoney.app.feature.search.SearchScreen
import com.mymoney.app.feature.search.SearchViewModel
import com.mymoney.core.data.db.SeedData
import com.mymoney.core.domain.model.Account
import com.mymoney.core.domain.model.AccountType
import com.mymoney.core.domain.model.Budget
import com.mymoney.core.domain.model.BudgetPeriodType
import com.mymoney.core.domain.model.CostType
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.model.ExchangeRate
import com.mymoney.core.domain.model.Frequency
import com.mymoney.core.domain.model.GoalMode
import com.mymoney.core.domain.model.RateSource
import com.mymoney.core.domain.model.RecurringRule
import com.mymoney.core.domain.model.RuleKind
import com.mymoney.core.domain.model.SavingsGoal
import com.mymoney.core.domain.settings.AppLanguage
import com.mymoney.core.domain.settings.CalendarType
import com.mymoney.core.domain.usecase.IncomeExpenseDraft
import com.mymoney.core.domain.usecase.TransferDraft
import java.math.BigDecimal
import java.time.LocalDate
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
 * Stage-4 screens (dashboard cards, reports, insights, search, data health) rendered with clearly fake
 * sample data that lives only in the test source set. Tall viewports show whole scrolling screens.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class Stage4ScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var h: ScreenshotHarness

    @Before
    fun setUp() {
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
        h.settings.update { it.copy(onboardingDone = true, calendar = CalendarType.JALALI) }
        val start = LocalDate.of(2026, 1, 1)
        h.accounts.create(Account("bank", "بانک ملت", AccountType.BANK, "IRR", startDate = start, colorKey = "c0"), 900_000_000)
        h.accounts.create(Account("cash", "کیف پول", AccountType.CASH, "IRR", startDate = start, colorKey = "c2"), 15_000_000)
        h.accounts.create(Account("usd", "حساب دلاری", AccountType.BANK, "USD", startDate = start, colorKey = "c1"), 50_000)
        h.rates.save(ExchangeRate("r1", "USD", "IRR", BigDecimal("900000"), LocalDate.of(2026, 4, 1), RateSource.MANUAL))
        h.rates.save(ExchangeRate("r2", "USD", "IRR", BigDecimal("1050000"), LocalDate.of(2026, 10, 1), RateSource.MANUAL))
        var n = 0
        val food = SeedData.categoryId("expense.food")
        // Seven months of history so trends, insights and the forecast have data.
        for (m in 0..6) {
            val d = LocalDate.of(2026, 4, 1).plusMonths(m.toLong())
            h.record(IncomeExpenseDraft("g${n++}", EntryKind.INCOME, "bank", 450_000_000, d, SeedData.categoryId("income.salary"), payee = "شرکت نمونه"))
            h.record(IncomeExpenseDraft("g${n++}", EntryKind.EXPENSE, "bank", 150_000_000, d.plusDays(1), SeedData.categoryId("expense.housing"), payee = "اجاره", costType = CostType.FIXED, essential = true))
            for (k in 0 until 10) {
                val day = d.plusDays(2L + k * 2)
                if (day.isAfter(LocalDate.of(2026, 10, 12))) break
                val amount = if (m == 5) 9_000_000L else 4_000_000L + k * 150_000
                h.record(IncomeExpenseDraft("g${n++}", EntryKind.EXPENSE, "cash", amount, day, food, payee = "سوپرمارکت", costType = CostType.VARIABLE, essential = true))
            }
            h.record(IncomeExpenseDraft("g${n++}", EntryKind.EXPENSE, "bank", 12_000_000, d.plusDays(10), SeedData.categoryId("expense.transport"), costType = CostType.VARIABLE, essential = false))
            h.record(TransferDraft("g${n++}", "bank", "cash", 60_000_000, 60_000_000, d.plusDays(3)))
        }
        h.record(IncomeExpenseDraft("g${n++}", EntryKind.INCOME, "bank", 80_000_000, LocalDate.of(2026, 8, 20), SeedData.categoryId("income.other"), payee = "پروژه آزاد"))
        h.recurring.save(
            RecurringRule(
                "netflix", RuleKind.SUBSCRIPTION, "اشتراک فیلم", "bank", categoryId = SeedData.categoryId("expense.subscriptions"), amountMinor = 2_500_000,
                currency = "IRR", frequency = Frequency.MONTHLY, calendar = CalendarType.JALALI, startDate = LocalDate.of(2026, 9, 25),
            ),
        )
        h.recurring.save(
            RecurringRule(
                "rent", RuleKind.BILL, "اجاره خانه", "bank", categoryId = SeedData.categoryId("expense.housing"), amountMinor = 150_000_000,
                currency = "IRR", frequency = Frequency.MONTHLY, calendar = CalendarType.JALALI, startDate = LocalDate.of(2026, 10, 22),
            ),
        )
        h.budgets.save(Budget("b-food", "خوراک", BudgetPeriodType.MONTHLY, CalendarType.JALALI, start, amountMinor = 40_000_000, currency = "IRR",
            scope = com.mymoney.core.domain.model.BudgetScope.CATEGORIES, categoryIds = setOf(food)))
        h.goals.save(SavingsGoal("trip", "سفر نوروز", 300_000_000, "IRR", targetDate = LocalDate.of(2027, 3, 15), mode = GoalMode.LINKED_ACCOUNTS, accountIds = setOf("usd")))
    }

    private fun shot(name: String, dark: Boolean = false, content: @androidx.compose.runtime.Composable () -> Unit) {
        val lang = if (name.contains("_fa")) AppLanguage.FA else AppLanguage.EN
        val formats = h.formats(lang, runBlocking { h.settings.settings.first() }.let {
            if (lang == AppLanguage.EN) it.copy(calendar = CalendarType.GREGORIAN, digits = com.mymoney.core.domain.settings.DigitStyle.LATIN) else it
        })
        compose.setContent { ScreenFrame(formats, dark) { content() } }
        // ViewModels compute on Dispatchers.Default; give them time before capturing.
        repeat(3) { Thread.sleep(500); compose.waitForIdle() }
        compose.saveScreenshot(name)
    }

    private fun dashboardVm() = DashboardViewModel(h.provider, h.settings, h.ledger, h.analytics, h.queries)

    @Test
    @Config(qualifiers = "fa-w411dp-h2400dp-xxhdpi")
    fun s4_dashboard_fa() {
        val vm = dashboardVm()
        shot("s4_dashboard_fa") { DashboardScreen(PaddingValues(), {}, {}, {}, {}, {}, {}, {}, {}, viewModel = vm) }
    }

    @Test
    @Config(qualifiers = "w411dp-h2400dp-xxhdpi")
    fun s4_dashboard_en_dark() {
        val vm = dashboardVm()
        shot("s4_dashboard_en_dark", dark = true) { DashboardScreen(PaddingValues(), {}, {}, {}, {}, {}, {}, {}, {}, viewModel = vm) }
    }

    @Test
    @Config(qualifiers = "fa-w411dp-h1600dp-xxhdpi")
    fun s4_reports_home_fa() {
        val vm = InsightsViewModel(h.provider, h.ledger, h.analytics)
        shot("s4_reports_home_fa") { ReportsHomeScreen(PaddingValues(), {}, {}, viewModel = vm) }
    }

    private fun report(type: ReportType) = ReportDetailViewModel(SavedStateHandle(mapOf("type" to type.name)), h.provider, h.ledger, h.analytics)

    @Test
    @Config(qualifiers = "fa-w411dp-h1600dp-xxhdpi")
    fun s4_report_income_expense_fa() {
        val vm = report(ReportType.INCOME_EXPENSE)
        shot("s4_report_income_expense_fa") { ReportDetailScreen({}, {}, viewModel = vm) }
    }

    @Test
    @Config(qualifiers = "w411dp-h1200dp-xxhdpi")
    fun s4_report_net_worth_en() {
        val vm = report(ReportType.NET_WORTH)
        shot("s4_report_net_worth_en") { ReportDetailScreen({}, {}, viewModel = vm) }
    }

    @Test
    @Config(qualifiers = "fa-w411dp-h1200dp-xxhdpi")
    fun s4_report_cash_flow_fa() {
        val vm = report(ReportType.CASH_FLOW)
        shot("s4_report_cash_flow_fa") { ReportDetailScreen({}, {}, viewModel = vm) }
    }

    @Test
    @Config(qualifiers = "fa-w411dp-h1400dp-xxhdpi")
    fun s4_report_fixed_variable_fa() {
        val vm = report(ReportType.FIXED_VARIABLE)
        shot("s4_report_fixed_variable_fa") { ReportDetailScreen({}, {}, viewModel = vm) }
    }

    @Test
    @Config(qualifiers = "w411dp-h1200dp-xxhdpi")
    fun s4_report_comparison_en() {
        val vm = report(ReportType.COMPARISON)
        shot("s4_report_comparison_en") { ReportDetailScreen({}, {}, viewModel = vm) }
    }

    @Test
    @Config(qualifiers = "fa-w411dp-h1200dp-xxhdpi")
    fun s4_report_recurring_fa() {
        val vm = report(ReportType.RECURRING)
        shot("s4_report_recurring_fa") { ReportDetailScreen({}, {}, viewModel = vm) }
    }

    @Test
    @Config(qualifiers = "fa-w411dp-h1200dp-xxhdpi")
    fun s4_insights_fa() {
        val vm = InsightsViewModel(h.provider, h.ledger, h.analytics)
        shot("s4_insights_fa") { InsightsScreen({}, {}, viewModel = vm) }
    }

    @Test
    @Config(qualifiers = "fa-w411dp-h1400dp-xxhdpi")
    fun s4_data_health_fa() {
        val vm = DataHealthViewModel(h.provider, h.analytics)
        shot("s4_data_health_fa") { DataHealthScreen({}, { _, _ -> }, viewModel = vm) }
    }

    @Test
    @Config(qualifiers = "fa-w411dp-h891dp-xxhdpi")
    fun s4_search_fa() {
        val vm = SearchViewModel(h.provider, h.queries)
        vm.setQuery("اجاره")
        shot("s4_search_fa") { SearchScreen({}, {}, {}, viewModel = vm) }
    }
}
