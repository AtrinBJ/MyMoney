package com.mymoney.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "currency")
data class CurrencyEntity(
    @PrimaryKey val code: String,
    val minorDigits: Int,
    val symbol: String,
    val nameFa: String,
    val nameEn: String,
    @ColumnInfo(defaultValue = "0") val isCustom: Boolean = false,
    @ColumnInfo(defaultValue = "1") val isEnabled: Boolean = true,
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
)
