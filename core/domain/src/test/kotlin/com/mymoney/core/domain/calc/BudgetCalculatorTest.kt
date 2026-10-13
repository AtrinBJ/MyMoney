package com.mymoney.core.domain.calc

import com.mymoney.core.domain.model.Budget
import com.mymoney.core.domain.model.BudgetPeriodType
import com.mymoney.core.domain.model.BudgetScope
import com.mymoney.core.domain.model.Category
import com.mymoney.core.domain.model.CategoryType
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.model.EntryStatus
import com.mymoney.core.domain.model.RolloverMode
import com.mymoney.core.domain.money.Currency
import com.mymoney.core.domain.repository.CategoryAmountRow
import com.mymoney.core.domain.settings.CalendarType
import com.mymoney.core.domain.settings.WeekStart
import java.time.LocalDate
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class BudgetCalculatorTest {
    private val cats = listOf(
        Category("food", CategoryType.EXPENSE),
        Category("restaurant", CategoryType.EXPENSE, parentId = "food"),
        Category("rent", CategoryType.EXPENSE),
        Category("salary", CategoryType.INCOME),
    )
    private val currencies = mapOf("IRR" to Currency("IRR", 0, "ریال", "ریال", "Rial"), "USD" to Currency("USD", 2, "$", "دلار", "Dollar"))
    private val calc = BudgetCalculator(cats, RateBook(emptyList(), currencies, "IRR"), WeekStart.SATURDAY)

    private fun row(date: String, cat: String?, kind: EntryKind, amount: Long, status: EntryStatus = EntryStatus.COMPLETED, cur: String = "IRR") =
        BudgetRow(CategoryAmountRow(cur, LocalDate.parse(date), cat, kind, amount), status)

    private fun budget(scope: BudgetScope = BudgetScope.CATEGORIES, ids: Set<String> = setOf("food"), rollover: RolloverMode = RolloverMode.NONE, pending: Boolean = false) =
        Budget("b", "Food", BudgetPeriodType.MONTHLY, CalendarType.GREGORIAN, LocalDate.of(2026, 1, 1), amountMinor = 1000, currency = "IRR",
            scope = scope, categoryIds = ids, rollover = rollover, includePending = pending)

    private val rows = listOf(
        row("2026-03-02", "food", EntryKind.EXPENSE, -300),
        row("2026-03-03", "restaurant", EntryKind.EXPENSE, -250), // subcategory counts
        row("2026-03-04", "food", EntryKind.REFUND, 50), // refund reduces
        row("2026-03-05", "rent", EntryKind.EXPENSE, -9000), // other category
        row("2026-03-06", "salary", EntryKind.INCOME, 5000), // income ignored
        row("2026-03-07", null, EntryKind.TRANSFER, -700), // transfers never count
        row("2026-03-08", "food", EntryKind.EXPENSE, -100, EntryStatus.PENDING),
        row("2026-02-10", "food", EntryKind.EXPENSE, -600),
        row("2026-01-10", "food", EntryKind.EXPENSE, -1200),
    )

    @Test
    fun `spent = expenses minus refunds in the budget's categories (FBR-19)`() {
        val s = calc.status(budget(), LocalDate.of(2026, 3, 15), rows)
        assertEquals(500, s.spentMinor)
        assertEquals(100, s.pendingMinor)
        assertEquals(500, s.usedMinor)
        assertEquals(500, s.remainingMinor)
        assertEquals(50, s.thresholdReached)
        assertEquals(600, calc.status(budget(pending = true), LocalDate.of(2026, 3, 15), rows).usedMinor)
    }

    @Test
    fun `total scope counts all expense categories`() {
        assertEquals(9500, calc.status(budget(scope = BudgetScope.TOTAL), LocalDate.of(2026, 3, 15), rows).spentMinor)
    }

    @Test
    fun `rollover carries unused or all (FBR-20)`() {
        // Jan: 1000 - 1200 = -200; Feb: 1000 - 600 = 400
        val unused = calc.status(budget(rollover = RolloverMode.CARRY_UNUSED), LocalDate.of(2026, 3, 15), rows)
        assertEquals(400, unused.rolloverMinor) // Jan carries 0 (negative dropped), Feb: 1000-600=400
        val all = calc.status(budget(rollover = RolloverMode.CARRY_ALL), LocalDate.of(2026, 3, 15), rows)
        assertEquals(200, all.rolloverMinor) // Jan -200 → Feb limit 800 - 600 = 200
        assertEquals(1200, all.limitMinor)
    }

    @Test
    fun `override changes only that period`() {
        val b = budget().copy(overrides = mapOf(LocalDate.of(2026, 3, 1) to 400))
        val s = calc.status(b, LocalDate.of(2026, 3, 15), rows)
        assertEquals(400, s.limitMinor)
        assertEquals(100, s.thresholdReached)
        assertEquals(1000, calc.status(b, LocalDate.of(2026, 2, 15), rows).limitMinor)
    }

    @Test
    fun `foreign currency without a rate is reported as missing, not counted as zero`() {
        val s = calc.status(budget(), LocalDate.of(2026, 3, 15), rows + row("2026-03-09", "food", EntryKind.EXPENSE, -999, cur = "USD"))
        assertEquals(500, s.spentMinor)
        assertEquals(listOf(MissingRate("USD", -999)), s.missing)
    }

    @Test
    fun `jalali monthly budget uses jalali month boundaries`() {
        val b = budget().copy(calendar = CalendarType.JALALI)
        val p = calc.periodOf(b, LocalDate.of(2026, 3, 15)) // Esfand 1404
        assertEquals(LocalDate.of(2026, 2, 20), p.start)
        assertEquals(LocalDate.of(2026, 3, 20), p.endInclusive)
    }
}
