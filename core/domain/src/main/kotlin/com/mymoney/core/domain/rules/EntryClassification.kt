package com.mymoney.core.domain.rules

import com.mymoney.core.domain.model.AccountType
import com.mymoney.core.domain.model.EntryKind

/** How a kind appears in the income statement (P&L). */
enum class PnlEffect {
    NONE,
    INCOME,
    EXPENSE,

    /** Reduces the expense of the refunded category (FBR-08). */
    EXPENSE_REDUCTION,

    /** Only the realized gain/loss counts, computed from asset trades (FBR-07). */
    REALIZED_GAIN,

    /** Non-operating gain/loss (FBR-32). */
    OTHER_GAIN_LOSS,
}

/** Cash-flow statement section (FBR-09). */
enum class CashFlowSection { OPENING, OPERATING, INVESTING, FINANCING, INTERNAL, ADJUSTMENT, NONE }

/**
 * The single source of truth for the economic meaning of each [EntryKind] (docs/04 table A).
 * The `when` expressions are exhaustive: adding a kind does not compile until it is classified.
 */
object EntryClassification {

    fun pnl(kind: EntryKind): PnlEffect = when (kind) {
        EntryKind.OPENING_BALANCE -> PnlEffect.NONE
        EntryKind.INCOME -> PnlEffect.INCOME
        EntryKind.EXPENSE -> PnlEffect.EXPENSE
        EntryKind.REFUND -> PnlEffect.EXPENSE_REDUCTION
        EntryKind.TRANSFER -> PnlEffect.NONE // FBR-02, FBR-04, FBR-05
        EntryKind.ADJUSTMENT -> PnlEffect.NONE // FBR-15
        EntryKind.ASSET_BUY -> PnlEffect.NONE // FBR-06
        EntryKind.ASSET_SELL -> PnlEffect.REALIZED_GAIN // FBR-07
        EntryKind.WRITE_OFF -> PnlEffect.OTHER_GAIN_LOSS // FBR-32
    }

    /** Whether the kind counts toward consumption budgets (FBR-19). */
    fun affectsBudget(kind: EntryKind): Boolean = when (kind) {
        EntryKind.EXPENSE, EntryKind.REFUND -> true
        EntryKind.OPENING_BALANCE, EntryKind.INCOME, EntryKind.TRANSFER, EntryKind.ADJUSTMENT,
        EntryKind.ASSET_BUY, EntryKind.ASSET_SELL, EntryKind.WRITE_OFF -> false
    }

    /** Whether the kind requires a category of the given type. */
    fun requiresCategory(kind: EntryKind): Boolean = when (kind) {
        EntryKind.INCOME, EntryKind.EXPENSE, EntryKind.REFUND -> true
        EntryKind.OPENING_BALANCE, EntryKind.TRANSFER, EntryKind.ADJUSTMENT,
        EntryKind.ASSET_BUY, EntryKind.ASSET_SELL, EntryKind.WRITE_OFF -> false
    }

    /**
     * Cash-flow section of a leg. A TRANSFER is internal when the counterpart is another funds account and
     * financing when the counterpart is a debt/receivable account (FBR-09). [counterpartType] is null when unknown.
     */
    fun cashFlow(kind: EntryKind, accountType: AccountType, counterpartType: AccountType?): CashFlowSection =
        when (kind) {
            EntryKind.OPENING_BALANCE -> CashFlowSection.OPENING
            EntryKind.INCOME, EntryKind.EXPENSE, EntryKind.REFUND -> CashFlowSection.OPERATING
            EntryKind.ASSET_BUY, EntryKind.ASSET_SELL -> CashFlowSection.INVESTING
            EntryKind.ADJUSTMENT -> CashFlowSection.ADJUSTMENT
            EntryKind.WRITE_OFF -> CashFlowSection.NONE
            EntryKind.TRANSFER -> when {
                accountType.isObligation || counterpartType?.isObligation == true -> CashFlowSection.FINANCING
                else -> CashFlowSection.INTERNAL
            }
        }

    /** Allowed sign of the amount for each kind (docs/03 §3.6). */
    enum class Sign { POSITIVE, NEGATIVE, NON_ZERO }

    fun sign(kind: EntryKind): Sign = when (kind) {
        EntryKind.INCOME, EntryKind.REFUND, EntryKind.ASSET_SELL -> Sign.POSITIVE
        EntryKind.EXPENSE, EntryKind.ASSET_BUY -> Sign.NEGATIVE
        EntryKind.OPENING_BALANCE, EntryKind.TRANSFER, EntryKind.ADJUSTMENT, EntryKind.WRITE_OFF -> Sign.NON_ZERO
    }
}
