package com.mymoney.app.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.ui.graphics.vector.ImageVector
import com.mymoney.app.R
import kotlin.reflect.KClass
import kotlinx.serialization.Serializable

// Graph roots (one per bottom-bar tab; each keeps its own back stack).
@Serializable data object HomeGraph
@Serializable data object TransactionsGraph
@Serializable data object PlanningGraph
@Serializable data object WealthGraph
@Serializable data object ReportsGraph

// Tab start destinations.
@Serializable data object HomeRoute
@Serializable data object TransactionsRoute
@Serializable data object PlanningRoute
@Serializable data object WealthRoute
@Serializable data object ReportsRoute

// Secondary destinations.
@Serializable data object SettingsRoute
@Serializable data object CategoriesRoute
@Serializable data object CurrenciesRoute
@Serializable data object RatesRoute

/** New/edit/copy/refund/adjust editor. [mode] is an `EditorMode` name. */
@Serializable
data class EntryEditorRoute(
    val mode: String = "EXPENSE",
    val groupId: String? = null,
    val accountId: String? = null,
    val refundOf: String? = null,
    val copyOf: String? = null,
)

@Serializable data class EntryDetailRoute(val groupId: String)
@Serializable data class AccountDetailRoute(val accountId: String)
@Serializable data class AccountEditorRoute(val accountId: String? = null)

enum class TopLevelDestination(
    val graph: Any,
    val startRoute: KClass<*>,
    @StringRes val label: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    HOME(HomeGraph, HomeRoute::class, R.string.nav_home, Icons.Filled.Home, Icons.Outlined.Home),
    TRANSACTIONS(
        TransactionsGraph, TransactionsRoute::class, R.string.nav_transactions,
        Icons.AutoMirrored.Filled.ReceiptLong, Icons.AutoMirrored.Outlined.ReceiptLong,
    ),
    PLANNING(PlanningGraph, PlanningRoute::class, R.string.nav_planning, Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth),
    WEALTH(
        WealthGraph, WealthRoute::class, R.string.nav_wealth,
        Icons.Filled.AccountBalanceWallet, Icons.Outlined.AccountBalanceWallet,
    ),
    REPORTS(ReportsGraph, ReportsRoute::class, R.string.nav_reports, Icons.Filled.Insights, Icons.Outlined.Insights),
}

