package com.mymoney.core.domain.usecase

import com.mymoney.core.domain.calc.IncomeStatementCalculator
import com.mymoney.core.domain.calc.RateBook
import com.mymoney.core.domain.common.AmountProblem
import com.mymoney.core.domain.common.DomainError
import com.mymoney.core.domain.common.Outcome
import com.mymoney.core.domain.model.Account
import com.mymoney.core.domain.model.AccountType
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.model.EntryStatus
import com.mymoney.core.domain.model.RateSource
import com.mymoney.core.domain.testing.FixedClock
import com.mymoney.core.domain.testing.InMemoryStore
import com.mymoney.core.domain.testing.SequentialIds
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RecordOperationUseCaseTest {
    private lateinit var store: InMemoryStore
    private lateinit var record: RecordOperationUseCase
    private val ids = SequentialIds()
    private val day = LocalDate.of(2026, 10, 1)

    @BeforeEach
    fun setUp() = runTest {
        store = InMemoryStore()
        record = RecordOperationUseCase(
            store.accountRepo, store.categoryRepo, store.currencyRepo, store.ledgerRepo, store.rateRepo, ids, FixedClock(),
        )
        account("bank", "IRR", 10_000_000)
        account("cash", "IRR", 0)
        account("eur", "EUR", 50_000) // €500.00
        account("card", "IRR", 0, AccountType.CREDIT_CARD)
    }

    private suspend fun account(id: String, currency: String, opening: Long, type: AccountType = AccountType.BANK) {
        store.accountRepo.create(Account(id, id, type, currency, startDate = LocalDate.of(2026, 1, 1)), opening)
    }

    private suspend fun ok(draft: OperationDraft) {
        val r = record(draft)
        assertIs<Outcome.Success<RecordResult>>(r, "expected success, got $r")
    }

    private suspend fun fail(draft: OperationDraft): DomainError {
        val r = record(draft)
        return assertIs<Outcome.Failure>(r).error
    }

    private fun expense(g: String, acc: String, amount: Long, cat: String? = "cat-food", splits: List<SplitDraft> = emptyList()) =
        IncomeExpenseDraft(g, EntryKind.EXPENSE, acc, amount, day, cat, splits)

    @Test
    fun `income increases and expense decreases the balance`() = runTest {
        ok(IncomeExpenseDraft("g1", EntryKind.INCOME, "bank", 5_000_000, day, "cat-salary"))
        ok(expense("g2", "bank", 1_200_000))
        assertEquals(10_000_000 + 5_000_000 - 1_200_000, store.balanceOf("bank"))
    }

    @Test
    fun `same groupId saved twice changes nothing (FBR-13)`() = runTest {
        val d = expense("g1", "bank", 700_000)
        ok(d)
        ok(d.copy(amountMinor = 700_000))
        // Second save with the same id is an edit of the same operation, not a second expense.
        assertEquals(10_000_000 - 700_000, store.balanceOf("bank"))
        assertEquals(1, store.entries.values.count { it.groupId == "g1" })
    }

    @Test
    fun `transfer moves money without income or expense (FBR-02, FBR-16)`() = runTest {
        ok(TransferDraft("t1", "bank", "cash", 2_000_000, 2_000_000, day))
        assertEquals(8_000_000, store.balanceOf("bank"))
        assertEquals(2_000_000, store.balanceOf("cash"))
        val pnl = IncomeStatementCalculator.compute(
            store.ledgerRepo.categoryAmounts(day, day), RateBook(emptyList(), store.currencies, "IRR"), "IRR",
        )
        assertEquals(0, pnl.incomeMinor)
        assertEquals(0, pnl.expenseMinor)
    }

    @Test
    fun `transfer fee is a separate expense (FBR-03)`() = runTest {
        ok(TransferDraft("t1", "bank", "cash", 2_000_000, 2_000_000, day, feeMinor = 50_000))
        assertEquals(10_000_000 - 2_000_000 - 50_000, store.balanceOf("bank"))
        assertEquals(2_000_000, store.balanceOf("cash"))
        val pnl = IncomeStatementCalculator.compute(
            store.ledgerRepo.categoryAmounts(day, day), RateBook(emptyList(), store.currencies, "IRR"), "IRR",
        )
        assertEquals(50_000, pnl.expenseMinor)
        assertEquals(50_000, pnl.byCategory["cat-fees"]?.netExpenseMinor)
    }

    @Test
    fun `cross-currency transfer keeps both actual amounts and records the implied rate (FBR-03)`() = runTest {
        // €100.00 → 105,000,000 rial (rate 1,050,000 IRR per EUR)
        ok(TransferDraft("fx", "eur", "bank", 10_000, 105_000_000, day, feeMinor = 200))
        assertEquals(50_000 - 10_000 - 200, store.balanceOf("eur"))
        assertEquals(10_000_000 + 105_000_000, store.balanceOf("bank"))
        val rate = store.rates.single()
        assertEquals(RateSource.TRANSFER_IMPLIED, rate.source)
        assertEquals("EUR", rate.base)
        assertEquals(0, BigDecimal("1050000").compareTo(rate.rate))
    }

    @Test
    fun `transfer validation (FBR-16)`() = runTest {
        assertIs<DomainError.RuleViolation>(fail(TransferDraft("t", "bank", "bank", 10, 10, day)))
        assertIs<DomainError.RuleViolation>(fail(TransferDraft("t", "bank", "cash", 10, 11, day)))
        assertEquals(DomainError.InvalidAmount(AmountProblem.ZERO), fail(TransferDraft("t", "bank", "cash", 0, 0, day)))
    }

    @Test
    fun `refund links to the expense and cannot exceed it (FBR-08)`() = runTest {
        ok(expense("e1", "card", 900_000, cat = "cat-food"))
        val original = store.entries.values.single { it.groupId == "e1" }
        ok(RefundDraft("r1", "card", 400_000, day, original.id, categoryId = null))
        val refund = store.entries.values.single { it.groupId == "r1" }
        assertEquals("cat-food", refund.categoryId)
        assertEquals(original.id, refund.relatedEntryId)
        assertEquals(-500_000, store.balanceOf("card"))

        assertIs<DomainError.RuleViolation>(fail(RefundDraft("r2", "card", 500_001, day, original.id, null)))
        ok(RefundDraft("r2", "card", 500_000, day, original.id, null))
        assertEquals(0, store.balanceOf("card"))

        val pnl = IncomeStatementCalculator.compute(
            store.ledgerRepo.categoryAmounts(day, day), RateBook(emptyList(), store.currencies, "IRR"), "IRR",
        )
        assertEquals(0, pnl.expenseMinor)
    }

    @Test
    fun `refund of a split expense is allocated proportionally`() = runTest {
        ok(expense("e1", "bank", 1_000_000, cat = null, splits = listOf(SplitDraft("cat-food", 750_000), SplitDraft("cat-rent", 250_000))))
        val original = store.entries.values.single { it.groupId == "e1" }
        ok(RefundDraft("r1", "bank", 100_001, day, original.id, null))
        val refund = store.entries.values.single { it.groupId == "r1" }
        assertNull(refund.categoryId)
        assertEquals(listOf(75_001L, 25_000L), refund.splits.map { it.amountMinor })
    }

    @Test
    fun `adjustment records the difference with a reason (FBR-15)`() = runTest {
        ok(AdjustmentDraft("a1", "cash", actualBalanceMinor = 123_000, date = day, reason = "counted wallet"))
        assertEquals(123_000, store.balanceOf("cash"))
        val adj = store.entries.values.single { it.groupId == "a1" }
        assertEquals(EntryKind.ADJUSTMENT, adj.kind)
        assertTrue(store.changeReasons.contains("counted wallet"))
        // Editing the same adjustment re-targets the balance instead of stacking.
        ok(AdjustmentDraft("a1", "cash", actualBalanceMinor = 100_000, date = day, reason = "recount"))
        assertEquals(100_000, store.balanceOf("cash"))
        assertEquals(DomainError.Required("reason"), fail(AdjustmentDraft("a2", "cash", 5, day, " ")))
    }

    @Test
    fun `pending entries do not change the current balance (FBR-11)`() = runTest {
        ok(expense("p1", "bank", 300_000).copy(status = EntryStatus.PENDING))
        assertEquals(10_000_000, store.balanceOf("bank"))
        assertEquals(9_700_000, store.balanceOf("bank", includePending = true))
    }

    @Test
    fun `soft-deleted operations vanish from balances and can be restored (FBR-11)`() = runTest {
        ok(TransferDraft("t1", "bank", "cash", 1_000_000, 1_000_000, day, feeMinor = 10_000))
        store.ledgerRepo.softDeleteGroup("t1")
        assertEquals(10_000_000, store.balanceOf("bank"))
        assertEquals(0, store.balanceOf("cash"))
        store.ledgerRepo.restoreGroup("t1")
        assertEquals(1_000_000, store.balanceOf("cash"))
    }

    @Test
    fun `category rules`() = runTest {
        // Income with an expense category
        assertIs<DomainError.RuleViolation>(fail(IncomeExpenseDraft("x", EntryKind.INCOME, "bank", 10, day, "cat-food")))
        // Missing category
        assertEquals(DomainError.Required("category"), fail(expense("x", "bank", 10, cat = null)))
        // Split that does not add up
        assertIs<DomainError.RuleViolation>(
            fail(expense("x", "bank", 100, cat = null, splits = listOf(SplitDraft("cat-food", 60), SplitDraft("cat-rent", 30)))),
        )
        // Too large
        assertEquals(
            DomainError.InvalidAmount(AmountProblem.TOO_LARGE),
            fail(expense("x", "bank", 1_000_000_000_000_001)),
        )
    }

    @Test
    fun `duplicate detection matches account, amount, date window and payee (FBR-36)`() = runTest {
        ok(expense("e1", "bank", 450_000).copy(payee = "Café  Naderi"))
        val dup = FindPossibleDuplicatesUseCase(store.ledgerRepo)
        assertEquals(1, dup("bank", EntryKind.EXPENSE, -450_000, day.plusDays(1), "café naderi", null).size)
        assertEquals(0, dup("bank", EntryKind.EXPENSE, -450_000, day.plusDays(2), "Café Naderi", null).size)
        assertEquals(0, dup("bank", EntryKind.EXPENSE, -450_001, day, "Café Naderi", null).size)
        assertEquals(0, dup("bank", EntryKind.EXPENSE, -450_000, day, "Other shop", null).size)
        assertEquals(0, dup("bank", EntryKind.EXPENSE, -450_000, day, "Café Naderi", excludingGroupId = "e1").size)
    }
}
