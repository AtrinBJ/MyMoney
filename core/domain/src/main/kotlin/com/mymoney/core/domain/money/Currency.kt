package com.mymoney.core.domain.money

/** A currency definition (docs/03-data-model.md, table `currency`). */
data class Currency(
    val code: String,
    val minorDigits: Int,
    val symbol: String,
    val nameFa: String,
    val nameEn: String,
    val isCustom: Boolean = false,
    val isEnabled: Boolean = true,
    val sortOrder: Int = 0,
) {
    init {
        require(CODE_REGEX.matches(code)) { "invalid currency code" }
        require(minorDigits in 0..4) { "minorDigits must be 0..4" }
    }

    companion object {
        const val IRR = "IRR"
        val CODE_REGEX = Regex("[A-Z0-9]{3,8}")
    }
}

/** An amount in minor units of a specific currency. */
data class Money(val minor: Long, val currency: String) {
    operator fun plus(other: Money): Money {
        requireSameCurrency(other)
        return Money(Math.addExact(minor, other.minor), currency)
    }

    operator fun minus(other: Money): Money {
        requireSameCurrency(other)
        return Money(Math.subtractExact(minor, other.minor), currency)
    }

    operator fun unaryMinus(): Money = Money(Math.negateExact(minor), currency)

    val isZero: Boolean get() = minor == 0L
    val isNegative: Boolean get() = minor < 0L
    fun abs(): Money = if (minor < 0) -this else this

    private fun requireSameCurrency(other: Money) {
        require(other.currency == currency) { "currency mismatch: $currency vs ${other.currency}" }
    }

    companion object {
        fun zero(currency: String) = Money(0, currency)
    }
}
