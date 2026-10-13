package com.mymoney.app.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import com.mymoney.app.feature.placeholder.ComingNextScreen
import com.mymoney.app.feature.settings.SettingsScreen

@Composable
fun MyMoneyNavHost(
    navController: NavHostController,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = HomeGraph,
        modifier = modifier,
    ) {
        navigation<HomeGraph>(startDestination = HomeRoute) {
            composable<HomeRoute> { ComingNextScreen(Modifier.padding(contentPadding)) }
        }
        navigation<TransactionsGraph>(startDestination = TransactionsRoute) {
            composable<TransactionsRoute> { ComingNextScreen(Modifier.padding(contentPadding)) }
        }
        navigation<PlanningGraph>(startDestination = PlanningRoute) {
            composable<PlanningRoute> { ComingNextScreen(Modifier.padding(contentPadding)) }
        }
        navigation<WealthGraph>(startDestination = WealthRoute) {
            composable<WealthRoute> { ComingNextScreen(Modifier.padding(contentPadding)) }
        }
        navigation<ReportsGraph>(startDestination = ReportsRoute) {
            composable<ReportsRoute> { ComingNextScreen(Modifier.padding(contentPadding)) }
        }
        composable<SettingsRoute> {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
