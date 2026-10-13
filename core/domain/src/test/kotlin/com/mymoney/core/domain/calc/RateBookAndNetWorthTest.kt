package com.mymoney.core.domain.calc

import com.mymoney.core.domain.model.Account
import com.mymoney.core.domain.model.AccountType
import com.mymoney.core.domain.model.AccountWithBalance
import com.mymoney.core.domain.model.ExchangeRate
import com.mymoney.core.domain.model.RateSource
import com.mymoney.core.domain.money.Currency
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class RateBookAndNetWorthTest {
    private val currencies = listOf(
        Currency("IRR", 0, "ریال", "ریال", "Rial"),
        Currency("USD", 2, "$", "دلار", "Dollar"),
        Currency("EUR", 2, "€", "یورو", "Euro"),
        Currency("GBP", 2, "£", "پوند", "Pound"),
    ).associateBy { it.code }

    private fun rate(b: String, q: String, r: String, d: LocalDate, s: RateSource = RateSource.MANUAL) =
        ExchangeRate("$b$q$d$s", b, q, BigDecimal(r), d, s)

    private val d1 = LocalDate.of(2026, 9, 1)
    private val d2 = LocalDate.of(2026, 10, 1)

    @Test
    fun `latest rate on or before the date is used (FBR-21)`() {
        val book = RateBook(listOf(rate("USD", "IRR", "1000000", d1), rate("USD", "IRR", "1100000", d2)), currencies, "IRR")
        assertEquals(BigDecimal("1000000"), book.rate("USD", "IRR", d2.minusDays(1))!!.rate)
        assertEquals(BigDecimal("1100000"), book.rate("USD", "IRR", d2)!!.rate)
        assertEquals(BigDecimal("1100000"), book.rate("USD", "IRR", d2.plusDays(30))!!.rate)
    }

    @Test
    fun `before the first rate the earliest later rate is used and flagged approximate`() {
        val book = RateBook(listOf(rate("USD", "IRR", "1000000", d1)), currencies, "IRR")
        val r = book.rate("USD", "IRR", d1.minusDays(100))!!
        assertTrue(r.approximate)
        assertFalse(book.rate("USD", "IRR", d1)!!.approximate)
    }

    @Test
    fun `inverse and triangulated rates`() {
        val book = RateBook(listOf(rate("USD", "IRR", "1000000", d1), rate("EUR", "IRR", "1100000", d1)), currencies, "IRR")
        val inv = book.rate("IRR", "USD", d2)!!
        assertTrue(inv.inverted)
        assertEquals(0, BigDecimal("0.000001").compareTo(inv.rate))
        val cross = book.rate("EUR", "USD", d2)!!
        assertEquals("IRR", cross.viaCurrency)
        assertEquals(0, BigDecimal("1.1").compareTo(cross.rate))
        assertEquals(110, book.convert(100, "EUR", "USD", d2)!!.minor)
    }

    @Test
    fun `missing rate is null, never zero or one`() {
        val book = RateBook(emptyList(), currencies, "IRR")
        assertNull(book.rate("GBP", "IRR", d1))
        assertNull(book.convert(100, "GBP", "IRR", d1))
        assertEquals(100, book.convert(100, "IRR", "IRR", d1)!!.minor)
    }

    @Test
    fun `manual rate wins over implied rate on the same day`() {
        val book = RateBook(
            listOf(rate("USD", "IRR", "999999", d1, RateSource.TRANSFER_IMPLIED), rate("USD", "IRR", "1000000", d1, RateSource.MANUAL)),
            currencies, "IRR",
        )
        assertEquals(RateSource.MANUAL, book.rate("USD", "IRR", d1)!!.source)
    }

    @Test
    fun `net worth counts each account once and reports missing rates (FBR-10)`() {
        fun acc(id: String, type: AccountType, cur: String, bal: Long, liquid: Boolean = type.defaultLiquid) =
            AccountWithBalance(Account(id, id, type, cur, startDate = d1, includeInLiquid = liquid), bal, 0)
        val accounts = listOf(
            acc("bank", AccountType.BANK, "IRR", 50_000_000),
            acc("card", AccountType.CREDIT_CARD, "IRR", -8_000_000),
            acc("loan", AccountType.LIABILITY, "IRR", -20_000_000),
            acc("lent", AccountType.RECEIVABLE, "IRR", 5_000_000),
            acc("usd", AccountType.CASH, "USD", 10_000), // $100
            acc("gbp", AccountType.CASH, "GBP", 5_000), // no rate
        )
        val book = RateBook(listOf(rate("USD", "IRR", "1000000", d1)), currencies, "IRR")
        val nw = NetWorthCalculator.compute(accounts, book, "IRR", d2, assetValuesBase = listOf(30_000_000))
        assertEquals(50_000_000 + 5_000_000 + 100_000_000 + 30_000_000, nw.totalAssetsMinor)
        assertEquals(8_000_000 + 20_000_000, nw.totalLiabilitiesMinor)
        assertEquals(nw.totalAssetsMinor - nw.totalLiabilitiesMinor, nw.netWorthMinor)
        assertEquals(50_000_000 + 100_000_000, nw.liquidMinor) // bank + usd cash; not card, not obligations
        assertEquals(listOf(MissingRate("GBP", 5_000)), nw.missing)
        assertNotNull(nw.accounts.first { it.account.account.id == "usd" }.lookup)
    }
}
