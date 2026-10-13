package com.mymoney.core.domain.model

import java.math.BigDecimal
import java.time.LocalDate

/** Where a rate came from. Shown next to every converted figure (FBR-21). */
enum class RateSource { MANUAL, TRANSFER_IMPLIED, IMPORT }

/** 1 [base] = [rate] [quote], valid from [date] until a newer rate exists. */
data class ExchangeRate(
    val id: String,
    val base: String,
    val quote: String,
    val rate: BigDecimal,
    val date: LocalDate,
    val source: RateSource,
    val note: String? = null,
    val createdAt: Long = 0,
)
