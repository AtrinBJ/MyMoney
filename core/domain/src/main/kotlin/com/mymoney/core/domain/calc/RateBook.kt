package com.mymoney.core.domain.calc

import com.mymoney.core.domain.model.ExchangeRate
import com.mymoney.core.domain.model.RateSource
import com.mymoney.core.domain.money.Currency
import com.mymoney.core.domain.money.MoneyMath
import java.math.BigDecimal
import java.time.LocalDate

/** A resolved rate and how it was found, so the UI can explain every converted number (FBR-21). */
data class RateLookup(
    val rate: BigDecimal,
    /** Date of the underlying rate record(s). */
    val rateDate: LocalDate,
    val source: RateSource,
    /** True when no rate on/before the date existed and the earliest later rate was used. */
    val approximate: Boolean,
    val inverted: Boolean = false,
    val viaCurrency: String? = null,
)

data class Converted(val minor: Long, val lookup: RateLookup?)

/**
 * In-memory index of exchange rates with the lookup policy of FBR-21:
 * 1. latest direct rate on or before the date, 2. its inverse, 3. triangulation through [pivot];
 * if nothing exists on/before the date, the earliest later rate is used and flagged approximate.
 * A missing rate is reported as `null` — never assumed to be 0 or 1.
 */
class RateBook(
    rates: List<ExchangeRate>,
    private val currencies: Map<String, Currency>,
    private val pivot: String,
) {
    private val byPair: Map<Pair<String, String>, List<ExchangeRate>> = rates
        .groupBy { it.base to it.quote }
        .mapValues { (_, list) ->
            // Sorted by date, then by source priority, so the last record of a day is the preferred one
            // (MANUAL beats TRANSFER_IMPLIED beats IMPORT).
            list.sortedWith(compareBy<ExchangeRate> { it.date }.thenBy { sourcePriority(it.source) })
        }

    fun rate(from: String, to: String, date: LocalDate): RateLookup? {
        if (from == to) return RateLookup(BigDecimal.ONE, date, RateSource.MANUAL, approximate = false)
        val direct = direct(from, to, date)
        if (direct != null && !direct.approximate) return direct
        val triangulated = if (from != pivot && to != pivot) triangulate(from, to, date) else null
        return best(listOfNotNull(direct, triangulated))
    }

    private fun triangulate(from: String, to: String, date: LocalDate): RateLookup? {
        val a = direct(from, pivot, date) ?: return null
        val b = direct(pivot, to, date) ?: return null
        return RateLookup(
            rate = a.rate.multiply(b.rate, MoneyMath.RATE_CONTEXT),
            rateDate = minOf(a.rateDate, b.rateDate),
            source = if (a.source == b.source) a.source else RateSource.MANUAL,
            approximate = a.approximate || b.approximate,
            viaCurrency = pivot,
        )
    }

    /** Exact beats approximate; among exact the most recent; among approximate the earliest. */
    private fun best(candidates: List<RateLookup>): RateLookup? {
        val exact = candidates.filter { !it.approximate }
        if (exact.isNotEmpty()) return exact.maxBy { it.rateDate }
        return candidates.minByOrNull { it.rateDate }
    }

    /** Converts [minor] of [from] into [to] at the rate valid on [date]; `minor = null` when no rate exists. */
    fun convert(minor: Long, from: String, to: String, date: LocalDate): Converted? {
        if (from == to) return Converted(minor, null)
        val lookup = rate(from, to, date) ?: return null
        val fromDigits = currencies[from]?.minorDigits ?: return null
        val toDigits = currencies[to]?.minorDigits ?: return null
        return Converted(MoneyMath.convert(minor, fromDigits, toDigits, lookup.rate), lookup)
    }

    private fun direct(from: String, to: String, date: LocalDate): RateLookup? {
        val forward = find(from to to, date)?.let { (r, approx) -> RateLookup(r.rate, r.date, r.source, approx) }
        val inverse = find(to to from, date)?.let { (r, approx) ->
            RateLookup(MoneyMath.inverse(r.rate), r.date, r.source, approx, inverted = true)
        }
        return best(listOfNotNull(forward, inverse))
    }

    private fun find(pair: Pair<String, String>, date: LocalDate): Pair<ExchangeRate, Boolean>? {
        val list = byPair[pair] ?: return null
        // Binary search for the first record dated after [date].
        var lo = 0
        var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].date.isAfter(date)) hi = mid else lo = mid + 1
        }
        if (lo > 0) return list[lo - 1] to false
        // Nothing on/before: earliest later day, preferred source of that day.
        val firstDay = list.first().date
        return list.last { it.date == firstDay } to true
    }

    private fun sourcePriority(source: RateSource): Int = when (source) {
        RateSource.MANUAL -> 3
        RateSource.TRANSFER_IMPLIED -> 2
        RateSource.IMPORT -> 1
    }
}
