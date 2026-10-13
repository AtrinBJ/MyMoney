package com.mymoney.core.domain.model

enum class CategoryType { INCOME, EXPENSE }

enum class CostType { FIXED, VARIABLE }

data class Category(
    val id: String,
    val type: CategoryType,
    val parentId: String? = null,
    /** Set only for built-in categories; the UI shows a translated name unless [name] is set. */
    val systemKey: String? = null,
    val name: String? = null,
    val iconKey: String? = null,
    val colorKey: String? = null,
    val defaultCostType: CostType? = null,
    val defaultEssential: Boolean? = null,
    val isArchived: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    /** Built-in categories the ledger rules rely on; they can be renamed but not deleted (docs/03 §3.3). */
    val isProtected: Boolean get() = systemKey in SystemCategories.protectedKeys
}

object SystemCategories {
    const val FEES = "expense.fees"
    const val INTEREST = "expense.interest"
    const val OTHER_EXPENSE = "expense.other"
    const val OTHER_INCOME = "income.other"
    val protectedKeys = setOf(FEES, INTEREST, OTHER_EXPENSE, OTHER_INCOME)
}

data class Tag(
    val id: String,
    val name: String,
    val colorKey: String? = null,
    val createdAt: Long = 0,
)
