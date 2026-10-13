package com.mymoney.app.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import com.mymoney.app.feature.accounts.AccountDetailScreen
import com.mymoney.app.feature.accounts.AccountEditorScreen
import com.mymoney.app.feature.accounts.AccountsScreen
import com.mymoney.app.feature.categories.CategoriesScreen
import com.mymoney.app.feature.currency.CurrenciesScreen
import com.mymoney.app.feature.currency.RatesScreen
import com.mymoney.app.feature.dashboard.DashboardScreen
import com.mymoney.app.feature.placeholder.ComingNextScreen
import com.mymoney.app.feature.reports.MonthlyReportScreen
import com.mymoney.app.feature.settings.SettingsScreen
import com.mymoney.app.feature.transactions.EntryDetailScreen
import com.mymoney.app.feature.transactions.EntryEditorScreen
import com.mymoney.app.feature.transactions.TransactionsScreen
import java.time.LocalDate

@Composable
fun MyMoneyNavHost(
    navController: NavHostController,
    contentPadding: PaddingValues,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val today = remember { LocalDate.now() }
    val openEntry: (String) -> Unit = { navController.navigate(EntryDetailRoute(it)) }
    val back: () -> Unit = { navController.popBackStack() }

    NavHost(navController = navController, startDestination = HomeGraph, modifier = modifier) {
        navigation<HomeGraph>(startDestination = HomeRoute) {
            composable<HomeRoute> {
                DashboardScreen(
                    contentPadding = contentPadding,
                    onAddAccount = { navController.navigate(AccountEditorRoute()) },
                    onOpenEntry = openEntry,
                    onSeeAllTransactions = { navController.navigateToTab(TopLevelDestination.TRANSACTIONS) },
                    onOpenWealth = { navController.navigateToTab(TopLevelDestination.WEALTH) },
                    onOpenReports = { navController.navigateToTab(TopLevelDestination.REPORTS) },
                    onOpenRates = { navController.navigate(RatesRoute) },
                )
            }
        }
        navigation<TransactionsGraph>(startDestination = TransactionsRoute) {
            composable<TransactionsRoute> {
                TransactionsScreen(contentPadding = contentPadding, snackbar = snackbar, today = today, onOpen = openEntry)
            }
        }
        navigation<PlanningGraph>(startDestination = PlanningRoute) {
            composable<PlanningRoute> { ComingNextScreen(Modifier.padding(contentPadding)) }
        }
        navigation<WealthGraph>(startDestination = WealthRoute) {
            composable<WealthRoute> {
                AccountsScreen(
                    contentPadding = contentPadding,
                    onOpenAccount = { navController.navigate(AccountDetailRoute(it)) },
                    onAddAccount = { navController.navigate(AccountEditorRoute()) },
                    onOpenRates = { navController.navigate(RatesRoute) },
                )
            }
        }
        navigation<ReportsGraph>(startDestination = ReportsRoute) {
            composable<ReportsRoute> {
                MonthlyReportScreen(contentPadding = contentPadding, onOpenRates = { navController.navigate(RatesRoute) })
            }
        }

        composable<SettingsRoute> {
            SettingsScreen(
                onBack = back,
                onOpenCategories = { navController.navigate(CategoriesRoute) },
                onOpenCurrencies = { navController.navigate(CurrenciesRoute) },
                onOpenRates = { navController.navigate(RatesRoute) },
            )
        }
        composable<CategoriesRoute> { CategoriesScreen(onBack = back) }
        composable<CurrenciesRoute> { CurrenciesScreen(onBack = back, onOpenRates = { navController.navigate(RatesRoute) }) }
        composable<RatesRoute> { RatesScreen(onBack = back) }
        composable<EntryEditorRoute> { EntryEditorScreen(onDone = back) }
        composable<EntryDetailRoute> {
            EntryDetailScreen(
                onBack = back,
                onEdit = { navController.navigate(EntryEditorRoute(groupId = it)) },
                onCopy = { navController.navigate(EntryEditorRoute(copyOf = it)) },
                onRefund = { navController.navigate(EntryEditorRoute(mode = "REFUND", refundOf = it)) },
            )
        }
        composable<AccountDetailRoute> {
            AccountDetailScreen(
                onBack = back,
                onEdit = { navController.navigate(AccountEditorRoute(it)) },
                onOpenEntry = openEntry,
                onNewEntry = { mode, accountId -> navController.navigate(EntryEditorRoute(mode = mode, accountId = accountId)) },
            )
        }
        composable<AccountEditorRoute> { AccountEditorScreen(onDone = { back() }) }
    }
}
