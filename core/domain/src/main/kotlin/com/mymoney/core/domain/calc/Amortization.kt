package com.mymoney.core.domain.calc

import com.mymoney.core.domain.model.InterestMethod
import com.mymoney.core.domain.money.MoneyMath
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

data class Installment(
    val number: Int,
    val paymentMinor: Long,
    val principalMinor: Long,
    val interestMinor: Long,
    val balanceAfterMinor: Long,
)

data class LoanSchedule(val installments: List<Installment>) {
    val totalPaymentMinor: Long get() = installments.sumOf { it.paymentMinor }
    val totalInterestMinor: Long get() = installments.sumOf { it.interestMinor }
}

/**
 * Loan maths (FBR-34). Intermediate values use DECIMAL128; each installment is rounded once (HALF_EVEN)
 * and the last installment absorbs the rounding difference so principal parts sum exactly to the loan.
 */
object Amortization {
    private val MC = MathContext.DECIMAL128

    /** Monthly rate as a fraction, e.g. 23% per year → 0.0191666… */
    fun monthlyRate(annualPercent: BigDecimal): BigDecimal =
        annualPercent.divide(BigDecimal(1200), MC)

    fun annuity(principalMinor: Long, annualPercent: BigDecimal, months: Int): LoanSchedule {
        require(principalMinor > 0 && months > 0 && annualPercent.signum() >= 0)
        val r = monthlyRate(annualPercent)
        val payment: BigDecimal = if (r.signum() == 0) {
            BigDecimal(principalMinor).divide(BigDecimal(months), MC)
        } else {
            val factor = BigDecimal.ONE.add(r).pow(months, MC)
            BigDecimal(principalMinor).multiply(r, MC).multiply(factor, MC).divide(factor.subtract(BigDecimal.ONE), MC)
        }
        val rounded = payment.setScale(0, RoundingMode.HALF_EVEN).longValueExact()
        val out = mutableListOf<Installment>()
        var balance = principalMinor
        for (k in 1..months) {
            val interest = BigDecimal(balance).multiply(r, MC).setScale(0, RoundingMode.HALF_EVEN).longValueExact()
            val principal = if (k == months) balance else (rounded - interest).coerceIn(0, balance)
            balance -= principal
            out += Installment(k, principal + interest, principal, interest, balance)
        }
        return LoanSchedule(out)
    }

    /** Flat interest over the term, split evenly; parts allocated so totals are exact. */
    fun simple(principalMinor: Long, annualPercent: BigDecimal, months: Int): LoanSchedule {
        require(principalMinor > 0 && months > 0 && annualPercent.signum() >= 0)
        val totalInterest = BigDecimal(principalMinor).multiply(annualPercent, MC)
            .multiply(BigDecimal(months), MC).divide(BigDecimal(1200), MC)
            .setScale(0, RoundingMode.HALF_EVEN).longValueExact()
        val principalParts = MoneyMath.splitEvenly(principalMinor, months)
        val interestParts = MoneyMath.splitEvenly(totalInterest, months)
        var balance = principalMinor
        return LoanSchedule(
            (0 until months).map { i ->
                balance -= principalParts[i]
                Installment(i + 1, principalParts[i] + interestParts[i], principalParts[i], interestParts[i], balance)
            },
        )
    }

    fun schedule(method: InterestMethod, principalMinor: Long, annualPercent: BigDecimal?, months: Int): LoanSchedule = when (method) {
        InterestMethod.NONE -> simple(principalMinor, BigDecimal.ZERO, months)
        InterestMethod.SIMPLE -> simple(principalMinor, annualPercent ?: BigDecimal.ZERO, months)
        InterestMethod.ANNUITY -> annuity(principalMinor, annualPercent ?: BigDecimal.ZERO, months)
    }

    /**
     * Suggested split of a payment against the current outstanding balance: interest for one month on the
     * balance (annuity) — the user can always edit it. Interest never exceeds the payment.
     */
    fun suggestSplit(paymentMinor: Long, outstandingMinor: Long, annualPercent: BigDecimal?, method: InterestMethod): Pair<Long, Long> {
        if (method == InterestMethod.NONE || annualPercent == null || annualPercent.signum() == 0) {
            return paymentMinor to 0L
        }
        val interest = BigDecimal(outstandingMinor.coerceAtLeast(0)).multiply(monthlyRate(annualPercent), MC)
            .setScale(0, RoundingMode.HALF_EVEN).longValueExact().coerceIn(0, paymentMinor)
        return (paymentMinor - interest) to interest
    }
}
