package com.mymoney.core.domain.calc

import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.money.MoneyMath
import com.mymoney.core.domain.repository.CategoryAmountRow
import com.mymoney.core.domain.rules.EntryClassification
import com.mymoney.core.domain.rules.PnlEffect

/** Income and expense of one category in base currency (expenses net of refunds, FBR-08). */
data class CategoryTotal(
    val categoryId: String?,
    val incomeMinor: Long = 0,
    val expenseMinor: Long = 0,
    val refundMinor: Long = 0,
) {
    /** Positive number: what was actually spent in this category. */
    val netExpenseMinor: Long get() = MoneyMath.subtract(expenseMinor, refundMinor)
}

/**
 * Income statement for a period (FBR-01, FBR-09). Only INCOME, EXPENSE and REFUND participate here;
 * transfers, adjustments, opening balances and asset movements never do. Realized asset gains and
 * write-offs are added by the stage-4 report from their own sources.
 */
data class IncomeStatement(
    val baseCurrency: String,
    val incomeMinor: Long,
    /** Expenses net of refunds. */
    val expenseMinor: Long,
    val byCategory: Map<String?, CategoryTotal>,
    val missing: List<MissingRate>,
    val anyApproximate: Boolean,
) {
    val netMinor: Long get() = MoneyMath.subtract(incomeMinor, expenseMinor)

    /** Savings rate = (income − expenses) / income, or null when there is no income. */
    val savingsRatePercent: Double?
        get() = if (incomeMinor <= 0) null else netMinor.toDouble() * 100.0 / incomeMinor.toDouble()
}

object IncomeStatementCalculator {
    fun compute(rows: List<CategoryAmountRow>, rates: RateBook, baseCurrency: String): IncomeStatement {
        var income = 0L
        var expense = 0L
        var approximate = false
        val missing = mutableMapOf<String, Long>()
        val byCategory = mutableMapOf<String?, CategoryTotal>()

        rows.forEach { row ->
            val effect = EntryClassification.pnl(row.kind)
            if (effect != PnlEffect.INCOME && effect != PnlEffect.EXPENSE && effect != PnlEffect.EXPENSE_REDUCTION) return@forEach
            val converted = rates.convert(row.amountMinor, row.currency, baseCurrency, row.date)
            if (converted == null) {
                missing.merge(row.currency, row.amountMinor, MoneyMath::add)
                return@forEach
            }
            if (converted.lookup?.approximate == true) approximate = true
            val v = converted.minor
            val current = byCategory[row.categoryId] ?: CategoryTotal(row.categoryId)
            byCategory[row.categoryId] = when (row.kind) {
                EntryKind.INCOME -> {
                    income = MoneyMath.add(income, v)
                    current.copy(incomeMinor = MoneyMath.add(current.incomeMinor, v))
                }
                EntryKind.EXPENSE -> {
                    expense = MoneyMath.add(expense, -v)
                    current.copy(expenseMinor = MoneyMath.add(current.expenseMinor, -v))
                }
                else -> { // REFUND
                    expense = MoneyMath.subtract(expense, v)
                    current.copy(refundMinor = MoneyMath.add(current.refundMinor, v))
                }
            }
        }
        return IncomeStatement(
            baseCurrency = baseCurrency,
            incomeMinor = income,
            expenseMinor = expense,
            byCategory = byCategory,
            missing = missing.map { MissingRate(it.key, it.value) },
            anyApproximate = approximate,
        )
    }
}
