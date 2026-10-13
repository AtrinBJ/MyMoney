package com.mymoney.core.domain.model

import java.time.LocalDate

enum class AccountType {
    CASH, BANK, SAVINGS, CREDIT_CARD, INVESTMENT, DIGITAL_WALLET, OTHER,

    /** System account behind a debt the user owes (docs/08 ADR-002). */
    LIABILITY,

    /** System account behind money owed to the user. */
    RECEIVABLE;

    /** Debts and receivables are shown in their own sections, not in the accounts list. */
    val isObligation: Boolean get() = this == LIABILITY || this == RECEIVABLE

    /** Default for "counts as spendable cash" (FBR-18). */
    val defaultLiquid: Boolean get() = this == CASH || this == BANK || this == DIGITAL_WALLET

    companion object {
        /** Types the user can pick in the account form. */
        val userSelectable = listOf(CASH, BANK, SAVINGS, CREDIT_CARD, INVESTMENT, DIGITAL_WALLET, OTHER)
    }
}

data class Account(
    val id: String,
    val name: String,
    val type: AccountType,
    val currency: String,
    val institution: String? = null,
    val startDate: LocalDate,
    val note: String? = null,
    val colorKey: String? = null,
    val iconKey: String? = null,
    val isArchived: Boolean = false,
    val includeInNetWorth: Boolean = true,
    val includeInLiquid: Boolean = type.defaultLiquid,
    val creditLimitMinor: Long? = null,
    val sortOrder: Int = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/** Account with its derived balances (FBR-17). Balances are never stored. */
data class AccountWithBalance(
    val account: Account,
    /** COMPLETED entries only. */
    val balanceMinor: Long,
    /** Sum of PENDING entries. */
    val pendingMinor: Long,
) {
    val balanceAfterPendingMinor: Long get() = Math.addExact(balanceMinor, pendingMinor)
}
