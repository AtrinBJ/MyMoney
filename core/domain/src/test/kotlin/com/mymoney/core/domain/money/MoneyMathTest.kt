package com.mymoney.core.domain.money

import com.mymoney.core.domain.common.AmountProblem
import com.mymoney.core.domain.common.DomainError
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** FBR-14: exact arithmetic, single HALF_EVEN rounding, allocation that always sums to the total. */
class MoneyMathTest {

    @Test
    fun `sum overflow is an error, never a wrong number`() {
        assertFailsWith<ArithmeticException> { MoneyMath.sum(listOf(Long.MAX_VALUE, 1)) }
    }

    @Test
    fun `amount limit is 10^15 minor units`() {
        assertNull(MoneyMath.validateAmount(MoneyMath.MAX_ABS_MINOR))
        assertNull(MoneyMath.validateAmount(-MoneyMath.MAX_ABS_MINOR))
        assertEquals(
            DomainError.InvalidAmount(AmountProblem.TOO_LARGE),
            MoneyMath.validateAmount(MoneyMath.MAX_ABS_MINOR + 1),
        )
        assertEquals(DomainError.InvalidAmount(AmountProblem.ZERO), MoneyMath.validateAmount(0))
        assertNull(MoneyMath.validateAmount(0, allowZero = true))
    }

    @Test
    fun `conversion rounds once with banker's rounding`() {
        // 1.005 EUR? not representable; use 2.5 units → HALF_EVEN to 2, 3.5 → 4
        assertEquals(2, MoneyMath.convert(25, fromDigits = 1, toDigits = 0, rate = BigDecimal.ONE))
        assertEquals(4, MoneyMath.convert(35, fromDigits = 1, toDigits = 0, rate = BigDecimal.ONE))
        // 100.00 EUR at 1.0835 USD/EUR = 108.35 USD
        assertEquals(10_835, MoneyMath.convert(10_000, 2, 2, BigDecimal("1.0835")))
        // 12.34 USD at 1,050,000 IRR/USD = 12,957,000 IRR (0 decimals)
        assertEquals(12_957_000, MoneyMath.convert(1_234, 2, 0, BigDecimal("1050000")))
        // IRR → USD: 1,000,000 rial at 1/1,050,000 → 0.95 USD (0.952380… → 0.95)
        assertEquals(95, MoneyMath.convert(1_000_000, 0, 2, MoneyMath.inverse(BigDecimal("1050000"))))
    }

    @Test
    fun `conversion rejects non-positive rate`() {
        assertFailsWith<IllegalArgumentException> { MoneyMath.convert(100, 2, 2, BigDecimal.ZERO) }
        assertFailsWith<IllegalArgumentException> { MoneyMath.convert(100, 2, 2, BigDecimal("-1")) }
    }

    @Test
    fun `allocate splits by weight and keeps the total`() {
        assertEquals(listOf(34L, 33L, 33L), MoneyMath.allocate(100, listOf(1, 1, 1)))
        assertEquals(listOf(-34L, -33L, -33L), MoneyMath.allocate(-100, listOf(1, 1, 1)))
        assertEquals(listOf(0L, 100L), MoneyMath.allocate(100, listOf(0, 5)))
        assertEquals(listOf(7L, 3L), MoneyMath.allocate(10, listOf(70, 30)))
        assertEquals(listOf(1L, 1L, 0L), MoneyMath.splitEvenly(2, 3))
    }

    @Test
    fun `property - allocation parts always sum to the total and are proportional within one unit`() = runTest {
        checkAll(
            Arb.long(-MoneyMath.MAX_ABS_MINOR..MoneyMath.MAX_ABS_MINOR),
            Arb.list(Arb.long(0L..1_000_000L), 1..12),
        ) { total, rawWeights ->
            val weights = if (rawWeights.all { it == 0L }) rawWeights.mapIndexed { i, w -> if (i == 0) 1L else w } else rawWeights
            val parts = MoneyMath.allocate(total, weights)
            assertEquals(total, parts.sum())
            val wSum = weights.sum().toBigDecimal()
            parts.zip(weights).forEach { (p, w) ->
                val exact = (total.toBigDecimal() * w.toBigDecimal()).divide(wSum, java.math.MathContext.DECIMAL128)
                val diff = (p.toBigDecimal() - exact).abs()
                assertTrue(diff < BigDecimal.ONE, "part $p deviates from $exact")
            }
        }
    }

    @Test
    fun `property - splitEvenly parts differ by at most one`() = runTest {
        checkAll(Arb.long(-1_000_000_000L..1_000_000_000L), Arb.int(1..60)) { total, n ->
            val parts = MoneyMath.splitEvenly(total, n)
            assertEquals(total, parts.sum())
            assertTrue(parts.max() - parts.min() <= 1)
        }
    }
}
