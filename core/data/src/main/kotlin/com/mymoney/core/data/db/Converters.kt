package com.mymoney.core.data.db

import androidx.room.TypeConverter
import java.math.BigDecimal

/** Non-money decimals (rates, quantities) are stored as canonical plain strings — never as REAL. */
class Converters {
    @TypeConverter
    fun bigDecimalToString(value: BigDecimal?): String? = value?.stripTrailingZeros()?.toPlainString()

    @TypeConverter
    fun stringToBigDecimal(value: String?): BigDecimal? = value?.let(::BigDecimal)
}
