package com.mymoney.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import com.mymoney.core.data.db.entity.CurrencyEntity
import com.mymoney.core.domain.model.CategoryType

/**
 * Reference data inserted when the database file is first created. This is not demo data:
 * currencies and default categories are definitions, not financial records.
 */
object SeedData {
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

    /** Built-in category: [key] doubles as the translation key in the app (`category_<key>`). */
    data class DefaultCategory(val key: String, val type: CategoryType, val icon: String, val color: String)

    /** Docs/01 §1.3 list. Debt principal, loans given back and asset sales are NOT categories (FBR-04/05/07). */
    val categories = listOf(
        DefaultCategory("expense.housing", CategoryType.EXPENSE, "home", "c0"),
        DefaultCategory("expense.food", CategoryType.EXPENSE, "grocery", "c2"),
        DefaultCategory("expense.transport", CategoryType.EXPENSE, "bus", "c4"),
        DefaultCategory("expense.utilities", CategoryType.EXPENSE, "bolt", "c5"),
        DefaultCategory("expense.internet_phone", CategoryType.EXPENSE, "wifi", "c1"),
        DefaultCategory("expense.shopping", CategoryType.EXPENSE, "bag", "c3"),
        DefaultCategory("expense.clothing", CategoryType.EXPENSE, "checkroom", "c6"),
        DefaultCategory("expense.health", CategoryType.EXPENSE, "health", "c7"),
        DefaultCategory("expense.leisure", CategoryType.EXPENSE, "sports", "c8"),
        DefaultCategory("expense.education", CategoryType.EXPENSE, "school", "c1"),
        DefaultCategory("expense.travel", CategoryType.EXPENSE, "flight", "c4"),
        DefaultCategory("expense.subscriptions", CategoryType.EXPENSE, "subscriptions", "c6"),
        DefaultCategory("expense.car", CategoryType.EXPENSE, "car", "c9"),
        DefaultCategory("expense.tax_insurance", CategoryType.EXPENSE, "shield", "c0"),
        DefaultCategory("expense.interest", CategoryType.EXPENSE, "percent", "c7"),
        DefaultCategory("expense.gifts", CategoryType.EXPENSE, "gift", "c3"),
        DefaultCategory("expense.work", CategoryType.EXPENSE, "work", "c5"),
        DefaultCategory("expense.fees", CategoryType.EXPENSE, "bank", "c9"),
        DefaultCategory("expense.other", CategoryType.EXPENSE, "more", "c9"),
        DefaultCategory("income.salary", CategoryType.INCOME, "payments", "c0"),
        DefaultCategory("income.business", CategoryType.INCOME, "store", "c1"),
        DefaultCategory("income.freelance", CategoryType.INCOME, "laptop", "c4"),
        DefaultCategory("income.bonus", CategoryType.INCOME, "trophy", "c2"),
        DefaultCategory("income.rental", CategoryType.INCOME, "apartment", "c5"),
        DefaultCategory("income.investment", CategoryType.INCOME, "trending", "c6"),
        DefaultCategory("income.bank_interest", CategoryType.INCOME, "savings", "c8"),
        DefaultCategory("income.gift", CategoryType.INCOME, "gift", "c3"),
        DefaultCategory("income.other", CategoryType.INCOME, "more", "c9"),
    )

    fun categoryId(key: String) = "sys-$key"

    internal fun insertReferenceData(db: SupportSQLiteDatabase, nowMillis: Long = System.currentTimeMillis()) {
        currencies.forEach { c ->
            db.execSQL(
                "INSERT OR IGNORE INTO currency (code, minorDigits, symbol, nameFa, nameEn, isCustom, isEnabled, sortOrder) " +
                    "VALUES (?, ?, ?, ?, ?, 0, 1, ?)",
                arrayOf<Any>(c.code, c.minorDigits, c.symbol, c.nameFa, c.nameEn, c.sortOrder),
            )
        }
        categories.forEachIndexed { i, c ->
            db.execSQL(
                "INSERT OR IGNORE INTO category (id, type, parentId, systemKey, name, iconKey, colorKey, defaultCostType, " +
                    "defaultEssential, isArchived, sortOrder, createdAt, updatedAt) VALUES (?, ?, NULL, ?, NULL, ?, ?, NULL, NULL, 0, ?, ?, ?)",
                arrayOf<Any>(categoryId(c.key), c.type.name, c.key, c.icon, c.color, i, nowMillis, nowMillis),
            )
        }
    }
}
