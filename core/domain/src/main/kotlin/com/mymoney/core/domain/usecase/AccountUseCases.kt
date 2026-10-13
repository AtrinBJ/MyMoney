package com.mymoney.core.domain.usecase

import com.mymoney.core.domain.common.DomainError
import com.mymoney.core.domain.common.IdGenerator
import com.mymoney.core.domain.common.Outcome
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.common.failure
import com.mymoney.core.domain.model.Account
import com.mymoney.core.domain.money.MoneyMath
import com.mymoney.core.domain.repository.AccountRepository
import com.mymoney.core.domain.repository.CurrencyRepository
import com.mymoney.core.domain.rules.LedgerValidator
import javax.inject.Inject

/** Creates or edits an account together with its opening balance (FBR-33). */
class SaveAccountUseCase @Inject constructor(
    private val accounts: AccountRepository,
    private val currencies: CurrencyRepository,
    private val ids: IdGenerator,
    private val clock: TimeProvider,
) {
    suspend operator fun invoke(account: Account, openingBalanceMinor: Long, isNew: Boolean): Outcome<String> {
        val name = account.name.trim()
        if (name.isEmpty() || name.length > 60) return DomainError.InvalidName("name").failure()
        if (currencies.get(account.currency) == null) return DomainError.NotFound("currency", account.currency).failure()
        MoneyMath.validateAmount(openingBalanceMinor, allowZero = true)?.let { return it.failure() }
        if (account.startDate.isBefore(LedgerValidator.MIN_DATE) || account.startDate.isAfter(LedgerValidator.MAX_DATE)) {
            return DomainError.InvalidDate("start date").failure()
        }
        account.creditLimitMinor?.let { limit ->
            if (limit < 0) return DomainError.RuleViolation("FBR-24", "credit limit must not be negative").failure()
        }

        val now = clock.nowMillis()
        return if (isNew) {
            val id = account.id.ifBlank { ids.newId() }
            val created = account.copy(id = id, name = name, createdAt = now, updatedAt = now)
            when (val r = accounts.create(created, openingBalanceMinor)) {
                is Outcome.Success -> Outcome.Success(id)
                is Outcome.Failure -> r
            }
        } else {
            val current = accounts.get(account.id) ?: return DomainError.NotFound("account", account.id).failure()
            if (current.currency != account.currency && accounts.entryCount(account.id) > accounts.openingEntryCount(account.id)) {
                // Changing the currency would silently reinterpret every recorded amount.
                return DomainError.InUse("account currency").failure()
            }
            val updated = account.copy(name = name, createdAt = current.createdAt, updatedAt = now)
            when (val r = accounts.update(updated, openingBalanceMinor)) {
                is Outcome.Success -> Outcome.Success(account.id)
                is Outcome.Failure -> r
            }
        }
    }
}

private suspend fun AccountRepository.openingEntryCount(id: String): Int = if (openingBalance(id) != 0L) 1 else 0
