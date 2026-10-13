package com.mymoney.app.ui.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.mymoney.app.R
import com.mymoney.core.domain.common.AmountProblem
import com.mymoney.core.domain.common.DomainError
import com.mymoney.core.domain.model.AccountType
import com.mymoney.core.domain.model.Category
import com.mymoney.core.domain.model.EntryKind

/** Translated name of a built-in category, or null for user categories. */
@StringRes
fun systemCategoryName(systemKey: String?): Int? = when (systemKey) {
    "expense.housing" -> R.string.category_expense_housing
    "expense.food" -> R.string.category_expense_food
    "expense.transport" -> R.string.category_expense_transport
    "expense.utilities" -> R.string.category_expense_utilities
    "expense.internet_phone" -> R.string.category_expense_internet_phone
    "expense.shopping" -> R.string.category_expense_shopping
    "expense.clothing" -> R.string.category_expense_clothing
    "expense.health" -> R.string.category_expense_health
    "expense.leisure" -> R.string.category_expense_leisure
    "expense.education" -> R.string.category_expense_education
    "expense.travel" -> R.string.category_expense_travel
    "expense.subscriptions" -> R.string.category_expense_subscriptions
    "expense.car" -> R.string.category_expense_car
    "expense.tax_insurance" -> R.string.category_expense_tax_insurance
    "expense.interest" -> R.string.category_expense_interest
    "expense.gifts" -> R.string.category_expense_gifts
    "expense.work" -> R.string.category_expense_work
    "expense.fees" -> R.string.category_expense_fees
    "expense.other" -> R.string.category_expense_other
    "income.salary" -> R.string.category_income_salary
    "income.business" -> R.string.category_income_business
    "income.freelance" -> R.string.category_income_freelance
    "income.bonus" -> R.string.category_income_bonus
    "income.rental" -> R.string.category_income_rental
    "income.investment" -> R.string.category_income_investment
    "income.bank_interest" -> R.string.category_income_bank_interest
    "income.gift" -> R.string.category_income_gift
    "income.other" -> R.string.category_income_other
    else -> null
}

/** A user-renamed built-in category shows the user's name; otherwise the translation. */
@Composable
fun categoryLabel(systemKey: String?, name: String?): String =
    name?.takeIf { it.isNotBlank() } ?: systemCategoryName(systemKey)?.let { stringResource(it) } ?: stringResource(R.string.category_uncategorized)

@Composable
fun categoryLabel(category: Category?): String = categoryLabel(category?.systemKey, category?.name)

@StringRes
fun accountTypeLabel(type: AccountType): Int = when (type) {
    AccountType.CASH -> R.string.acc_type_cash
    AccountType.BANK -> R.string.acc_type_bank
    AccountType.SAVINGS -> R.string.acc_type_savings
    AccountType.CREDIT_CARD -> R.string.acc_type_credit_card
    AccountType.INVESTMENT -> R.string.acc_type_investment
    AccountType.DIGITAL_WALLET -> R.string.acc_type_digital_wallet
    AccountType.OTHER -> R.string.acc_type_other
    AccountType.LIABILITY -> R.string.acc_type_liability
    AccountType.RECEIVABLE -> R.string.acc_type_receivable
}

@StringRes
fun kindLabel(kind: EntryKind): Int = when (kind) {
    EntryKind.OPENING_BALANCE -> R.string.kind_opening_balance
    EntryKind.INCOME -> R.string.kind_income
    EntryKind.EXPENSE -> R.string.kind_expense
    EntryKind.REFUND -> R.string.kind_refund
    EntryKind.TRANSFER -> R.string.kind_transfer
    EntryKind.ADJUSTMENT -> R.string.kind_adjustment
    EntryKind.ASSET_BUY -> R.string.kind_asset_buy
    EntryKind.ASSET_SELL -> R.string.kind_asset_sell
    EntryKind.WRITE_OFF -> R.string.kind_write_off
}

/** Every expected failure has a human sentence; never a stack trace (docs/02 §2.4). */
@Composable
fun errorMessage(error: DomainError): String = when (error) {
    is DomainError.InvalidAmount -> stringResource(
        when (error.reason) {
            AmountProblem.ZERO -> R.string.err_amount_zero
            AmountProblem.NEGATIVE_NOT_ALLOWED -> R.string.err_amount_negative
            AmountProblem.POSITIVE_NOT_ALLOWED -> R.string.err_amount_positive
            AmountProblem.TOO_LARGE -> R.string.err_amount_too_large
            AmountProblem.UNPARSABLE -> R.string.err_amount_unparsable
            AmountProblem.TOO_MANY_DECIMALS -> R.string.err_amount_decimals
        },
    )
    DomainError.Overflow -> stringResource(R.string.err_overflow)
    is DomainError.InvalidDate -> stringResource(R.string.err_date)
    is DomainError.Required -> stringResource(R.string.err_required)
    is DomainError.InvalidName -> stringResource(R.string.err_name)
    is DomainError.NotFound -> stringResource(R.string.err_not_found)
    is DomainError.MissingExchangeRate -> stringResource(R.string.err_missing_rate, error.from, error.to)
    is DomainError.InvalidRate -> stringResource(R.string.err_invalid_rate)
    DomainError.AlreadySaved -> stringResource(R.string.editor_saved)
    is DomainError.InUse -> stringResource(R.string.err_in_use)
    is DomainError.RuleViolation -> stringResource(
        when (error.ruleId) {
            "FBR-16" -> R.string.err_rule_transfer
            "FBR-08" -> R.string.err_rule_refund
            "FBR-14" -> R.string.err_rule_split
            "FBR-01" -> R.string.err_rule_category_type
            "FBR-03" -> R.string.err_rule_fee
            "FBR-24" -> R.string.err_rule_credit_limit
            "DM-3.3" -> R.string.err_rule_category_tree
            else -> R.string.err_rule_generic
        },
    )
}
