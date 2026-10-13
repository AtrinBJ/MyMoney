package com.mymoney.core.domain.calc

import com.mymoney.core.domain.money.MoneyMath
import java.time.LocalDate

/**
 * FBR-31: how much of a foreign-currency balance's change in base currency over a period came from the
 * exchange rate rather than from money moving. Shown only in the net-worth report, never as spending.
 *
 * effect = end × rate(end) − start × rate(start) − Σ flow × rate(flow date)
 */
object FxRevaluation {
    fun effect(
        currency: String,
        base: String,
        start: LocalDate,
        end: LocalDate,
        startBalanceMinor: Long,
        endBalanceMinor: Long,
        flows: List<Pair<LocalDate, Long>>,
        rates: RateBook,
    ): Long? {
        if (currency == base) return 0
        val endBase = rates.convert(endBalanceMinor, currency, base, end)?.minor ?: return null
        val startBase = rates.convert(startBalanceMinor, currency, base, start)?.minor ?: return null
        var flowsBase = 0L
        flows.forEach { (d, v) -> flowsBase = MoneyMath.add(flowsBase, rates.convert(v, currency, base, d)?.minor ?: return null) }
        return endBase - startBase - flowsBase
    }
}
