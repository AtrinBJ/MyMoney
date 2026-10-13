package com.mymoney.core.domain.common

/**
 * Result of a domain operation. Expected failures are values ([DomainError]), never exceptions,
 * so the UI can show a precise, translated message.
 */
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val error: DomainError) : Outcome<Nothing>
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

fun <T> Outcome<T>.getOrNull(): T? = (this as? Outcome.Success)?.value

fun <T> T.success(): Outcome<T> = Outcome.Success(this)

fun DomainError.failure(): Outcome<Nothing> = Outcome.Failure(this)
