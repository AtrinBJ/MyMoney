package com.mymoney.core.domain.calc

import com.mymoney.core.domain.model.AccountWithBalance
import com.mymoney.core.domain.money.MoneyMath
import java.time.LocalDate

/** An amount that could not be included in a base-currency total because no rate exists (FBR-21). */
data class MissingRate(val currency: String, val amountMinor: Long)

data class AccountValue(
    val account: AccountWithBalance,
    /** Balance in base currency; null when no rate exists. */
    val baseMinor: Long?,
    val lookup: RateLookup?,
)

/**
 * Net worth without double counting (FBR-10): every account balance counts once (debts negative,
 * receivables positive), plus non-cash holdings supplied as [assetValuesBase] (stage 4).
 */
data class NetWorthSummary(
    val baseCurrency: String,
    val totalAssetsMinor: Long,
    val totalLiabilitiesMinor: Long,
    val liquidMinor: Long,
    val accounts: List<AccountValue>,
    val missing: List<MissingRate>,
    val anyApproximate: Boolean,
) {
    val netWorthMinor: Long get() = Math.subtractExact(totalAssetsMinor, totalLiabilitiesMinor)
}

object NetWorthCalculator {
    fun compute(
        accounts: List<AccountWithBalance>,
        rates: RateBook,
        baseCurrency: String,
        today: LocalDate,
        assetValuesBase: List<Long> = emptyList(),
    ): NetWorthSummary {
        var assets = 0L
        var liabilities = 0L
        var liquid = 0L
        var approximate = false
        val missing = mutableMapOf<String, Long>()
        val values = accounts.map { awb ->
            val acc = awb.account
            val converted = rates.convert(awb.balanceMinor, acc.currency, baseCurrency, today)
            if (converted == null) {
                if (acc.includeInNetWorth && awb.balanceMinor != 0L) {
                    missing.merge(acc.currency, awb.balanceMinor, MoneyMath::add)
                }
                return@map AccountValue(awb, null, null)
            }
            if (converted.lookup?.approximate == true) approximate = true
            val base = converted.minor
            if (acc.includeInNetWorth && !acc.isArchived) {
                if (base >= 0) assets = MoneyMath.add(assets, base) else liabilities = MoneyMath.add(liabilities, -base)
            }
            if (acc.includeInLiquid && !acc.isArchived && !acc.type.isObligation) {
                liquid = MoneyMath.add(liquid, base)
            }
            AccountValue(awb, base, converted.lookup)
        }
        assetValuesBase.forEach { assets = MoneyMath.add(assets, it) }
        return NetWorthSummary(
            baseCurrency = baseCurrency,
            totalAssetsMinor = assets,
            totalLiabilitiesMinor = liabilities,
            liquidMinor = liquid,
            accounts = values,
            missing = missing.map { MissingRate(it.key, it.value) },
            anyApproximate = approximate,
        )
    }
}
