package com.mymoney.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import com.mymoney.core.data.db.entity.CurrencyEntity

/**
 * Reference data inserted when the database file is first created. This is not demo data:
 * currencies are definitions, not financial records.
 */
internal object SeedData {
    val currencies = listOf(
        CurrencyEntity("IRR", 0, "ریال", "ریال ایران", "Iranian Rial", sortOrder = 0),
        CurrencyEntity("USD", 2, "$", "دلار آمریکا", "US Dollar", sortOrder = 1),
        CurrencyEntity("EUR", 2, "€", "یورو", "Euro", sortOrder = 2),
        CurrencyEntity("GBP", 2, "£", "پوند انگلیس", "British Pound", sortOrder = 3),
        CurrencyEntity("AED", 2, "د.إ", "درهم امارات", "UAE Dirham", sortOrder = 4),
        CurrencyEntity("TRY", 2, "₺", "لیر ترکیه", "Turkish Lira", sortOrder = 5),
        CurrencyEntity("CAD", 2, "C$", "دلار کانادا", "Canadian Dollar", sortOrder = 6),
        CurrencyEntity("AUD", 2, "A$", "دلار استرالیا", "Australian Dollar", sortOrder = 7),
        CurrencyEntity("CHF", 2, "CHF", "فرانک سوئیس", "Swiss Franc", sortOrder = 8),
        CurrencyEntity("SEK", 2, "kr", "کرون سوئد", "Swedish Krona", sortOrder = 9),
        CurrencyEntity("NOK", 2, "kr", "کرون نروژ", "Norwegian Krone", sortOrder = 10),
        CurrencyEntity("DKK", 2, "kr", "کرون دانمارک", "Danish Krone", sortOrder = 11),
        CurrencyEntity("JPY", 0, "¥", "ین ژاپن", "Japanese Yen", sortOrder = 12),
        CurrencyEntity("CNY", 2, "¥", "یوان چین", "Chinese Yuan", sortOrder = 13),
        CurrencyEntity("INR", 2, "₹", "روپیه هند", "Indian Rupee", sortOrder = 14),
    )

    fun insertCurrencies(db: SupportSQLiteDatabase) {
        currencies.forEach { c ->
            db.execSQL(
                "INSERT OR IGNORE INTO currency (code, minorDigits, symbol, nameFa, nameEn, isCustom, isEnabled, sortOrder) " +
                    "VALUES (?, ?, ?, ?, ?, 0, 1, ?)",
                arrayOf<Any>(c.code, c.minorDigits, c.symbol, c.nameFa, c.nameEn, c.sortOrder),
            )
        }
    }
}
