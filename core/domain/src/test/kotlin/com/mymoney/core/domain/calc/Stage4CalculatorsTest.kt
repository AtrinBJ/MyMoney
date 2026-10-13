package com.mymoney.core.domain.calc

import com.mymoney.core.domain.calendar.DateRange
import com.mymoney.core.domain.model.Account
import com.mymoney.core.domain.model.AccountType
import com.mymoney.core.domain.model.Asset
import com.mymoney.core.domain.model.AssetTrade
import com.mymoney.core.domain.model.AssetType
import com.mymoney.core.domain.model.AssetValuation
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.model.GoalContribution
import com.mymoney.core.domain.model.SavingsGoal
import com.mymoney.core.domain.model.TradeType
import com.mymoney.core.domain.money.Currency
import com.mymoney.core.domain.repository.FlowRow
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class Stage4CalculatorsTest {

    // ---- Amortization (FBR-34) ----
    @Test
    fun `annuity schedule matches the closed formula and sums exactly`() {
        // 12,000,000 at 24%/yr for 12 months: r = 2%, A = P·r(1+r)^n/((1+r)^n−1) = 1,134,715.159…
        val s = Amortization.annuity(12_000_000, BigDecimal("24"), 12)
        assertEquals(1_134_715, s.installments.first().paymentMinor)
        assertEquals(240_000, s.installments.first().interestMinor)
        assertEquals(12_000_000, s.installments.sumOf { it.principalMinor })
        assertEquals(0, s.installments.last().balanceAfterMinor)
        assertTrue(s.totalInterestMinor in 1_616_570..1_616_600, "interest ${s.totalInterestMinor}")
    }

    @Test
    fun `zero rate and simple interest`() {
        val zero = Amortization.annuity(1_000, BigDecimal.ZERO, 3)
        assertEquals(listOf(333L, 333L, 334L), zero.installments.map { it.paymentMinor })
        val simple = Amortization.simple(12_000_000, BigDecimal("18"), 12) // 18% of 12M for 1 year = 2.16M
        assertEquals(2_160_000, simple.totalInterestMinor)
        assertEquals(12_000_000, simple.installments.sumOf { it.principalMinor })
    }

    @Test
    fun `property - principal parts always sum to the loan`() = runTest {
        checkAll(Arb.long(1L..10_000_000_000L), Arb.int(0..60), Arb.int(1..120)) { p, rate, n ->
            val s = Amortization.annuity(p, BigDecimal(rate), n)
            assertEquals(p, s.installments.sumOf { it.principalMinor })
            assertTrue(s.installments.all { it.principalMinor >= 0 && it.interestMinor >= 0 })
        }
    }

    // ---- Assets (FBR-30) ----
    private val gold = Asset("g", "Gold", AssetType.GOLD, "gram", "IRR")
    private fun t(type: TradeType, q: String, gross: Long, fees: Long = 0, day: Int = 1) =
        AssetTrade("t$day$type", "g", LocalDate.of(2026, 1, day), type, BigDecimal(q), gross, fees)

    @Test
    fun `average cost, realized and unrealized gains`() {
        val trades = listOf(
            t(TradeType.BUY, "10", 100_000_000, 1_000_000, 1), // cost 101M
            t(TradeType.BUY, "10", 120_000_000, 0, 2), // cost 221M, qty 20, avg 11.05M
            t(TradeType.SELL, "5", 70_000_000, 500_000, 3), // costOfSold 55.25M, realized 69.5 − 55.25 = 14.25M
        )
        val v = AssetValuation("v", "g", LocalDate.of(2026, 1, 4), unitPrice = BigDecimal("14000000"), currency = "IRR")
        val h = AssetCalculator.holding(gold, trades, listOf(v), 0)
        assertEquals(0, BigDecimal("15").compareTo(h.quantity))
        assertEquals(165_750_000, h.costBasisMinor)
        assertEquals(14_250_000, h.realizedTotalMinor)
        assertEquals(210_000_000, h.valueMinor)
        assertEquals(44_250_000, h.unrealizedMinor)
    }

    @Test
    fun `selling more than held is an error`() {
        assertFailsWith<OversellException> { AssetCalculator.holding(gold, listOf(t(TradeType.BUY, "1", 10), t(TradeType.SELL, "2", 10, day = 2)), emptyList(), 0) }
    }

    @Test
    fun `stale valuation and missing value`() {
        val h = AssetCalculator.holding(gold, listOf(t(TradeType.OPENING, "3", 0)), emptyList(), 0)
        assertNull(h.valueMinor)
        assertTrue(h.isStale(LocalDate.of(2026, 1, 1)))
        val old = AssetValuation("v", "g", LocalDate.of(2025, 11, 1), unitPrice = BigDecimal.ONE, currency = "IRR")
        assertTrue(AssetCalculator.holding(gold, emptyList(), listOf(old), 0).isStale(LocalDate.of(2026, 1, 1)))
    }

    // ---- Goals (FBR-23) ----
    @Test
    fun `goal progress, required pace and projection`() {
        val goal = SavingsGoal("goal", "Car", 12_000, "IRR", targetDate = LocalDate.of(2026, 12, 1))
        val today = LocalDate.of(2026, 6, 15)
        val contributions = (1..5).map { GoalContribution("c$it", "goal", null, LocalDate.of(2026, it, 10), 1_000) }
        val p = GoalProjection.progress(goal, contributions, null, today)
        assertEquals(5_000, p.currentMinor)
        assertEquals(7_000, p.remainingMinor)
        assertEquals(1_167, p.requiredPerMonthMinor) // 7000 over 6 months, rounded up
        assertTrue(p.projectedDate != null)
        assertEquals(false, p.onTrack)
    }

    // ---- Cash flow (FBR-09) ----
    @Test
    fun `cash flow sections and identity`() {
        val accounts = listOf(
            Account("bank", "b", AccountType.BANK, "IRR", startDate = LocalDate.of(2026, 1, 1)),
            Account("cash", "c", AccountType.CASH, "IRR", startDate = LocalDate.of(2026, 1, 1)),
            Account("loan", "l", AccountType.LIABILITY, "IRR", startDate = LocalDate.of(2026, 1, 1)),
            Account("save", "s", AccountType.SAVINGS, "IRR", startDate = LocalDate.of(2026, 1, 1)),
        ).associateBy { it.id }
        val d = LocalDate.of(2026, 2, 1)
        val rows = listOf(
            FlowRow("bank", "IRR", d, EntryKind.INCOME, null, 1000),
            FlowRow("bank", "IRR", d, EntryKind.EXPENSE, null, -300),
            FlowRow("bank", "IRR", d, EntryKind.REFUND, null, 20),
            FlowRow("bank", "IRR", d, EntryKind.TRANSFER, "cash", -100), // internal
            FlowRow("cash", "IRR", d, EntryKind.TRANSFER, "bank", 100), // internal
            FlowRow("bank", "IRR", d, EntryKind.TRANSFER, "loan", -200), // financing (principal)
            FlowRow("bank", "IRR", d, EntryKind.ASSET_BUY, null, -150),
            FlowRow("bank", "IRR", d, EntryKind.TRANSFER, "save", -50), // to an account outside the set
            FlowRow("bank", "IRR", d, EntryKind.ADJUSTMENT, null, 5),
        )
        val cf = CashFlowCalculator.compute(rows, accounts, setOf("bank", "cash"), RateBook(emptyList(), mapOf("IRR" to Currency("IRR", 0, "r", "r", "r")), "IRR"), "IRR")
        assertEquals(1020, cf.operatingIn)
        assertEquals(-300, cf.operatingOut)
        assertEquals(-200, cf.financingNet)
        assertEquals(-150, cf.investingNet)
        assertEquals(-50, cf.transfersOther)
        assertEquals(5, cf.adjustments)
        assertEquals(rows.filter { it.accountId in setOf("bank", "cash") }.sumOf { it.amountMinor }, cf.netChange)
    }

    // ---- Forecast (FBR-28) ----
    @Test
    fun `forecast uses typical weekly spending rate and reports a range`() {
        val history = List(60) { (it % 10 + 1) * 1000L } // 1000..10000
        val f = MonthEndForecast.compute(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 20), 100_000, 20_000, 30_000, -5_000, history)
        assertTrue(f.hasVariableEstimate)
        assertTrue(f.variableMedianMinor!! in 45_000..65_000) // about 5,500/day × 10 days
        // Spending every other day must not collapse to a zero estimate.
        val lumpy = MonthEndForecast.compute(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 20), 0, 0, 0, 0, List(60) { if (it % 2 == 0) 2000L else 0L })
        assertTrue(lumpy.variableMedianMinor!! in 8_000..12_000)
        assertTrue(f.lowMinor < f.projectedMinor && f.projectedMinor < f.highMinor)
        val short = MonthEndForecast.compute(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 20), 100_000, 0, 0, 0, List(10) { 1000L })
        assertEquals(false, short.hasVariableEstimate)
        assertEquals(100_000, short.projectedMinor)
    }

    @Suppress("unused")
    private fun range(a: String, b: String) = DateRange(LocalDate.parse(a), LocalDate.parse(b))
}
