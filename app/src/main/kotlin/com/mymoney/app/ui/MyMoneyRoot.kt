package com.mymoney.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mymoney.app.MainUiState
import com.mymoney.app.R
import com.mymoney.app.feature.onboarding.OnboardingScreen
import com.mymoney.app.navigation.AccountEditorRoute
import com.mymoney.app.navigation.EntryEditorRoute
import com.mymoney.app.navigation.MyMoneyNavHost
import com.mymoney.app.navigation.SearchRoute
import com.mymoney.app.navigation.SettingsRoute
import com.mymoney.app.navigation.TopLevelDestination
import com.mymoney.app.navigation.navigateToTab

@Composable
fun MyMoneyRoot(state: MainUiState, onToggleHideAmounts: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // Wait for the first settings read so theme/language never flicker.
        val settings = state.settings ?: return@Surface
        if (!settings.onboardingDone) {
            OnboardingScreen()
        } else {
            MainScaffold(hideAmounts = settings.hideAmounts, onToggleHideAmounts = onToggleHideAmounts)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(
    hideAmounts: Boolean,
    onToggleHideAmounts: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val currentTab = TopLevelDestination.entries.firstOrNull { tab -> destination?.hasRoute(tab.startRoute) == true }
    val snackbar = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            if (currentTab != null) {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(currentTab.label)) },
                    navigationIcon = {
                        IconButton(onClick = onToggleHideAmounts) {
                            Icon(
                                if (hideAmounts) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = stringResource(if (hideAmounts) R.string.action_show_amounts else R.string.action_hide_amounts),
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { navController.navigate(SearchRoute) }) {
                            Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.action_search))
                        }
                        IconButton(onClick = { navController.navigate(SettingsRoute) }) {
                            Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.action_settings))
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (currentTab != null) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { tab ->
                        val selected = destination.isOnTab(tab)
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateToTab(tab) },
                            icon = { Icon(if (selected) tab.selectedIcon else tab.unselectedIcon, contentDescription = null) },
                            label = { Text(stringResource(tab.label), maxLines = 1) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            when (currentTab) {
                TopLevelDestination.HOME, TopLevelDestination.TRANSACTIONS -> FloatingActionButton(onClick = { navController.navigate(EntryEditorRoute()) }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_quick_add))
                }
                TopLevelDestination.WEALTH -> FloatingActionButton(onClick = { navController.navigate(AccountEditorRoute()) }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.acc_add))
                }
                else -> Unit
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        MyMoneyNavHost(navController = navController, contentPadding = padding, snackbar = snackbar)
    }
}

private fun NavDestination?.isOnTab(tab: TopLevelDestination): Boolean =
    this?.hierarchy?.any { it.hasRoute(tab.graph::class) } == true
