package com.mymoney.app.feature

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mymoney.app.feature.reports.PeriodKind
import com.mymoney.app.feature.reports.ReportBuilder
import com.mymoney.app.feature.reports.ReportData
import com.mymoney.app.feature.reports.ReportType
import com.mymoney.app.feature.reports.available
import com.mymoney.app.feature.search.searchKey
import com.mymoney.app.reminders.DailyMaintenance
import com.mymoney.app.reminders.Notifier
import com.mymoney.app.reminders.Reminder
import com.mymoney.app.screenshots.ScreenshotHarness
import com.mymoney.core.data.db.SeedData
import com.mymoney.core.domain.calc.IncomeStatementCalculator
import com.mymoney.core.domain.calendar.PeriodType
import com.mymoney.core.domain.insights.Insight
import com.mymoney.core.domain.model.Account
import com.mymoney.core.domain.model.AccountType
import com.mymoney.core.domain.model.Budget
import com.mymoney.core.domain.model.BudgetPeriodType
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.model.Frequency
import com.mymoney.core.domain.model.PostingPolicy
import com.mymoney.core.domain.model.RecurringRule
import com.mymoney.core.domain.model.RuleKind
import com.mymoney.core.domain.settings.CalendarType
import com.mymoney.core.domain.usecase.IncomeExpenseDraft
import com.mymoney.core.domain.usecase.TransferDraft
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Stage4LogicTest {
    private lateinit var h: ScreenshotHarness
    private val today = LocalDate.of(2026, 10, 13)

    private class FakeNotifier(var allowed: Boolean = true) : Notifier {
        val shown = mutableListOf<Reminder>()
        override fun canNotify() = allowed
        override fun show(reminders: List<Reminder>) {
            shown += reminders
        }
    }

    @Before
    fun setUp() = runBlocking {
        h = ScreenshotHarness()
        h.settings.update { it.copy(onboardingDone = true, calendar = CalendarType.GREGORIAN, remindersEnabled = true) }
        h.accounts.create(Account("bank", "Bank", AccountType.BANK, "IRR", startDate = LocalDate.of(2026, 1, 1)), 100_000_000)
        h.accounts.create(Account("cash", "Cash", AccountType.CASH, "IRR", startDate = LocalDate.of(2026, 1, 1)), 5_000_000)
        Unit
    }

    @After
    fun tearDown() = h.close()

    private suspend fun expense(id: String, amount: Long, date: LocalDate, category: String = "expense.food") =
        h.record(IncomeExpenseDraft(id, EntryKind.EXPENSE, "bank", amount, date, SeedData.categoryId(category)))

    @Test
    fun `daily maintenance posts AUTO_POST rules and notifies once (idempotent)`() = runBlocking {
        h.recurring.save(
            RecurringRule(
                "net", RuleKind.SUBSCRIPTION, "Internet", "bank", categoryId = SeedData.categoryId("expense.food"), amountMinor = 1_000_000,
                currency = "IRR", frequency = Frequency.MONTHLY, calendar = CalendarType.GREGORIAN, startDate = LocalDate.of(2026, 10, 5),
                postingPolicy = PostingPolicy.AUTO_POST,
            ),
        )
        h.recurring.save(
            RecurringRule(
                "rent", RuleKind.BILL, "Rent", "bank", categoryId = SeedData.categoryId("expense.housing"), amountMinor = 20_000_000,
                currency = "IRR", frequency = Frequency.MONTHLY, calendar = CalendarType.GREGORIAN, startDate = LocalDate.of(2026, 10, 15),
                reminderDaysBefore = 3,
            ),
        )
        h.budgets.save(Budget("food", "Food", BudgetPeriodType.MONTHLY, CalendarType.GREGORIAN, LocalDate.of(2026, 1, 1), amountMinor = 2_000_000, currency = "IRR"))
        expense("e1", 700_000, LocalDate.of(2026, 10, 2))

        val maintenance = DailyMaintenance(h.ledger, h.analytics, h.notificationLog, h.payOccurrence, h.clock) { 0 }
        val notifier = FakeNotifier()
        val first = maintenance.run(h.provider.snapshot.first(), notifier)
        assertEquals(1, first.autoPosted)
        assertEquals(100_000_000 - 700_000 - 1_000_000, h.accounts.balance("bank"))
        assertTrue(notifier.shown.any { it is Reminder.Due && it.ruleName == "Rent" })
        assertTrue(notifier.shown.any { it is Reminder.AutoPosted })
        // Food: 0.7M + 1M auto-posted = 85% of 2M → the 80% threshold.
        assertEquals(80, notifier.shown.filterIsInstance<Reminder.BudgetThreshold>().single().percent)

        val second = maintenance.run(h.provider.snapshot.first(), notifier)
        assertEquals(0, second.autoPosted)
        assertEquals(0, second.notified)
        assertEquals(100_000_000 - 700_000 - 1_000_000, h.accounts.balance("bank"))
    }

    @Test
    fun `reminders off or notifications blocked - nothing is shown, auto-post still runs`() = runBlocking {
        h.settings.update { it.copy(remindersEnabled = false) }
        h.recurring.save(
            RecurringRule(
                "rent", RuleKind.BILL, "Rent", "bank", categoryId = SeedData.categoryId("expense.housing"), amountMinor = 20_000_000,
                currency = "IRR", frequency = Frequency.MONTHLY, calendar = CalendarType.GREGORIAN, startDate = LocalDate.of(2026, 10, 14),
                reminderDaysBefore = 3,
            ),
        )
        val notifier = FakeNotifier()
        DailyMaintenance(h.ledger, h.analytics, h.notificationLog, h.payOccurrence, h.clock) { 0 }.run(h.provider.snapshot.first(), notifier)
        assertTrue(notifier.shown.isEmpty())

        h.settings.update { it.copy(remindersEnabled = true) }
        val blocked = FakeNotifier(allowed = false)
        val r = DailyMaintenance(h.ledger, h.analytics, h.notificationLog, h.payOccurrence, h.clock) { 0 }.run(h.provider.snapshot.first(), blocked)
        assertEquals(0, r.notified)
        // Not marked as sent, so it is shown once notifications are allowed again.
        val later = FakeNotifier()
        DailyMaintenance(h.ledger, h.analytics, h.notificationLog, h.payOccurrence, h.clock) { 0 }.run(h.provider.snapshot.first(), later)
        assertTrue(later.shown.any { it is Reminder.Due })
    }

    @Test
    fun `report totals equal dashboard totals and transfers are not income or expense (FBR-02, FBR-39)`() = runBlocking {
        h.record(IncomeExpenseDraft("i1", EntryKind.INCOME, "bank", 30_000_000, LocalDate.of(2026, 10, 1), SeedData.categoryId("income.salary")))
        expense("e1", 4_000_000, LocalDate.of(2026, 10, 3))
        expense("e2", 1_500_000, LocalDate.of(2026, 9, 20))
        h.record(TransferDraft("t1", "bank", "cash", 2_000_000, 2_000_000, LocalDate.of(2026, 10, 4)))

        val s = h.provider.snapshot.first()
        val builder = ReportBuilder(h.ledger, h.analytics)
        val series = assertIs<ReportData.Series>(builder.build(s, ReportType.INCOME_EXPENSE, PeriodKind.MONTH, 0))
        val month = builder.periods(s).rangeOf(PeriodType.MONTH, today)
        val dashboard = IncomeStatementCalculator.compute(h.ledger.categoryAmounts(month.start, month.endInclusive), s.rateBook, s.base)
        assertEquals(dashboard, series.statements.last())
        assertEquals(30_000_000, series.statements.last().incomeMinor)
        assertEquals(4_000_000, series.statements.last().expenseMinor)
        assertEquals(1_500_000, series.statements[series.statements.size - 2].expenseMinor)

        val cf = assertIs<ReportData.CashFlow>(builder.build(s, ReportType.CASH_FLOW, PeriodKind.MONTH, 0))
        assertEquals(30_000_000 - 4_000_000, cf.statement.operatingNet)
        assertEquals(0, cf.statement.transfersOther) // bank → cash cancels out inside the selected accounts

        val nw = assertIs<ReportData.NetWorth>(builder.build(s, ReportType.NET_WORTH, PeriodKind.MONTH, 0))
        assertEquals(s.netWorth.netWorthMinor, nw.values.last())
        assertEquals(0, nw.fxEffectBase)

        val cmp = assertIs<ReportData.Comparison>(builder.build(s, ReportType.COMPARISON, PeriodKind.MONTH, 0))
        val food = cmp.rows.single { it.first == SeedData.categoryId("expense.food") }
        assertEquals(4_000_000L to 1_500_000L, food.second to food.third)
    }

    @Test
    fun `backup insight is shown now that backup exists`() = runBlocking {
        expense("e1", 4_000_000, LocalDate.of(2026, 10, 3))
        val all = ReportBuilder(h.ledger, h.analytics).insights(h.provider.snapshot.first())
        assertTrue(all.available().any { it is Insight.BackupOverdue })
    }

    @Test
    fun `search matching ignores Arabic letter variants, digit scripts and case`() {
        assertTrue(searchKey("علي كريمي").contains(searchKey("علی کریمی")))
        assertEquals(searchKey("Rent 1405"), searchKey("rent ۱۴۰۵"))
        assertEquals(searchKey("می‌خواهم"), searchKey("میخواهم"))
    }
}
