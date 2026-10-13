package com.mymoney.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mymoney.core.data.db.MyMoneyDatabase
import com.mymoney.core.data.db.SeedData
import com.mymoney.core.domain.calc.IncomeStatementCalculator
import com.mymoney.core.domain.calc.RateBook
import com.mymoney.core.domain.common.DomainError
import com.mymoney.core.domain.common.IdGenerator
import com.mymoney.core.domain.common.Outcome
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.model.Account
import com.mymoney.core.domain.model.AccountType
import com.mymoney.core.domain.model.Category
import com.mymoney.core.domain.model.CategoryType
import com.mymoney.core.domain.model.Entry
import com.mymoney.core.domain.model.EntryGroup
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.model.EntrySplit
import com.mymoney.core.domain.model.EntryStatus
import com.mymoney.core.domain.model.Tag
import com.mymoney.core.domain.usecase.AdjustmentDraft
import com.mymoney.core.domain.usecase.IncomeExpenseDraft
import com.mymoney.core.domain.usecase.RecordOperationUseCase
import com.mymoney.core.domain.usecase.RefundDraft
import com.mymoney.core.domain.usecase.SplitDraft
import com.mymoney.core.domain.usecase.TransferDraft
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerRepositoryTest {
    private lateinit var db: MyMoneyDatabase
    private lateinit var ledger: LedgerRepositoryImpl
    private lateinit var accounts: AccountRepositoryImpl
    private lateinit var categories: CategoryRepositoryImpl
    private lateinit var record: RecordOperationUseCase
    private val day = LocalDate.of(2026, 10, 1)
    private val food = SeedData.categoryId("expense.food")
    private val rent = SeedData.categoryId("expense.housing")
    private val salary = SeedData.categoryId("income.salary")

    private val clock = object : TimeProvider {
        var t = 1_760_000_000_000
        override fun nowMillis() = t++
        override fun today() = LocalDate.of(2026, 10, 13)
    }
    private val ids = object : IdGenerator {
        var n = 0
        override fun newId() = "id-${n++}"
    }

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MyMoneyDatabase::class.java)
            .addCallback(MyMoneyDatabase.SeedCallback)
            .allowMainThreadQueries()
            .build()
        val log = ChangeLogger(db.changeLogDao(), ids, clock)
        ledger = LedgerRepositoryImpl(db, log, clock)
        accounts = AccountRepositoryImpl(db, log, ids, clock)
        categories = CategoryRepositoryImpl(db, log, ids, clock)
        record = RecordOperationUseCase(
            accounts, categories, CurrencyRepositoryImpl(db), ledger, ExchangeRateRepositoryImpl(db), ids, clock,
        )
        accounts.create(Account("bank", "Bank", AccountType.BANK, "IRR", startDate = LocalDate.of(2026, 1, 1)), 10_000_000)
        accounts.create(Account("cash", "Cash", AccountType.CASH, "IRR", startDate = LocalDate.of(2026, 1, 1)), 0)
        accounts.create(Account("eur", "Euro", AccountType.BANK, "EUR", startDate = LocalDate.of(2026, 1, 1)), 50_000)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun balances() = accounts.observeWithBalances().first().associate { it.account.id to it.balanceMinor }

    @Test
    fun `seeded categories include the protected system categories`() = runTest {
        val all = categories.getAll()
        assertEquals(SeedData.categories.size, all.size)
        assertNotNull(categories.getBySystemKey("expense.fees"))
        assertNotNull(categories.getBySystemKey("expense.interest"))
    }

    @Test
    fun `SQL balances follow the ledger (FBR-17)`() = runTest {
        record(IncomeExpenseDraft("g1", EntryKind.INCOME, "bank", 5_000_000, day, salary))
        record(IncomeExpenseDraft("g2", EntryKind.EXPENSE, "bank", 1_000_000, day, food))
        record(IncomeExpenseDraft("g3", EntryKind.EXPENSE, "bank", 70_000, day, food, status = EntryStatus.PENDING))
        record(TransferDraft("t1", "bank", "cash", 2_000_000, 2_000_000, day, feeMinor = 30_000))
        val b = balances()
        assertEquals(10_000_000 + 5_000_000 - 1_000_000 - 2_000_000 - 30_000, b["bank"])
        assertEquals(2_000_000, b["cash"])
        val bank = accounts.observeWithBalances().first().first { it.account.id == "bank" }
        assertEquals(-70_000, bank.pendingMinor)
        assertEquals(b["bank"], accounts.balance("bank"))
        assertEquals(10_000_000, accounts.balance("bank", upTo = day.minusDays(1)))
    }

    @Test
    fun `saving the same group twice is a no-op (FBR-13)`() = runTest {
        val group = EntryGroup("dup", listOf(Entry("e1", "dup", "bank", EntryKind.EXPENSE, -100, day, categoryId = food)))
        assertIs<Outcome.Success<Unit>>(ledger.insertGroup(group))
        assertEquals(Outcome.Failure(DomainError.AlreadySaved), ledger.insertGroup(group.copy(entries = group.entries.map { it.copy(id = "e2") })))
        assertEquals(10_000_000 - 100, accounts.balance("bank"))
    }

    @Test
    fun `a failing leg rolls back the whole operation (atomicity)`() = runTest {
        val group = EntryGroup(
            "bad",
            listOf(
                Entry("ok-leg", "bad", "bank", EntryKind.TRANSFER, -500, day),
                Entry("bad-leg", "bad", "missing-account", EntryKind.TRANSFER, 500, day),
            ),
        )
        val failed = runCatching { ledger.insertGroup(group) }
        assertTrue(failed.isFailure, "FK violation must abort")
        assertNull(ledger.getGroup("bad"))
        assertEquals(10_000_000, accounts.balance("bank"))
    }

    @Test
    fun `split expenses report per category through the view`() = runTest {
        record(
            IncomeExpenseDraft(
                "s1", EntryKind.EXPENSE, "bank", 1_000_000, day, null,
                splits = listOf(SplitDraft(food, 600_000), SplitDraft(rent, 400_000)),
            ),
        )
        val rows = ledger.categoryAmounts(day, day)
        assertEquals(-600_000, rows.single { it.categoryId == food }.amountMinor)
        assertEquals(-400_000, rows.single { it.categoryId == rent }.amountMinor)
        val pnl = IncomeStatementCalculator.compute(rows, RateBook(emptyList(), CurrencyRepositoryImpl(db).getAll().associateBy { it.code }, "IRR"), "IRR")
        assertEquals(1_000_000, pnl.expenseMinor)
    }

    @Test
    fun `editing keeps leg ids so refunds stay linked`() = runTest {
        record(IncomeExpenseDraft("e", EntryKind.EXPENSE, "bank", 900_000, day, food))
        val original = ledger.getGroup("e")!!.entries.single()
        record(RefundDraft("r", "bank", 300_000, day, original.id, null))
        // Edit the expense amount
        record(IncomeExpenseDraft("e", EntryKind.EXPENSE, "bank", 950_000, day, food, payee = "Shop"))
        val edited = ledger.getGroup("e")!!.entries.single()
        assertEquals(original.id, edited.id)
        assertEquals(-950_000, edited.amountMinor)
        assertEquals(original.id, ledger.getGroup("r")!!.entries.single().relatedEntryId)
        assertEquals(300_000, ledger.refundedTotal(original.id))
    }

    @Test
    fun `soft delete, restore and purge (FBR-11)`() = runTest {
        record(TransferDraft("t", "bank", "cash", 1_000, 1_000, day, feeMinor = 10))
        ledger.softDeleteGroup("t")
        assertEquals(0, accounts.balance("cash"))
        assertNull(ledger.getGroup("t"))
        ledger.restoreGroup("t")
        assertEquals(1_000, accounts.balance("cash"))
        ledger.softDeleteGroup("t")
        assertEquals(3, ledger.purgeDeletedBefore(Long.MAX_VALUE))
    }

    @Test
    fun `adjustment is traceable in the change log (FBR-15)`() = runTest {
        record(AdjustmentDraft("adj", "cash", 42_000, day, "counted the wallet"))
        assertEquals(42_000, accounts.balance("cash"))
        val log = db.changeLogDao().history(ChangeLogger.ENTRY_GROUP, "adj").single()
        assertEquals("ADJUST", log.action)
        assertEquals("counted the wallet", log.reason)
        assertTrue(log.afterJson!!.contains("\"k\":\"ADJUSTMENT\""))
    }

    @Test
    fun `opening balance edits replace the opening entry (FBR-33)`() = runTest {
        val acc = accounts.get("cash")!!
        accounts.update(acc, 777)
        assertEquals(777, accounts.openingBalance("cash"))
        accounts.update(acc, 0)
        assertEquals(0, accounts.entryCount("cash"))
    }

    @Test
    fun `category delete requires reassignment when used`() = runTest {
        categories.save(Category("", CategoryType.EXPENSE, name = "Coffee"))
        val coffee = categories.getAll().single { it.name == "Coffee" }
        record(IncomeExpenseDraft("c", EntryKind.EXPENSE, "bank", 50_000, day, coffee.id))
        assertEquals(Outcome.Failure(DomainError.InUse("category")), categories.delete(coffee.id, null))
        assertIs<Outcome.Success<Unit>>(categories.delete(coffee.id, food))
        assertEquals(food, ledger.getGroup("c")!!.entries.single().categoryId)
        assertIs<Outcome.Failure>(categories.delete(SeedData.categoryId("expense.fees"), null))
    }

    @Test
    fun `transaction list query filters and hides incoming transfer legs`() = runTest {
        val tags = TagRepositoryImpl(db, ids, clock)
        tags.save(Tag("tag-1", "travel"))
        record(IncomeExpenseDraft("a", EntryKind.EXPENSE, "bank", 1_000, day, food, payee = "Café 50% off", tagIds = setOf("tag-1")))
        record(IncomeExpenseDraft("b", EntryKind.INCOME, "bank", 2_000, day.minusDays(1), salary))
        record(TransferDraft("t", "bank", "cash", 3_000, 3_000, day))
        val q = EntryQueries(db)
        val all = q.list(EntryFilter(), 100)
        // 3 opening entries? no: only bank and eur have openings → 2, plus a, b, transfer out-leg
        assertEquals(setOf("a", "b", "t"), all.filter { it.kind != EntryKind.OPENING_BALANCE }.map { it.groupId }.toSet())
        assertEquals("Cash", all.single { it.groupId == "t" }.counterpartAccountName)
        assertEquals(listOf("a"), q.list(EntryFilter(text = "50%"), 100).map { it.groupId })
        assertEquals(listOf("a"), q.list(EntryFilter(tagId = "tag-1"), 100).map { it.groupId })
        assertEquals(listOf("b"), q.list(EntryFilter(kinds = setOf(EntryKind.INCOME)), 100).map { it.groupId })
        assertEquals(listOf("t"), q.list(EntryFilter(accountId = "cash"), 100).map { it.groupId })
        assertEquals(setOf("a", "t"), q.list(EntryFilter(from = day, to = day), 100).map { it.groupId }.toSet())
        assertEquals(listOf("a"), q.list(EntryFilter(categoryIds = setOf(food)), 100).map { it.groupId })
    }

    @Test
    fun `split category filter matches split parts`() = runTest {
        record(IncomeExpenseDraft("s", EntryKind.EXPENSE, "bank", 1_000, day, null, splits = listOf(SplitDraft(food, 400), SplitDraft(rent, 600))))
        assertEquals(listOf("s"), EntryQueries(db).list(EntryFilter(categoryIds = setOf(rent)), 10).map { it.groupId })
        assertEquals(2, EntryQueries(db).list(EntryFilter(categoryIds = setOf(rent)), 10).single().splitCount)
        // the EntrySplit domain mapping round-trips
        val e = ledger.getGroup("s")!!.entries.single()
        assertEquals(setOf(EntrySplit::class), e.splits.map { it::class }.toSet())
    }
}
