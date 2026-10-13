package com.mymoney.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mymoney.core.data.db.MyMoneyDatabase
import com.mymoney.core.data.db.SeedData
import com.mymoney.core.domain.calc.AssetCalculator
import com.mymoney.core.domain.calc.BudgetCalculator
import com.mymoney.core.domain.calc.IncomeStatementCalculator
import com.mymoney.core.domain.calc.NetWorthCalculator
import com.mymoney.core.domain.calc.RateBook
import com.mymoney.core.domain.common.DomainError
import com.mymoney.core.domain.common.IdGenerator
import com.mymoney.core.domain.common.Outcome
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.model.Account
import com.mymoney.core.domain.model.AccountType
import com.mymoney.core.domain.model.Asset
import com.mymoney.core.domain.model.AssetType
import com.mymoney.core.domain.model.Budget
import com.mymoney.core.domain.model.BudgetPeriodType
import com.mymoney.core.domain.model.BudgetScope
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.model.Frequency
import com.mymoney.core.domain.model.GoalMode
import com.mymoney.core.domain.model.InterestMethod
import com.mymoney.core.domain.model.Obligation
import com.mymoney.core.domain.model.ObligationDirection
import com.mymoney.core.domain.model.ObligationKind
import com.mymoney.core.domain.model.ObligationStatus
import com.mymoney.core.domain.model.OccurrenceStatus
import com.mymoney.core.domain.model.RecurringRule
import com.mymoney.core.domain.model.RuleKind
import com.mymoney.core.domain.model.SavingsGoal
import com.mymoney.core.domain.settings.CalendarType
import com.mymoney.core.domain.settings.WeekStart
import com.mymoney.core.domain.usecase.AssetOperationsUseCase
import com.mymoney.core.domain.usecase.CreateObligationUseCase
import com.mymoney.core.domain.usecase.GoalAllocationUseCase
import com.mymoney.core.domain.usecase.IncomeExpenseDraft
import com.mymoney.core.domain.usecase.ObligationOrigin
import com.mymoney.core.domain.usecase.PayOccurrenceUseCase
import com.mymoney.core.domain.usecase.RecordOperationUseCase
import com.mymoney.core.domain.usecase.TransferDraft
import com.mymoney.core.domain.usecase.WriteOffObligationUseCase
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlanningUseCasesTest {
    private lateinit var db: MyMoneyDatabase
    private val clock = object : TimeProvider {
        var t = 1_760_000_000_000
        override fun nowMillis() = t++
        override fun today() = LocalDate.of(2026, 10, 13)
    }
    private val ids = object : IdGenerator {
        var n = 0
        override fun newId() = "id-${n++}"
    }
    private lateinit var accounts: AccountRepositoryImpl
    private lateinit var ledger: LedgerRepositoryImpl
    private lateinit var categories: CategoryRepositoryImpl
    private lateinit var currencies: CurrencyRepositoryImpl
    private lateinit var record: RecordOperationUseCase
    private lateinit var tx: RoomTransactionRunner
    private val day = LocalDate.of(2026, 10, 1)

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MyMoneyDatabase::class.java)
            .addCallback(MyMoneyDatabase.SeedCallback).allowMainThreadQueries().build()
        val log = ChangeLogger(db.changeLogDao(), ids, clock)
        accounts = AccountRepositoryImpl(db, log, ids, clock)
        ledger = LedgerRepositoryImpl(db, log, clock)
        categories = CategoryRepositoryImpl(db, log, ids, clock)
        currencies = CurrencyRepositoryImpl(db)
        tx = RoomTransactionRunner(db)
        record = RecordOperationUseCase(accounts, categories, currencies, ledger, ExchangeRateRepositoryImpl(db), ids, clock)
        accounts.create(Account("bank", "Bank", AccountType.BANK, "IRR", startDate = LocalDate.of(2026, 1, 1)), 100_000_000)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `paying a bill records the real expense and marks the due date (FBR-12, FBR-27)`() = runTest {
        val recurring = RecurringRepositoryImpl(db, ids, clock)
        val rule = RecurringRule(
            "rent", RuleKind.BILL, "Rent", "bank", categoryId = SeedData.categoryId("expense.housing"), amountMinor = 10_000_000,
            currency = "IRR", frequency = Frequency.MONTHLY, calendar = CalendarType.JALALI, startDate = day,
        )
        recurring.save(rule)
        assertEquals(100_000_000, accounts.balance("bank")) // defining a rule moves no money
        val pay = PayOccurrenceUseCase(recurring, record, tx, clock)
        assertIs<Outcome.Success<Unit>>(pay(rule, day, "p1", 4_000_000, day))
        assertEquals(OccurrenceStatus.PARTIAL, recurring.states("rent").single().status)
        assertIs<Outcome.Success<Unit>>(pay(rule, day, "p2", 6_000_000, day.plusDays(2)))
        assertEquals(OccurrenceStatus.PAID, recurring.states("rent").single().status)
        assertEquals(10_000_000, recurring.paidAmounts()["rent" to day])
        assertEquals(90_000_000, accounts.balance("bank"))
        // Paying the same groupId again is ignored (idempotent).
        pay(rule, day, "p2", 6_000_000, day.plusDays(2))
        assertEquals(90_000_000, accounts.balance("bank"))
    }

    @Test
    fun `loan - borrow, pay installment split principal-interest, write-off (FBR-04, FBR-32)`() = runTest {
        val obligations = ObligationRepositoryImpl(db)
        val create = CreateObligationUseCase(accounts, obligations, record, tx, ids, clock)
        val loanId = (create(
            Obligation("", ObligationDirection.PAYABLE, "Bank Melli", ObligationKind.BANK_LOAN, 50_000_000, day,
                annualRatePercent = BigDecimal("18"), interestMethod = InterestMethod.ANNUITY, termMonths = 12),
            "Car loan", "IRR", ObligationOrigin.CASH, "bank", null,
        ) as Outcome.Success).value
        assertEquals(150_000_000, accounts.balance("bank"))
        assertEquals(-50_000_000, accounts.balance(loanId))
        // Borrowing is not income
        assertEquals(0, IncomeStatementCalculator.compute(ledger.categoryAmounts(day, day), RateBook(emptyList(), currencies.getAll().associateBy { it.code }, "IRR"), "IRR").incomeMinor)

        val recurring = RecurringRepositoryImpl(db, ids, clock)
        val rule = RecurringRule("inst", RuleKind.LOAN_INSTALLMENT, "Installment", "bank", toAccountId = loanId, amountMinor = 4_583_000,
            currency = "IRR", frequency = Frequency.MONTHLY, calendar = CalendarType.GREGORIAN, startDate = day.plusMonths(1))
        recurring.save(rule)
        PayOccurrenceUseCase(recurring, record, tx, clock)(rule, rule.startDate, "i1", 4_583_000, rule.startDate, interestMinor = 750_000)
        assertEquals(-50_000_000 + 3_833_000, accounts.balance(loanId))
        val nov = day.plusMonths(1)
        val pnl = IncomeStatementCalculator.compute(ledger.categoryAmounts(nov, nov), RateBook(emptyList(), currencies.getAll().associateBy { it.code }, "IRR"), "IRR")
        assertEquals(750_000, pnl.expenseMinor) // only interest is an expense
        assertEquals(OccurrenceStatus.PAID, recurring.states("inst").single().status)

        val writeOff = WriteOffObligationUseCase(accounts, obligations, ledger, tx, ids, clock)
        assertIs<Outcome.Success<Unit>>(writeOff(loanId, nov, "forgiven"))
        assertEquals(0, accounts.balance(loanId))
        assertEquals(ObligationStatus.WRITTEN_OFF, obligations.get(loanId)!!.status)
    }

    @Test
    fun `lending and collecting is not income (FBR-05) and failed creation rolls back`() = runTest {
        val obligations = ObligationRepositoryImpl(db)
        val create = CreateObligationUseCase(accounts, obligations, record, tx, ids, clock)
        val lent = (create(Obligation("", ObligationDirection.RECEIVABLE, "Ali", ObligationKind.PERSONAL, 5_000_000, day), "Ali", "IRR", ObligationOrigin.CASH, "bank", null) as Outcome.Success).value
        assertEquals(95_000_000, accounts.balance("bank"))
        record(TransferDraft("collect", lent, "bank", 2_000_000, 2_000_000, day))
        assertEquals(3_000_000, accounts.balance(lent))
        assertEquals(0, IncomeStatementCalculator.compute(ledger.categoryAmounts(day, day), RateBook(emptyList(), currencies.getAll().associateBy { it.code }, "IRR"), "IRR").incomeMinor)

        val before = accounts.getAll().size
        val failed = create(Obligation("", ObligationDirection.PAYABLE, "Shop", ObligationKind.INSTALLMENT_PURCHASE, 1_000, day), "Phone", "IRR", ObligationOrigin.PURCHASE, null, purchaseCategoryId = "missing")
        assertIs<Outcome.Failure>(failed)
        assertEquals(before, accounts.getAll().size) // no orphan account
    }

    @Test
    fun `asset buy and sell keep net worth honest (FBR-06, FBR-07, FBR-10)`() = runTest {
        val assets = AssetRepositoryImpl(db, clock)
        val ops = AssetOperationsUseCase(assets, accounts, currencies, ledger, tx, ids, clock)
        val goldId = (ops.create(Asset("", "Gold", AssetType.GOLD, "gram", "IRR"), null, null, null, null, day) as Outcome.Success).value
        assertIs<Outcome.Success<Unit>>(ops.buy(goldId, "bank", BigDecimal("10"), 40_000_000, 500_000, 40_500_000, day, "buy1"))
        assertEquals(59_500_000, accounts.balance("bank"))
        val rates = RateBook(emptyList(), currencies.getAll().associateBy { it.code }, "IRR")
        // Buying is not an expense
        assertEquals(0, IncomeStatementCalculator.compute(ledger.categoryAmounts(day, day), rates, "IRR").expenseMinor)
        val holding = AssetCalculator.holding(assets.get(goldId)!!, assets.trades(goldId), assets.valuations(goldId), 0)
        assertEquals(40_000_000, holding.valueMinor) // trade price valuation
        val nw = NetWorthCalculator.compute(accounts.observeWithBalances().first(), rates, "IRR", day, listOf(holding.valueMinor!!))
        assertEquals(100_000_000 - 500_000, nw.netWorthMinor) // only the fee reduced net worth

        assertIs<Outcome.Failure>(ops.sell(goldId, "bank", BigDecimal("11"), 1, 0, 1, day.plusDays(1), "bad"))
        assertIs<Outcome.Success<Unit>>(ops.sell(goldId, "bank", BigDecimal("4"), 20_000_000, 100_000, 19_900_000, day.plusDays(1), "sell1"))
        val after = AssetCalculator.holding(assets.get(goldId)!!, assets.trades(goldId), assets.valuations(goldId), 0)
        assertEquals(0, BigDecimal("6").compareTo(after.quantity))
        assertEquals(19_900_000 - 16_200_000, after.realizedTotalMinor) // cost of 4g = 40.5M × 4/10
        // Deleting the trade removes its cash movement too
        assets.deleteTrade(assets.trades(goldId).last().id)
        assertEquals(59_500_000, accounts.balance("bank"))
    }

    @Test
    fun `goal allocations are virtual (FBR-23)`() = runTest {
        val goals = GoalRepositoryImpl(db, ids, clock)
        goals.save(SavingsGoal("g", "Trip", 30_000_000, "IRR", mode = GoalMode.ALLOCATION))
        val allocate = GoalAllocationUseCase(goals, ids, clock)
        allocate("g", "bank", 10_000_000, day, null)
        assertEquals(100_000_000, accounts.balance("bank"))
        assertEquals(10_000_000, goals.allocatedFrom("bank"))
        assertEquals(DomainError.RuleViolation("FBR-23", "release exceeds allocation"), (allocate("g", "bank", -10_000_001, day, null) as Outcome.Failure).error)
    }

    @Test
    fun `budget round trip and evaluation on real rows`() = runTest {
        val budgets = BudgetRepositoryImpl(db, ids, clock)
        val food = SeedData.categoryId("expense.food")
        budgets.save(Budget("", "Food", BudgetPeriodType.MONTHLY, CalendarType.GREGORIAN, LocalDate.of(2026, 1, 1), amountMinor = 1_000_000,
            currency = "IRR", scope = BudgetScope.CATEGORIES, categoryIds = setOf(food), alertThresholds = listOf(80, 50, 100)))
        val b = budgets.observeAll().first().single()
        assertEquals(listOf(50, 80, 100), b.alertThresholds)
        record(IncomeExpenseDraft("x", EntryKind.EXPENSE, "bank", 850_000, day, food))
        record(TransferDraft("t", "bank", "bank".let { accounts.create(Account("cash", "Cash", AccountType.CASH, "IRR", startDate = day), 0); "cash" }, 50_000, 50_000, day))
        val calc = BudgetCalculator(categories.getAll(), RateBook(emptyList(), currencies.getAll().associateBy { it.code }, "IRR"), WeekStart.SATURDAY)
        val status = calc.status(b, day, LedgerAnalyticsImpl(db).budgetRows(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)))
        assertEquals(850_000, status.spentMinor)
        assertEquals(80, status.thresholdReached)
        assertTrue(status.remainingMinor == 150_000L)
    }
}
