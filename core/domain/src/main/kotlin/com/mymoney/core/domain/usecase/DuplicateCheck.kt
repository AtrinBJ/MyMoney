package com.mymoney.core.domain.usecase

import com.mymoney.core.domain.model.Entry
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.money.Digits
import com.mymoney.core.domain.repository.LedgerRepository
import java.time.LocalDate
import javax.inject.Inject

/**
 * FBR-36: same account, kind and amount within ±1 day, and the same normalized payee (or both blank).
 * Only warns; never deletes anything.
 */
class FindPossibleDuplicatesUseCase @Inject constructor(
    private val ledger: LedgerRepository,
) {
    suspend operator fun invoke(
        accountId: String,
        kind: EntryKind,
        signedAmountMinor: Long,
        date: LocalDate,
        payee: String?,
        excludingGroupId: String?,
    ): List<Entry> {
        if (kind == EntryKind.TRANSFER || kind == EntryKind.OPENING_BALANCE || kind == EntryKind.ADJUSTMENT) return emptyList()
        val key = normalizePayee(payee)
        return ledger.findSimilar(accountId, kind, signedAmountMinor, date, excludingGroupId)
            .filter { normalizePayee(it.payee) == key }
    }

    companion object {
        /** Lowercase, Persian/Arabic letter variants unified, digits normalized, whitespace and punctuation removed. */
        fun normalizePayee(payee: String?): String =
            Digits.normalize(payee.orEmpty())
                .lowercase()
                .replace('ي', 'ی')
                .replace('ك', 'ک')
                .replace("‌", "")
                .filter { it.isLetterOrDigit() }
    }
}
