package com.mymoney.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mymoney.core.designsystem.theme.AmbientBackground
import com.mymoney.core.designsystem.theme.backdropSource
import com.mymoney.core.designsystem.theme.rememberBackdrop
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

    // Everything under the bars is recorded once per frame; the bars draw it blurred (glass).
    val backdrop = rememberBackdrop()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            if (currentTab != null) {
                GlassTopBar(backdrop) {
                    CenterAlignedTopAppBar(
                        title = { Text(stringResource(currentTab.label)) },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
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
            }
        },
        bottomBar = {
            if (currentTab != null) {
                GlassNavBar(selected = TopLevelDestination.entries.firstOrNull { destination.isOnTab(it) }, onSelect = { navController.navigateToTab(it) }, backdrop = backdrop)
            }
        },
        floatingActionButton = {
            val fabShape = RoundedCornerShape(22.dp)
            when (currentTab) {
                TopLevelDestination.HOME, TopLevelDestination.TRANSACTIONS -> FloatingActionButton(onClick = { navController.navigate(EntryEditorRoute()) }, shape = fabShape) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_quick_add))
                }
                TopLevelDestination.WEALTH -> FloatingActionButton(onClick = { navController.navigate(AccountEditorRoute()) }, shape = fabShape) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.acc_add))
                }
                else -> Unit
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().backdropSource(backdrop)) {
            AmbientBackground()
            MyMoneyNavHost(navController = navController, contentPadding = padding, snackbar = snackbar)
        }
    }
}

private fun NavDestination?.isOnTab(tab: TopLevelDestination): Boolean =
    this?.hierarchy?.any { it.hasRoute(tab.graph::class) } == true
