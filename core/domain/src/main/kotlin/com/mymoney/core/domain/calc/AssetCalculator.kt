package com.mymoney.core.domain.calc

import com.mymoney.core.domain.model.Asset
import com.mymoney.core.domain.model.AssetTrade
import com.mymoney.core.domain.model.AssetValuation
import com.mymoney.core.domain.model.TradeType
import com.mymoney.core.domain.model.ValuationMode
import com.mymoney.core.domain.money.MoneyMath
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate

data class RealizedGain(val tradeId: String, val date: LocalDate, val gainMinor: Long)

data class AssetHolding(
    val asset: Asset,
    val quantity: BigDecimal,
    /** Remaining cost basis in the asset currency (average-cost method, FBR-30). */
    val costBasisMinor: Long,
    val realized: List<RealizedGain>,
    val valuation: AssetValuation?,
    /** Current value in the asset currency, or null without any valuation. */
    val valueMinor: Long?,
) {
    val unrealizedMinor: Long? get() = valueMinor?.let { MoneyMath.subtract(it, costBasisMinor) }
    val realizedTotalMinor: Long get() = realized.sumOf { it.gainMinor }
    fun isStale(today: LocalDate): Boolean =
        valuation == null || valuation.date.plusDays(asset.staleAfterDays.toLong()).isBefore(today)
}

/** Selling more than is held is a data error, never silently clamped. */
class OversellException(val assetId: String) : IllegalStateException("sell exceeds holding")

object AssetCalculator {
    private val MC = MathContext.DECIMAL128

    /**
     * Replays trades in date order (FBR-30): BUY/OPENING add quantity and cost (gross + fees);
     * SELL removes average cost × quantity and realizes (gross − fees) − that cost; ADJUST changes quantity only.
     */
    fun holding(asset: Asset, trades: List<AssetTrade>, valuations: List<AssetValuation>, currencyDigits: Int, asOf: LocalDate? = null): AssetHolding {
        var qty = BigDecimal.ZERO
        var cost = 0L
        val realized = mutableListOf<RealizedGain>()
        trades.filter { asOf == null || !it.date.isAfter(asOf) }
            .sortedWith(compareBy<AssetTrade> { it.date }.thenBy { it.createdAt })
            .forEach { t ->
                when (t.type) {
                    TradeType.OPENING, TradeType.BUY -> {
                        qty = qty.add(t.quantity)
                        cost = MoneyMath.add(cost, MoneyMath.add(t.grossMinor, t.feesMinor))
                    }
                    TradeType.SELL -> {
                        if (t.quantity > qty) throw OversellException(asset.id)
                        val costOfSold = if (qty.signum() == 0) 0L else BigDecimal(cost).multiply(t.quantity, MC).divide(qty, MC)
                            .setScale(0, RoundingMode.HALF_EVEN).longValueExact()
                        realized += RealizedGain(t.id, t.date, MoneyMath.subtract(MoneyMath.subtract(t.grossMinor, t.feesMinor), costOfSold))
                        qty = qty.subtract(t.quantity)
                        cost = if (qty.signum() == 0) 0L else MoneyMath.subtract(cost, costOfSold)
                    }
                    TradeType.ADJUST -> qty = qty.add(t.quantity)
                }
            }
        val latest = valuations.filter { asOf == null || !it.date.isAfter(asOf) }.maxWithOrNull(compareBy<AssetValuation> { it.date }.thenBy { it.createdAt })
        val value = latest?.let { valueOf(asset, qty, it, currencyDigits) }
        return AssetHolding(asset, qty.stripTrailingZeros(), cost, realized, latest, value)
    }

    fun valueOf(asset: Asset, quantity: BigDecimal, v: AssetValuation, currencyDigits: Int): Long? = when {
        asset.valuationMode == ValuationMode.TOTAL || v.totalValueMinor != null -> v.totalValueMinor
        v.unitPrice != null -> MoneyMath.toMinor(quantity.multiply(v.unitPrice, MC), currencyDigits)
        else -> null
    }
}
