package com.mymoney.core.domain.calc

import com.mymoney.core.domain.model.Account
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.money.MoneyMath
import com.mymoney.core.domain.repository.FlowRow
import com.mymoney.core.domain.rules.CashFlowSection
import com.mymoney.core.domain.rules.EntryClassification

/** Cash-flow lines (FBR-09). Inflows positive, outflows negative, in base currency. */
data class CashFlowStatement(
    val baseCurrency: String,
    val openingBalances: Long,
    val operatingIn: Long,
    val operatingOut: Long,
    val investingIn: Long,
    val investingOut: Long,
    val financingIn: Long,
    val financingOut: Long,
    /** Transfers to/from accounts outside the selected set (non-debt accounts). */
    val transfersOther: Long,
    val adjustments: Long,
    val missing: List<MissingRate>,
) {
    val operatingNet: Long get() = operatingIn + operatingOut
    val investingNet: Long get() = investingIn + investingOut
    val financingNet: Long get() = financingIn + financingOut

    /** Equals the change of the selected accounts' balance over the period (per currency, before conversion). */
    val netChange: Long get() = openingBalances + operatingNet + investingNet + financingNet + transfersOther + adjustments
}

object CashFlowCalculator {
    /**
     * [selected] = accounts whose cash movement is reported (default: all non-debt accounts).
     * Transfers between two selected accounts cancel out and are not shown (FBR-02).
     */
    fun compute(rows: List<FlowRow>, accounts: Map<String, Account>, selected: Set<String>, rates: RateBook, base: String): CashFlowStatement {
        var opening = 0L; var opIn = 0L; var opOut = 0L; var invIn = 0L; var invOut = 0L
        var finIn = 0L; var finOut = 0L; var other = 0L; var adj = 0L
        val missing = mutableMapOf<String, Long>()
        rows.forEach { r ->
            if (r.accountId !in selected) return@forEach
            val account = accounts[r.accountId] ?: return@forEach
            val counterpart = r.counterpartAccountId?.let { accounts[it] }
            if (r.kind == EntryKind.TRANSFER && r.counterpartAccountId in selected) return@forEach
            val section = when {
                r.kind == EntryKind.TRANSFER && counterpart?.type?.isObligation != true -> null // transfer with another own account
                else -> EntryClassification.cashFlow(r.kind, account.type, counterpart?.type)
            }
            val v = rates.convert(r.amountMinor, r.currency, base, r.date)?.minor
            if (v == null) {
                missing.merge(r.currency, r.amountMinor, MoneyMath::add)
                return@forEach
            }
            when (section) {
                null -> other = MoneyMath.add(other, v)
                CashFlowSection.OPENING -> opening = MoneyMath.add(opening, v)
                CashFlowSection.OPERATING -> if (v >= 0) opIn = MoneyMath.add(opIn, v) else opOut = MoneyMath.add(opOut, v)
                CashFlowSection.INVESTING -> if (v >= 0) invIn = MoneyMath.add(invIn, v) else invOut = MoneyMath.add(invOut, v)
                CashFlowSection.FINANCING -> if (v >= 0) finIn = MoneyMath.add(finIn, v) else finOut = MoneyMath.add(finOut, v)
                CashFlowSection.ADJUSTMENT -> adj = MoneyMath.add(adj, v)
                CashFlowSection.INTERNAL -> other = MoneyMath.add(other, v)
                CashFlowSection.NONE -> Unit
            }
        }
        return CashFlowStatement(base, opening, opIn, opOut, invIn, invOut, finIn, finOut, other, adj, missing.map { MissingRate(it.key, it.value) })
    }
}
