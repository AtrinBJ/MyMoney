package com.mymoney.core.domain.common

/**
 * Expected, user-explainable failures. Each case maps to a translated message in the UI layer.
 * New cases must be added here (not thrown as exceptions) so `when` over them stays exhaustive.
 */
sealed interface DomainError {
    /** Amount is zero, has the wrong sign for its kind, or exceeds the 10^15 minor-unit cap (FBR-38). */
    data class InvalidAmount(val reason: AmountProblem) : DomainError

    /** Arithmetic would overflow a Long (FBR-14). */
    data object Overflow : DomainError

    data class InvalidDate(val reason: String) : DomainError

    data class Required(val field: String) : DomainError

    data class InvalidName(val field: String) : DomainError

    data class NotFound(val entity: String, val id: String) : DomainError

    /** A rule from docs/04-financial-business-rules.md was violated. */
    data class RuleViolation(val ruleId: String, val detail: String) : DomainError

    data class MissingExchangeRate(val from: String, val to: String) : DomainError

    data class InvalidRate(val reason: String) : DomainError

    /** The same operation (groupId) was already saved — not an error for the user (FBR-13). */
    data object AlreadySaved : DomainError

    data class InUse(val entity: String) : DomainError
}

enum class AmountProblem { ZERO, NEGATIVE_NOT_ALLOWED, POSITIVE_NOT_ALLOWED, TOO_LARGE, UNPARSABLE, TOO_MANY_DECIMALS }
