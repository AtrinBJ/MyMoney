package com.mymoney.core.domain.usecase

import com.mymoney.core.domain.calc.IncomeStatementCalculator
import com.mymoney.core.domain.calc.RateBook
import com.mymoney.core.domain.common.Outcome
import com.mymoney.core.domain.model.Account
import com.mymoney.core.domain.model.AccountType
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.model.EntryStatus
import com.mymoney.core.domain.testing.FixedClock
import com.mymoney.core.domain.testing.InMemoryStore
import com.mymoney.core.domain.testing.SequentialIds
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Property-based check of the ledger rules (docs/07 §7.4): random sequences of valid operations
 * must keep the accounting identities, whatever the order and amounts.
 */
class LedgerInvariantPropertyTest {

    private sealed interface Op {
        data class Income(val acc: Int, val amount: Long) : Op
        data class Expense(val acc: Int, val amount: Long, val pending: Boolean) : Op
        data class Transfer(val from: Int, val to: Int, val amount: Long, val inAmount: Long, val fee: Long) : Op
        data class Refund(val ofIndex: Int, val percent: Int) : Op
        data class Delete(val ofIndex: Int) : Op
    }

    // Accounts: 0..2 IRR, 3 EUR, 4 JPY (0 digits), 5 KWD (3 digits)
    private val currencyOf = listOf("IRR", "IRR", "IRR", "EUR", "JPY", "KWD")

    private val opArb = arbitrary { rs ->
        val amount = Arb.long(1L..5_000_000_000L).bind()
        when (Arb.int(0..4).bind()) {
            0 -> Op.Income(Arb.int(0..5).bind(), amount)
            1 -> Op.Expense(Arb.int(0..5).bind(), amount, Arb.boolean().bind())
            2 -> {
                val from = Arb.int(0..5).bind()
                var to = Arb.int(0..5).bind()
                if (to == from) to = (from + 1) % 6
                val inAmount = if (currencyOf[from] == currencyOf[to]) amount else Arb.long(1L..5_000_000_000L).bind()
                Op.Transfer(from, to, amount, inAmount, if (Arb.boolean().bind()) Arb.long(1L..100_000L).bind() else 0)
            }
            3 -> Op.Refund(Arb.int(0..199).bind(), Arb.int(1..100).bind())
            else -> Op.Delete(Arb.int(0..199).bind())
        }
    }

    @Test
    fun `ledger identities hold for any sequence of operations`() = runTest {
        checkAll(PropTestConfig(iterations = 150), Arb.list(opArb, 1..120)) { ops ->
            val store = InMemoryStore()
            val ids = SequentialIds("p")
            val record = RecordOperationUseCase(
                store.accountRepo, store.categoryRepo, store.currencyRepo, store.ledgerRepo, store.rateRepo, ids, FixedClock(),
            )
            val day = LocalDate.of(2026, 5, 1)
            currencyOf.forEachIndexed { i, cur ->
                store.accountRepo.create(Account("a$i", "a$i", AccountType.BANK, cur, startDate = day), 1_000_000L * (i + 1))
            }
            val expenseGroups = mutableListOf<String>()
            val allGroups = mutableListOf<String>()

            ops.forEachIndexed { n, op ->
                val g = "g$n"
                val result = when (op) {
                    is Op.Income -> record(IncomeExpenseDraft(g, EntryKind.INCOME, "a${op.acc}", op.amount, day, "cat-salary"))
                    is Op.Expense -> record(
                        IncomeExpenseDraft(
                            g, EntryKind.EXPENSE, "a${op.acc}", op.amount, day, "cat-food",
                            status = if (op.pending) EntryStatus.PENDING else EntryStatus.COMPLETED,
                        ),
                    ).also { if (it is Outcome.Success) expenseGroups += g }
                    is Op.Transfer -> record(TransferDraft(g, "a${op.from}", "a${op.to}", op.amount, op.inAmount, day, feeMinor = op.fee))
                    is Op.Refund -> {
                        val target = expenseGroups.getOrNull(op.ofIndex % (expenseGroups.size.coerceAtLeast(1)))
                        val original = target?.let { store.ledgerRepo.getGroup(it)?.entries?.single() }
                        if (original == null || original.status == EntryStatus.PENDING) null else {
                            val refundable = -original.amountMinor - store.ledgerRepo.refundedTotal(original.id)
                            val amount = refundable * op.percent / 100
                            if (amount <= 0) null else record(RefundDraft(g, original.accountId, amount, day, original.id, null))
                        }
                    }
                    is Op.Delete -> {
                        allGroups.getOrNull(op.ofIndex % (allGroups.size.coerceAtLeast(1)))?.let { store.ledgerRepo.softDeleteGroup(it) }
                        null
                    }
                }
                if (result is Outcome.Success) allGroups += g
            }

            val live = store.entries.values.filter { it.deletedAt == null && it.status == EntryStatus.COMPLETED }

            // 1. Per currency: Σ balances = Σ opening + income + expenses(−) + refunds + cross-currency transfers in/out.
            //    Same-currency transfers must not create or destroy money (FBR-02).
            currencyOf.distinct().forEach { cur ->
                val accs = currencyOf.indices.filter { currencyOf[it] == cur }.map { "a$it" }.toSet()
                val inCur = live.filter { it.accountId in accs }
                val balances = accs.sumOf { store.balanceOf(it) }
                val nonTransfer = inCur.filter { it.kind != EntryKind.TRANSFER }.sumOf { it.amountMinor }
                val crossCurrency = inCur.filter { e ->
                    e.kind == EntryKind.TRANSFER && live.any { o ->
                        o.groupId == e.groupId && o.id != e.id && o.kind == EntryKind.TRANSFER && o.accountId !in accs
                    }
                }.sumOf { it.amountMinor }
                assertEquals(balances, nonTransfer + crossCurrency)
            }

            // 2. Same-currency transfer legs always cancel out (FBR-16).
            live.filter { it.kind == EntryKind.TRANSFER }.groupBy { it.groupId }.forEach { (_, legs) ->
                assertEquals(2, legs.size)
                val (a, b) = legs
                if (store.accounts.getValue(a.accountId).currency == store.accounts.getValue(b.accountId).currency) {
                    assertEquals(0, a.amountMinor + b.amountMinor)
                }
            }

            // 3. Income statement only sees INCOME / EXPENSE / REFUND (FBR-01, FBR-02).
            val pnl = IncomeStatementCalculator.compute(
                store.ledgerRepo.categoryAmounts(day, day), RateBook(emptyList(), store.currencies, "IRR"), "IRR",
            )
            val irrAccounts = setOf("a0", "a1", "a2")
            val irrLive = live.filter { it.accountId in irrAccounts }
            assertEquals(irrLive.filter { it.kind == EntryKind.INCOME }.sumOf { it.amountMinor }, pnl.incomeMinor)
            assertEquals(
                -irrLive.filter { it.kind == EntryKind.EXPENSE }.sumOf { it.amountMinor } -
                    irrLive.filter { it.kind == EntryKind.REFUND }.sumOf { it.amountMinor },
                pnl.expenseMinor,
            )

            // 4. Refunds never exceed their expense (FBR-08).
            live.filter { it.kind == EntryKind.REFUND }.groupBy { it.relatedEntryId }.forEach { (orig, refunds) ->
                val original = store.entries.getValue(orig!!)
                assertTrue(refunds.sumOf { it.amountMinor } <= -original.amountMinor)
            }
        }
    }
}
