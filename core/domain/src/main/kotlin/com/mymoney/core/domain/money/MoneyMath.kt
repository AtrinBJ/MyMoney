package com.mymoney.core.domain.money

import com.mymoney.core.domain.common.AmountProblem
import com.mymoney.core.domain.common.DomainError
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode

/**
 * Exact money arithmetic on minor units (FBR-14).
 *
 * - Amounts are `Long` minor units; every sum uses [Math.addExact] so overflow is an error, never a wrong number.
 * - Currency conversion goes through [BigDecimal] and rounds once, HALF_EVEN (banker's rounding).
 * - Splitting a total into parts uses the largest-remainder method so parts always sum to the total.
 */
object MoneyMath {
    /** |amount| ≤ 10^15 minor units for any single amount (FBR-38). */
    const val MAX_ABS_MINOR: Long = 1_000_000_000_000_000L

    val ROUNDING: RoundingMode = RoundingMode.HALF_EVEN

    /** Precision for intermediate rate math (inverse rates, cross rates, interest). */
    val RATE_CONTEXT = MathContext(20, RoundingMode.HALF_EVEN)

    fun isWithinLimit(minor: Long): Boolean = minor in -MAX_ABS_MINOR..MAX_ABS_MINOR

    fun validateAmount(minor: Long, allowZero: Boolean = false): DomainError? = when {
        minor == 0L && !allowZero -> DomainError.InvalidAmount(AmountProblem.ZERO)
        !isWithinLimit(minor) -> DomainError.InvalidAmount(AmountProblem.TOO_LARGE)
        else -> null
    }

    /** Sum that throws [ArithmeticException] on Long overflow. */
    fun sum(values: Iterable<Long>): Long = values.fold(0L) { acc, v -> Math.addExact(acc, v) }

    fun add(a: Long, b: Long): Long = Math.addExact(a, b)

    fun subtract(a: Long, b: Long): Long = Math.subtractExact(a, b)

    /** Minor units → decimal amount in major units (e.g. 12345 cents → 123.45). */
    fun toMajor(minor: Long, digits: Int): BigDecimal = BigDecimal.valueOf(minor, digits)

    /** Major-unit decimal → minor units, rounding HALF_EVEN to the currency's digits. */
    fun toMinor(major: BigDecimal, digits: Int): Long =
        major.setScale(digits, ROUNDING).unscaledValue().longValueExact()

    /**
     * Converts [minor] (in a currency with [fromDigits] decimals) by [rate] (1 from = rate to)
     * into minor units of a currency with [toDigits] decimals. Rounds once, HALF_EVEN.
     */
    fun convert(minor: Long, fromDigits: Int, toDigits: Int, rate: BigDecimal): Long {
        require(rate.signum() > 0) { "rate must be positive" }
        val major = toMajor(minor, fromDigits).multiply(rate)
        return major.setScale(toDigits, ROUNDING).unscaledValue().longValueExact()
    }

    /** Inverse of a rate with 20 significant digits. */
    fun inverse(rate: BigDecimal): BigDecimal = BigDecimal.ONE.divide(rate, RATE_CONTEXT)

    /**
     * Splits [total] into parts proportional to [weights] (all ≥ 0, sum > 0) so that
     * the parts sum exactly to [total]. Largest-remainder method; ties go to the earliest index.
     * The sign of every part follows the sign of [total].
     */
    fun allocate(total: Long, weights: List<Long>): List<Long> {
        require(weights.isNotEmpty()) { "weights must not be empty" }
        require(weights.all { it >= 0 }) { "weights must be non-negative" }
        val weightSum = weights.fold(BigInteger.ZERO) { acc, w -> acc + BigInteger.valueOf(w) }
        require(weightSum.signum() > 0) { "weights must not all be zero" }

        val sign = if (total < 0) -1 else 1
        val absTotal = BigInteger.valueOf(total).abs()
        val floors = LongArray(weights.size)
        val remainders = Array(weights.size) { BigInteger.ZERO }
        var allocated = BigInteger.ZERO
        weights.forEachIndexed { i, w ->
            val product = absTotal * BigInteger.valueOf(w)
            val (q, r) = product.divideAndRemainder(weightSum)
            floors[i] = q.longValueExact()
            remainders[i] = r
            allocated += q
        }
        var leftover = (absTotal - allocated).longValueExact()
        val order = weights.indices.sortedWith(compareByDescending<Int> { remainders[it] }.thenBy { it })
        var k = 0
        while (leftover > 0) {
            floors[order[k % order.size]] += 1
            leftover--
            k++
        }
        return floors.map { it * sign }
    }

    /** Splits [total] into [n] near-equal parts that sum to [total] (earlier parts get the extra units). */
    fun splitEvenly(total: Long, n: Int): List<Long> = allocate(total, List(n) { 1L })
}
