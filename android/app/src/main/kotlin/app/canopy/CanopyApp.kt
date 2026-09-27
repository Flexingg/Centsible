package app.canopy

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.canopy.core.designsystem.component.LoadingState
import app.canopy.core.designsystem.theme.CanopyTheme
import app.canopy.core.extensions.Destination
import app.canopy.feature.accounts.AccountsRoute
import app.canopy.feature.budget.BudgetRoute
import app.canopy.feature.dashboard.DashboardRoute
import app.canopy.feature.onboarding.BudgetPickerRoute
import app.canopy.feature.onboarding.PairingRoute
import app.canopy.feature.settings.SettingsRoute
import app.canopy.feature.transactions.TransactionsRoute

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Dashboard("dashboard", "Dashboard", Icons.Rounded.Home),
    Accounts("accounts", "Accounts", Icons.Rounded.AccountBalance),
    Transactions("transactions", "Transactions", Icons.AutoMirrored.Rounded.ReceiptLong),
    Budget("budget", "Budget", Icons.Rounded.PieChart),
    More("settings", "More", Icons.Rounded.Settings),
}

@Composable
fun CanopyApp(pairingLink: String?, viewModel: AppViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CanopyTheme {
        when (state) {
            AppState.Starting -> LoadingState()
            AppState.NeedsPairing -> PairingRoute(deepLink = pairingLink)
            AppState.NeedsBudget -> BudgetPickerRoute()
            AppState.Ready -> MainScaffold()
        }
    }
}

@Composable
private fun MainScaffold() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val go = { route: String ->
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    val colors = CanopyTheme.colors
    Scaffold(
        containerColor = colors.canvas,
        bottomBar = {
            NavigationBar(containerColor = colors.card) {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab.route,
                        onClick = { go(tab.route) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = colors.accent,
                            selectedTextColor = colors.accent,
                            indicatorColor = colors.accentSoft,
                            unselectedIconColor = colors.textTertiary,
                            unselectedTextColor = colors.textTertiary,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Tab.Dashboard.route, modifier = Modifier.padding(padding)) {
            composable(Tab.Dashboard.route) {
                DashboardRoute(onNavigate = { d ->
                    go(
                        when (d) {
                            Destination.Accounts -> Tab.Accounts.route
                            Destination.Transactions -> Tab.Transactions.route
                            Destination.Budget -> Tab.Budget.route
                            Destination.Settings -> Tab.More.route
                        },
                    )
                })
            }
            composable(Tab.Accounts.route) { AccountsRoute() }
            composable(Tab.Transactions.route) { TransactionsRoute() }
            composable(Tab.Budget.route) { BudgetRoute() }
            composable(Tab.More.route) { SettingsRoute() }
        }
    }
}
