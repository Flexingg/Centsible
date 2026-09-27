package app.canopy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.canopy.core.designsystem.component.LoadingState
import app.canopy.core.designsystem.theme.CanopyTheme
import app.canopy.core.extensions.Destination
import app.canopy.core.model.AccountId
import app.canopy.core.model.TransactionId
import app.canopy.feature.accounts.AccountDetailRoute
import app.canopy.feature.accounts.AccountDetailViewModel
import app.canopy.feature.accounts.AccountsRoute
import app.canopy.feature.budget.BudgetRoute
import app.canopy.feature.budget.CategoryManagerRoute
import app.canopy.feature.dashboard.DashboardRoute
import app.canopy.feature.onboarding.BudgetPickerRoute
import app.canopy.feature.onboarding.PairingRoute
import app.canopy.feature.settings.SettingsRoute
import app.canopy.feature.settings.MoreItem
import app.canopy.feature.settings.MoreScreen
import app.canopy.feature.planning.MerchantsRoute
import app.canopy.feature.planning.RecurringRoute
import app.canopy.feature.planning.RulesRoute
import app.canopy.feature.planning.TagsRoute
import app.canopy.feature.reports.ReportsRoute
import app.canopy.feature.transactions.TransactionsViewModel
import app.canopy.feature.transactions.TransactionEditorRoute
import app.canopy.feature.transactions.TransactionEditorViewModel
import app.canopy.feature.transactions.TransactionsRoute

private enum class Tab(val route: String, val label: String, val icon: ImageVector, val canAdd: Boolean) {
    Dashboard("dashboard", "Dashboard", Icons.Rounded.Home, canAdd = true),
    Accounts("accounts", "Accounts", Icons.Rounded.AccountBalance, canAdd = true),
    Transactions("transactions", "Transactions", Icons.AutoMirrored.Rounded.ReceiptLong, canAdd = true),
    Budget("budget", "Budget", Icons.Rounded.PieChart, canAdd = false),
    More("more", "More", Icons.Rounded.Menu, canAdd = false),
}

private object Routes {
    const val ACCOUNT = "account/{${AccountDetailViewModel.ARG_ID}}"
    const val TRANSACTION = "transaction?${TransactionEditorViewModel.ARG_ID}={${TransactionEditorViewModel.ARG_ID}}&" +
        "${TransactionEditorViewModel.ARG_ACCOUNT}={${TransactionEditorViewModel.ARG_ACCOUNT}}"
    const val CATEGORIES = "budget/categories"
    const val SETTINGS = "settings"
    const val RECURRING = "recurring"
    const val REPORTS = "reports"
    const val MERCHANTS = "merchants"
    const val RULES = "rules"
    const val TAGS = "tags"
    const val SEARCH = "transactions/search?${TransactionsViewModel.ARG_QUERY}={${TransactionsViewModel.ARG_QUERY}}"

    fun search(q: String) = "transactions/search?${TransactionsViewModel.ARG_QUERY}=" + java.net.URLEncoder.encode(q, "UTF-8")

    fun account(id: AccountId) = "account/${id.raw}"
    fun transaction(id: TransactionId?, account: AccountId? = null) =
        "transaction?${TransactionEditorViewModel.ARG_ID}=${id?.raw.orEmpty()}&${TransactionEditorViewModel.ARG_ACCOUNT}=${account?.raw.orEmpty()}"
}

@Composable
fun CanopyApp(pairingLink: String?, viewModel: AppViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val offline by viewModel.offline.collectAsStateWithLifecycle()
    CanopyTheme {
        when (val s = state) {
            AppState.Starting -> LoadingState()
            AppState.NeedsPairing -> PairingRoute(deepLink = pairingLink)
            AppState.NeedsBudget -> BudgetPickerRoute()
            // Switching budgets rebuilds navigation and every screen's state.
            is AppState.Ready -> key(s.budget) { MainScaffold(offline) }
        }
    }
}

@Composable
private fun MainScaffold(offline: Boolean) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val tab = Tab.entries.firstOrNull { it.route == current }
    val colors = CanopyTheme.colors
    Scaffold(
        containerColor = colors.canvas,
        topBar = { if (offline) OfflineBanner() },
        floatingActionButton = {
            if (tab?.canAdd == true) {
                FloatingActionButton(onClick = { nav.navigate(Routes.transaction(null)) }, containerColor = colors.accent, contentColor = colors.card) {
                    Icon(Icons.Rounded.Add, contentDescription = "Add transaction")
                }
            }
        },
        bottomBar = {
            if (tab != null) {
                NavigationBar(containerColor = colors.card) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = t == tab,
                            onClick = { nav.goToTab(t.route) },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
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
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Tab.Dashboard.route, modifier = Modifier.padding(padding)) {
            composable(Tab.Dashboard.route) { DashboardRoute(onNavigate = { nav.go(it) }) }
            composable(Tab.Accounts.route) { AccountsRoute(onOpenAccount = { nav.navigate(Routes.account(it)) }) }
            composable(Tab.Transactions.route) { TransactionsRoute(onOpen = { nav.navigate(Routes.transaction(it)) }) }
            composable(Tab.Budget.route) { BudgetRoute(onManageCategories = { nav.navigate(Routes.CATEGORIES) }) }
            composable(Tab.More.route) {
                MoreScreen(onOpen = { item ->
                    nav.navigate(
                        when (item) {
                            MoreItem.Recurring -> Routes.RECURRING
                            MoreItem.Reports -> Routes.REPORTS
                            MoreItem.Merchants -> Routes.MERCHANTS
                            MoreItem.Rules -> Routes.RULES
                            MoreItem.Tags -> Routes.TAGS
                            MoreItem.Settings -> Routes.SETTINGS
                        },
                    )
                })
            }
            composable(Routes.SETTINGS) { SettingsRoute() }
            composable(Routes.RECURRING) { RecurringRoute(onBack = { nav.popBackStack() }) }
            composable(Routes.REPORTS) { ReportsRoute(onBack = { nav.popBackStack() }) }
            composable(Routes.MERCHANTS) { MerchantsRoute(onBack = { nav.popBackStack() }) }
            composable(Routes.RULES) { RulesRoute(onBack = { nav.popBackStack() }) }
            composable(Routes.TAGS) { TagsRoute(onBack = { nav.popBackStack() }, onSearch = { nav.navigate(Routes.search(it)) }) }
            composable(Routes.SEARCH, arguments = listOf(navArgument(TransactionsViewModel.ARG_QUERY) { type = NavType.StringType; defaultValue = "" })) {
                TransactionsRoute(onOpen = { nav.navigate(Routes.transaction(it)) })
            }
            composable(Routes.ACCOUNT, arguments = listOf(navArgument(AccountDetailViewModel.ARG_ID) { type = NavType.StringType })) {
                AccountDetailRoute(onBack = { nav.popBackStack() }, onOpenTransaction = { nav.navigate(Routes.transaction(it)) })
            }
            composable(
                Routes.TRANSACTION,
                arguments = listOf(
                    navArgument(TransactionEditorViewModel.ARG_ID) { type = NavType.StringType; defaultValue = "" },
                    navArgument(TransactionEditorViewModel.ARG_ACCOUNT) { type = NavType.StringType; defaultValue = "" },
                ),
            ) { TransactionEditorRoute(onClose = { nav.popBackStack() }) }
            composable(Routes.CATEGORIES) { CategoryManagerRoute(onBack = { nav.popBackStack() }) }
        }
    }
}

/** Shown while reads come from the offline cache; writes will fail until back online. */
@Composable
private fun OfflineBanner() {
    val colors = CanopyTheme.colors
    Text(
        "Offline · showing saved data. Changes need a connection.",
        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
        color = colors.textPrimary,
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.warning.copy(alpha = 0.25f))
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

private fun NavHostController.goToTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

private fun NavHostController.go(d: Destination) = when (d) {
    Destination.Accounts -> goToTab(Tab.Accounts.route)
    Destination.Transactions -> goToTab(Tab.Transactions.route)
    Destination.Budget -> goToTab(Tab.Budget.route)
    Destination.Settings -> navigate(Routes.SETTINGS)
    Destination.Recurring -> navigate(Routes.RECURRING)
    Destination.Reports -> navigate(Routes.REPORTS)
    is Destination.Account -> navigate(Routes.account(d.id))
    is Destination.Transaction -> navigate(Routes.transaction(d.id, d.account))
}
