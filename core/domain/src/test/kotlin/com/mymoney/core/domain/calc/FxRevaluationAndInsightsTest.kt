package com.mymoney.core.domain.calc

import com.mymoney.core.domain.calendar.DateRange
import com.mymoney.core.domain.model.Budget
import com.mymoney.core.domain.model.BudgetPeriodType
import com.mymoney.core.domain.model.ExchangeRate
import com.mymoney.core.domain.model.Frequency
import com.mymoney.core.domain.model.RateSource
import com.mymoney.core.domain.model.RecurringRule
import com.mymoney.core.domain.model.RuleKind
import com.mymoney.core.domain.money.Currency
import com.mymoney.core.domain.insights.Insight
import com.mymoney.core.domain.insights.InsightEngine
import com.mymoney.core.domain.insights.InsightInputs
import com.mymoney.core.domain.settings.CalendarType
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class FxRevaluationAndInsightsTest {
    private val currencies = listOf(Currency("IRR", 0, "r", "r", "r"), Currency("USD", 2, "$", "$", "$")).associateBy { it.code }
    private val d1 = LocalDate.of(2026, 1, 1)
    private val d2 = LocalDate.of(2026, 6, 30)

    @Test
    fun `holding dollars while the rate rises is a revaluation, not income (FBR-31)`() {
        val rates = RateBook(
            listOf(
                ExchangeRate("a", "USD", "IRR", BigDecimal("1000000"), d1, RateSource.MANUAL),
                ExchangeRate("b", "USD", "IRR", BigDecimal("1200000"), d2, RateSource.MANUAL),
            ),
            currencies, "IRR",
        )
        // $100 held all period, plus $50 deposited when rate was 1,000,000
        val effect = FxRevaluation.effect("USD", "IRR", d1, d2, 10_000, 15_000, listOf(LocalDate.of(2026, 3, 1) to 5_000), rates)
        assertEquals(150 * 200_000L, effect) // 150 dollars × 200,000 rial gain
        assertEquals(0, FxRevaluation.effect("IRR", "IRR", d1, d2, 1, 2, emptyList(), rates))
    }

    private fun statement(income: Long, expense: Long, byCat: Map<String?, Long> = emptyMap()) = IncomeStatement(
        "IRR", income, expense, byCat.mapValues { CategoryTotal(it.key, expenseMinor = it.value) }, emptyList(), false,
    )

    private fun month(y: Int, m: Int): DateRange {
        val s = LocalDate.of(y, m, 1)
        return DateRange(s, s.withDayOfMonth(s.lengthOfMonth()))
    }

    @Test
    fun `insights are evidence based`() {
        val past = (4..8).map { m -> month(2026, m) to statement(1000, 600, mapOf("food" to 200L, "rent" to 400L)) } +
            (month(2026, 9) to statement(1000, 900, mapOf("food" to 500L, "rent" to 400L)))
        val current = month(2026, 10) to statement(500, 900, mapOf("food" to 500L, "rent" to 400L))
        val sub = RecurringRule("s", RuleKind.SUBSCRIPTION, "Video", "a", amountMinor = 30, currency = "IRR", frequency = Frequency.MONTHLY, calendar = CalendarType.GREGORIAN, startDate = d1)
        val budget = BudgetStatus(
            Budget("b", "Food", BudgetPeriodType.MONTHLY, CalendarType.GREGORIAN, d1, amountMinor = 300, currency = "IRR"),
            month(2026, 10), 300, 0, 500, 0, emptyList(),
        )
        val out = InsightEngine.compute(
            InsightInputs(
                today = LocalDate.of(2026, 10, 20), baseCurrency = "IRR", pastMonths = past, currentMonth = current, budgets = listOf(budget),
                subscriptions = listOf(sub to 30L), holdings = emptyList(), lastBackupAt = null, backupReminderDays = 30, hasAnyData = true,
            ),
        )
        val spike = out.filterIsInstance<Insight.CategorySpike>().single()
        assertEquals("food", spike.categoryId)
        assertTrue(spike.increasePercent > 100)
        assertTrue(out.any { it is Insight.BudgetExceeded })
        assertEquals(4, out.filterIsInstance<Insight.Subscriptions>().single().sharePercentOfExpenses) // 30 / avg(600,600,900)
        assertTrue(out.any { it is Insight.BackupOverdue })
        assertTrue(out.first().severity.ordinal >= out.last().severity.ordinal)

        // Too little history: no trend claims.
        val few = InsightEngine.compute(InsightInputs(LocalDate.of(2026, 10, 20), "IRR", past.takeLast(1), current, emptyList(), emptyList(), emptyList(), LocalDate.of(2026, 10, 1), 30, true))
        assertTrue(few.none { it is Insight.CategorySpike || it is Insight.HighSpendingMonth })
    }
}
