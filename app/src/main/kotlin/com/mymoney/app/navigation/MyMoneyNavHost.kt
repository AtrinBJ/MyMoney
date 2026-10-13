package com.mymoney.app.navigation

import androidx.compose.foundation.layout.PaddingValues
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
import androidx.navigation.toRoute
import com.mymoney.app.feature.planning.BudgetEditorScreen
import com.mymoney.app.feature.wealth.AssetDetailScreen
import com.mymoney.app.feature.wealth.AssetEditorScreen
import com.mymoney.app.feature.wealth.ObligationDetailScreen
import com.mymoney.app.feature.wealth.ObligationEditorScreen
import com.mymoney.app.feature.wealth.ObligationsScreen
import com.mymoney.app.feature.planning.GoalDetailScreen
import com.mymoney.app.feature.planning.GoalEditorScreen
import com.mymoney.app.feature.planning.PlanningScreen
import com.mymoney.app.feature.planning.RuleEditorScreen
import com.mymoney.app.feature.planning.RulesScreen
import com.mymoney.app.feature.planning.SubscriptionsScreen
import com.mymoney.app.feature.planning.ToolsScreen
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
            composable<PlanningRoute> {
                PlanningScreen(
                    contentPadding = contentPadding,
                    onOpenBudget = { navController.navigate(BudgetEditorRoute(it)) },
                    onOpenRules = { navController.navigate(RulesRoute) },
                    onOpenRule = { navController.navigate(RuleEditorRoute(ruleId = it)) },
                    onOpenSubscriptions = { navController.navigate(SubscriptionsRoute) },
                    onOpenGoal = { navController.navigate(GoalDetailRoute(it)) },
                    onNewGoal = { navController.navigate(GoalEditorRoute()) },
                    onOpenTools = { navController.navigate(ToolsRoute) },
                )
            }
        }
        navigation<WealthGraph>(startDestination = WealthRoute) {
            composable<WealthRoute> {
                AccountsScreen(
                    contentPadding = contentPadding,
                    onOpenAccount = { navController.navigate(AccountDetailRoute(it)) },
                    onAddAccount = { navController.navigate(AccountEditorRoute()) },
                    onOpenRates = { navController.navigate(RatesRoute) },
                    onOpenAsset = { navController.navigate(AssetDetailRoute(it)) },
                    onAddAsset = { navController.navigate(AssetEditorRoute()) },
                    onOpenObligations = { navController.navigate(ObligationsRoute(it)) },
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
        composable<BudgetEditorRoute> { BudgetEditorScreen(onDone = back) }
        composable<RulesRoute> { RulesScreen(onBack = back, onOpenRule = { navController.navigate(RuleEditorRoute(ruleId = it)) }) }
        composable<RuleEditorRoute> { RuleEditorScreen(onDone = back) }
        composable<SubscriptionsRoute> {
            SubscriptionsScreen(onBack = back, onOpenRule = { navController.navigate(if (it == null) RuleEditorRoute(kind = "SUBSCRIPTION") else RuleEditorRoute(ruleId = it)) })
        }
        composable<GoalDetailRoute> { GoalDetailScreen(onBack = back, onEdit = { navController.navigate(GoalEditorRoute(it)) }) }
        composable<GoalEditorRoute> { GoalEditorScreen(onDone = back) }
        composable<ToolsRoute> { ToolsScreen(onBack = back) }
        composable<AssetEditorRoute> { AssetEditorScreen(onDone = back) }
        composable<AssetDetailRoute> { AssetDetailScreen(onBack = back, onEdit = { navController.navigate(AssetEditorRoute(it)) }) }
        composable<ObligationsRoute> { entry ->
            ObligationsScreen(
                initialReceivables = entry.toRoute<ObligationsRoute>().receivables,
                onBack = back,
                onOpen = { navController.navigate(ObligationDetailRoute(it)) },
                onAdd = { navController.navigate(ObligationEditorRoute(it)) },
            )
        }
        composable<ObligationEditorRoute> { ObligationEditorScreen(onDone = back) }
        composable<ObligationDetailRoute> {
            ObligationDetailScreen(
                onBack = back,
                onOpenEntry = openEntry,
                onAddInstallments = { navController.navigate(RuleEditorRoute(kind = "LOAN_INSTALLMENT", toAccountId = it)) },
            )
        }
    }
}
