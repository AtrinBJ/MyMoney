package com.mymoney.core.domain.security

/**
 * Rules for the app-lock PIN (docs/SECURITY.md §PIN). The PIN gates the UI; the data itself is protected by
 * the Keystore-wrapped database key.
 */
object PinPolicy {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 8

    /** Wrong attempts allowed before delays start. */
    const val FREE_ATTEMPTS = 5
    private const val FIRST_DELAY_MS = 30_000L
    private const val MAX_DELAY_MS = 60 * 60_000L

    enum class Problem { LENGTH, NOT_DIGITS, TOO_SIMPLE }

    /** Validation of a new PIN: 4–8 ASCII digits, not one repeated digit, not a straight run (1234, 9876). */
    fun validateNew(pin: String): Problem? {
        if (pin.length !in MIN_LENGTH..MAX_LENGTH) return Problem.LENGTH
        if (!pin.all { it in '0'..'9' }) return Problem.NOT_DIGITS
        if (pin.all { it == pin[0] }) return Problem.TOO_SIMPLE
        val steps = pin.zipWithNext { a, b -> b - a }.toSet()
        if (steps == setOf(1) || steps == setOf(-1)) return Problem.TOO_SIMPLE
        return null
    }

    /**
     * Waiting time after [failures] consecutive wrong attempts: none for the first [FREE_ATTEMPTS], then 30 s,
     * doubling per further failure, capped at one hour. Data is never wiped automatically.
     */
    fun lockoutMillis(failures: Int): Long {
        if (failures < FREE_ATTEMPTS) return 0
        val exp = (failures - FREE_ATTEMPTS).coerceAtMost(20)
        return (FIRST_DELAY_MS shl exp).coerceAtMost(MAX_DELAY_MS)
    }
}
