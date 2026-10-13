package com.mymoney.core.domain.usecase

import com.mymoney.core.domain.model.CostType
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.model.EntryStatus
import java.time.LocalDate

/**
 * What the user entered in a form. Amounts are positive; the builder applies the signs of docs/03 §3.6.
 * [groupId] is created when the form opens and is the idempotency key of the operation (FBR-13).
 */
sealed interface OperationDraft {
    val groupId: String
    val date: LocalDate
    val timeMinutes: Int?
    val status: EntryStatus
    val note: String?
    val tagIds: Set<String>
}

data class SplitDraft(val categoryId: String, val amountMinor: Long, val note: String? = null)

/** Income or expense on one account, optionally split across categories. */
data class IncomeExpenseDraft(
    override val groupId: String,
    val kind: EntryKind,
    val accountId: String,
    val amountMinor: Long,
    override val date: LocalDate,
    val categoryId: String?,
    val splits: List<SplitDraft> = emptyList(),
    override val timeMinutes: Int? = null,
    override val status: EntryStatus = EntryStatus.COMPLETED,
    val payee: String? = null,
    override val note: String? = null,
    val costType: CostType? = null,
    val essential: Boolean? = null,
    override val tagIds: Set<String> = emptySet(),
    val recurringRuleId: String? = null,
    val occurrenceDate: LocalDate? = null,
) : OperationDraft {
    init {
        require(kind == EntryKind.INCOME || kind == EntryKind.EXPENSE) { "kind must be INCOME or EXPENSE" }
    }
}

/**
 * Transfer between two own accounts. For different currencies both actual amounts are given (FBR-03).
 * [feeMinor] is charged as a separate expense on [feeAccountId] (default: the source account).
 */
data class TransferDraft(
    override val groupId: String,
    val fromAccountId: String,
    val toAccountId: String,
    val amountOutMinor: Long,
    val amountInMinor: Long,
    override val date: LocalDate,
    val feeMinor: Long = 0,
    val feeAccountId: String? = null,
    override val timeMinutes: Int? = null,
    override val status: EntryStatus = EntryStatus.COMPLETED,
    override val note: String? = null,
    override val tagIds: Set<String> = emptySet(),
    /** FEES for transfer fees; INTEREST for the interest part of a loan payment (FBR-04). */
    val extraLeg: ExtraLeg = ExtraLeg.FEES,
    val recurringRuleId: String? = null,
    val occurrenceDate: LocalDate? = null,
) : OperationDraft

enum class ExtraLeg { FEES, INTEREST }

/** Money returned for an expense. Linked refunds inherit the expense's category or split (FBR-08). */
data class RefundDraft(
    override val groupId: String,
    val accountId: String,
    val amountMinor: Long,
    override val date: LocalDate,
    val originalEntryId: String?,
    val categoryId: String?,
    override val timeMinutes: Int? = null,
    override val status: EntryStatus = EntryStatus.COMPLETED,
    val payee: String? = null,
    override val note: String? = null,
    override val tagIds: Set<String> = emptySet(),
) : OperationDraft

/** Brings the computed balance to the real balance; [reason] is mandatory (FBR-15). */
data class AdjustmentDraft(
    override val groupId: String,
    val accountId: String,
    val actualBalanceMinor: Long,
    override val date: LocalDate,
    val reason: String,
    override val note: String? = null,
) : OperationDraft {
    override val timeMinutes: Int? get() = null
    override val status: EntryStatus get() = EntryStatus.COMPLETED
    override val tagIds: Set<String> get() = emptySet()
}
