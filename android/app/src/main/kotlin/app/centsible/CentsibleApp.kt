package app.centsible

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry
import app.centsible.core.domain.userMessage
import kotlinx.coroutines.launch
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
import app.centsible.core.designsystem.component.LoadingState
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.extensions.Destination
import app.centsible.core.model.AccountId
import app.centsible.core.model.CategoryId
import app.centsible.core.model.YearMonth
import app.centsible.core.model.TransactionId
import app.centsible.feature.accounts.AccountDetailRoute
import app.centsible.feature.accounts.AccountDetailViewModel
import app.centsible.feature.accounts.AccountsRoute
import app.centsible.feature.budget.BudgetRoute
import app.centsible.feature.budget.CategoryManagerRoute
import app.centsible.feature.dashboard.DashboardRoute
import app.centsible.feature.onboarding.BudgetPickerRoute
import app.centsible.feature.onboarding.PairingRoute
import app.centsible.feature.settings.SettingsRoute
import app.centsible.feature.settings.MoreItem
import app.centsible.feature.settings.MoreScreen
import app.centsible.feature.planning.MerchantsRoute
import app.centsible.feature.planning.RecurringRoute
import app.centsible.feature.planning.RulesRoute
import app.centsible.feature.planning.TagsRoute
import app.centsible.feature.reports.ReportsRoute
import app.centsible.feature.transactions.TransactionsViewModel
import app.centsible.feature.transactions.TransactionEditorRoute
import app.centsible.feature.transactions.TransactionEditorViewModel
import app.centsible.feature.transactions.TransactionsRoute

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
    const val BANK_SYNC = "bank-sync"
    const val REPORTS = "reports"
    const val MERCHANTS = "merchants"
    const val RULES = "rules"
    const val TAGS = "tags"
    const val GOALS = "goals"
    const val CALENDAR = "calendar"
    const val FORECAST = "forecast"
    const val SUBSCRIPTIONS = "subscriptions"
    const val TRENDS = "trends"
    const val NET_WORTH = "net-worth"
    const val YEAR_IN_REVIEW = "year-in-review?period={period}&date={date}"
    fun review(period: String? = null, date: String? = null) = "year-in-review?period=${period.orEmpty()}&date=${date.orEmpty()}"
    const val SERVER = "server"
    const val SEARCH = "transactions/search?${TransactionsViewModel.ARG_QUERY}={${TransactionsViewModel.ARG_QUERY}}"

    const val REVIEW = "review"
    const val RULE = "rule?id={id}&payee={payee}&category={category}"
    fun rule(id: String?, payee: String? = null, category: String? = null) = "rule?id=${id.orEmpty()}&payee=${payee.orEmpty()}&category=${category.orEmpty()}"
    const val AUTOMATIONS = "automations?month={month}"
    fun automations(month: YearMonth) = "automations?month=${month.raw}"
    const val AUTOMATION = "automation/{category}?month={month}"
    fun automation(category: CategoryId, month: YearMonth) = "automation/${category.raw}?month=${month.raw}"
    const val TRANSACTIONS_FOR = "transactions/for?${TransactionsViewModel.ARG_TITLE}={${TransactionsViewModel.ARG_TITLE}}" +
        "&${TransactionsViewModel.ARG_CATEGORY}={${TransactionsViewModel.ARG_CATEGORY}}&${TransactionsViewModel.ARG_GROUP}={${TransactionsViewModel.ARG_GROUP}}" +
        "&${TransactionsViewModel.ARG_PAYEE}={${TransactionsViewModel.ARG_PAYEE}}&${TransactionsViewModel.ARG_SINCE}={${TransactionsViewModel.ARG_SINCE}}" +
        "&${TransactionsViewModel.ARG_UNTIL}={${TransactionsViewModel.ARG_UNTIL}}"
    fun transactionsFor(d: Destination.TransactionsFor): String {
        fun enc(v: String?) = java.net.URLEncoder.encode(v.orEmpty(), "UTF-8")
        return "transactions/for?${TransactionsViewModel.ARG_TITLE}=${enc(d.title)}&${TransactionsViewModel.ARG_CATEGORY}=${enc(d.categoryId?.raw)}" +
            "&${TransactionsViewModel.ARG_GROUP}=${enc(d.groupId?.raw)}&${TransactionsViewModel.ARG_PAYEE}=${enc(d.payeeId?.raw)}" +
            "&${TransactionsViewModel.ARG_SINCE}=${enc(d.since)}&${TransactionsViewModel.ARG_UNTIL}=${enc(d.until)}"
    }
    fun search(q: String) = "transactions/search?${TransactionsViewModel.ARG_QUERY}=" + java.net.URLEncoder.encode(q, "UTF-8")

    fun account(id: AccountId) = "account/${id.raw}"
    fun transaction(id: TransactionId?, account: AccountId? = null) =
        "transaction?${TransactionEditorViewModel.ARG_ID}=${id?.raw.orEmpty()}&${TransactionEditorViewModel.ARG_ACCOUNT}=${account?.raw.orEmpty()}"
}

@Composable
fun CentsibleApp(pairingLink: String?, openScreen: String? = null, onOpened: () -> Unit = {}, viewModel: AppViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val offline by viewModel.offline.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val look by viewModel.look.collectAsStateWithLifecycle()
    CentsibleTheme {
      androidx.compose.runtime.CompositionLocalProvider(app.centsible.core.designsystem.component.LocalCategoryLook provides look) {
        when (val s = state) {
            AppState.Starting -> LoadingState()
            AppState.NeedsPairing -> PairingRoute(deepLink = pairingLink)
            AppState.NeedsBudget -> BudgetPickerRoute()
            // Switching budgets rebuilds navigation and every screen's state.
            is AppState.Ready -> key(s.budget) { MainScaffold(offline, pending, viewModel.undoOffers, openScreen, onOpened) }
        }
      }
    }
}

@Composable
private fun MainScaffold(
    offline: Boolean,
    pending: Int,
    undoOffers: kotlinx.coroutines.flow.Flow<app.centsible.core.domain.Undoable>,
    openScreen: String?,
    onOpened: () -> Unit,
) {
    val nav = rememberNavController()
    androidx.compose.runtime.LaunchedEffect(openScreen) {
        when (openScreen) {
            OPEN_RECURRING -> nav.navigate(Routes.RECURRING) { launchSingleTop = true }
            OPEN_INSIGHTS -> nav.navigate(Routes.TRENDS) { launchSingleTop = true }
            // "review:<period>:<first day>" from a review notification.
            else -> if (openScreen?.startsWith("review:") == true) {
                val (_, period, date) = openScreen.split(':') + listOf("", "")
                nav.navigate(Routes.review(period, date)) { launchSingleTop = true }
            }
        }
        if (openScreen != null) onOpened()
    }
    val snackbar = androidx.compose.runtime.remember { androidx.compose.material3.SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(undoOffers) {
        undoOffers.collect { offer ->
            val result = snackbar.showSnackbar(offer.message, actionLabel = "Undo", duration = androidx.compose.material3.SnackbarDuration.Long)
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) scope.launch {
                runCatching { offer.undo() }.onFailure { e -> snackbar.showSnackbar("Couldn't undo: ${e.userMessage()}") }
            }
        }
    }
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val tab = Tab.entries.firstOrNull { it.route == current }
    val colors = CentsibleTheme.colors
    app.centsible.core.designsystem.component.AdaptiveNavScaffold(
        tabs = Tab.entries.map { app.centsible.core.designsystem.component.NavTab(it.route, it.label, it.icon) },
        selected = tab?.route,
        onSelect = { nav.goToTab(it) },
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
        topBar = { if (offline || pending > 0) OfflineBanner(offline, pending) },
        addButton = if (tab?.canAdd == true) {
            {
                FloatingActionButton(onClick = { nav.navigate(Routes.transaction(null)) }, containerColor = colors.accent, contentColor = colors.card) {
                    Icon(Icons.Rounded.Add, contentDescription = "Add transaction")
                }
            }
        } else null,
    ) { padding ->
        // Tabs fade through each other; drilling in slides along the shared X axis (and back out on pop).
        val reduced = app.centsible.core.designsystem.motion.reducedMotion
        val tabRoutes = androidx.compose.runtime.remember { Tab.entries.map { it.route }.toSet() }
        fun AnimatedContentTransitionScope<NavBackStackEntry>.tabSwitch() =
            initialState.destination.route in tabRoutes && targetState.destination.route in tabRoutes
        val axis = tween<IntOffset>(320, easing = app.centsible.core.designsystem.motion.Motion.Dial)
        NavHost(
            nav,
            startDestination = Tab.Dashboard.route,
            modifier = Modifier.padding(padding),
            enterTransition = {
                when {
                    reduced -> EnterTransition.None
                    tabSwitch() -> fadeIn(tween(220, delayMillis = 90)) + scaleIn(tween(220, delayMillis = 90), initialScale = 0.97f)
                    else -> slideInHorizontally(axis) { it / 8 } + fadeIn(tween(260, delayMillis = 40))
                }
            },
            exitTransition = {
                when {
                    reduced -> ExitTransition.None
                    tabSwitch() -> fadeOut(tween(90))
                    else -> slideOutHorizontally(axis) { -it / 8 } + fadeOut(tween(160))
                }
            },
            popEnterTransition = {
                when {
                    reduced -> EnterTransition.None
                    tabSwitch() -> fadeIn(tween(220, delayMillis = 90))
                    else -> slideInHorizontally(axis) { -it / 8 } + fadeIn(tween(260, delayMillis = 40))
                }
            },
            popExitTransition = {
                when {
                    reduced -> ExitTransition.None
                    tabSwitch() -> fadeOut(tween(90))
                    else -> slideOutHorizontally(axis) { it / 8 } + fadeOut(tween(160))
                }
            },
        ) {
            composable(Tab.Dashboard.route) { DashboardRoute(onNavigate = { nav.go(it) }) }
            composable(Tab.Accounts.route) { AccountsRoute(onOpenAccount = { nav.navigate(Routes.account(it)) }) }
            composable(Tab.Transactions.route) { TransactionsRoute(onOpen = { nav.navigate(Routes.transaction(it)) }, onOpenReview = { nav.navigate(Routes.REVIEW) }) }
            composable(Tab.Budget.route) { BudgetRoute(
                    onManageCategories = { nav.navigate(Routes.CATEGORIES) },
                    onOpenTransactions = { nav.go(it) },
                    onOpenAutomations = { c, m -> nav.navigate(if (c == null) Routes.automations(m) else Routes.automation(c, m)) },
                ) }
            composable(Tab.More.route) {
                MoreScreen(onOpen = { item ->
                    nav.navigate(
                        when (item) {
                            MoreItem.Goals -> Routes.GOALS
                            MoreItem.Calendar -> Routes.CALENDAR
                            MoreItem.Forecast -> Routes.FORECAST
                            MoreItem.Recurring -> Routes.RECURRING
                            MoreItem.Subscriptions -> Routes.SUBSCRIPTIONS
                            MoreItem.Trends -> Routes.TRENDS
                            MoreItem.NetWorth -> Routes.NET_WORTH
                            MoreItem.YearInReview -> Routes.review()
                            MoreItem.Reports -> Routes.REPORTS
                            MoreItem.Merchants -> Routes.MERCHANTS
                            MoreItem.Rules -> Routes.RULES
                            MoreItem.Tags -> Routes.TAGS
                            MoreItem.Appearance -> Routes.CATEGORIES
                            MoreItem.BankSync -> Routes.BANK_SYNC
                            MoreItem.Server -> Routes.SERVER
                            MoreItem.Settings -> Routes.SETTINGS
                        },
                    )
                })
            }
            composable(Routes.SETTINGS) { SettingsRoute(onOpenBankSync = { nav.navigate(Routes.BANK_SYNC) }) }
            composable(Routes.SERVER) { app.centsible.feature.settings.ServerRoute(onBack = { nav.popBackStack() }) }
            composable(Routes.BANK_SYNC) { app.centsible.feature.settings.BankSyncRoute(onBack = { nav.popBackStack() }) }
            composable(Routes.RECURRING) { RecurringRoute(onBack = { nav.popBackStack() }, onFind = { nav.navigate(Routes.SUBSCRIPTIONS) { launchSingleTop = true } }) }
            composable(Routes.SUBSCRIPTIONS) { app.centsible.feature.planning.SubscriptionsRoute(onBack = { nav.popBackStack() }, onOpenTransactions = { nav.go(it) }) }
            composable(Routes.TRENDS) { app.centsible.feature.reports.TrendsRoute(onBack = { nav.popBackStack() }, onOpenTransactions = { nav.go(it) }) }
            composable(Routes.NET_WORTH) { app.centsible.feature.reports.NetWorthRoute(onBack = { nav.popBackStack() }) }
            composable(
                Routes.YEAR_IN_REVIEW,
                arguments = listOf(
                    navArgument("period") { type = NavType.StringType; defaultValue = "" },
                    navArgument("date") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                app.centsible.feature.reports.YearInReviewRoute(
                    onClose = { nav.popBackStack() },
                    period = entry.arguments?.getString("period")?.ifEmpty { null },
                    date = entry.arguments?.getString("date")?.ifEmpty { null },
                    onNavigate = { nav.go(it) },
                )
            }
            composable(Routes.GOALS) { app.centsible.feature.planning.GoalsRoute(onBack = { nav.popBackStack() }) }
            composable(Routes.CALENDAR) {
                app.centsible.feature.planning.BillCalendarRoute(
                    onBack = { nav.popBackStack() },
                    onOpenRecurring = { nav.navigate(Routes.RECURRING) { launchSingleTop = true } },
                    onOpenForecast = { nav.navigate(Routes.FORECAST) { launchSingleTop = true } },
                )
            }
            composable(Routes.FORECAST) {
                app.centsible.feature.planning.ForecastRoute(onBack = { nav.popBackStack() }, onOpenCalendar = { nav.navigate(Routes.CALENDAR) { launchSingleTop = true } })
            }
            composable(Routes.REPORTS) { ReportsRoute(onBack = { nav.popBackStack() }, onOpenTransactions = { nav.go(it) }) }
            composable(Routes.MERCHANTS) { MerchantsRoute(onBack = { nav.popBackStack() }, onOpenTransactions = { nav.go(it) }) }
            composable(Routes.RULES) { RulesRoute(onBack = { nav.popBackStack() }, onOpenRule = { nav.navigate(Routes.rule(it)) }) }
            composable(Routes.TAGS) { TagsRoute(onBack = { nav.popBackStack() }, onSearch = { nav.navigate(Routes.search(it)) }) }
            composable(
                Routes.RULE,
                arguments = listOf("id", "payee", "category").map { n -> navArgument(n) { type = NavType.StringType; defaultValue = "" } },
            ) {
                app.centsible.feature.planning.RuleEditorRoute(onDone = { nav.popBackStack() })
            }
            composable(Routes.AUTOMATIONS, arguments = listOf(navArgument("month") { type = NavType.StringType; defaultValue = "" })) {
                app.centsible.feature.budget.AutomationsRoute(onBack = { nav.popBackStack() }, onOpen = { c, m -> nav.navigate(Routes.automation(c, m)) })
            }
            composable(
                Routes.AUTOMATION,
                arguments = listOf(navArgument("category") { type = NavType.StringType }, navArgument("month") { type = NavType.StringType; defaultValue = "" }),
            ) {
                app.centsible.feature.budget.AutomationEditorRoute(onDone = { nav.popBackStack() })
            }
            composable(Routes.REVIEW) {
                app.centsible.feature.transactions.ReviewRoute(onBack = { nav.popBackStack() }, onOpen = { nav.navigate(Routes.transaction(it)) }, onMakeRule = { p, c -> nav.navigate(Routes.rule(null, p.raw, c?.raw)) })
            }
            composable(
                Routes.TRANSACTIONS_FOR,
                arguments = listOf(
                    TransactionsViewModel.ARG_TITLE, TransactionsViewModel.ARG_CATEGORY, TransactionsViewModel.ARG_GROUP,
                    TransactionsViewModel.ARG_PAYEE, TransactionsViewModel.ARG_SINCE, TransactionsViewModel.ARG_UNTIL,
                ).map { name -> navArgument(name) { type = NavType.StringType; defaultValue = "" } },
            ) {
                TransactionsRoute(
                    onOpen = { nav.navigate(Routes.transaction(it)) },
                    onOpenReview = { nav.navigate(Routes.REVIEW) },
                    onBack = { nav.popBackStack() },
                )
            }
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
            ) {
                TransactionEditorRoute(
                    onClose = { nav.popBackStack() },
                    onMakeRule = { p, c -> nav.navigate(Routes.rule(null, p.raw, c?.raw)) },
                )
            }
            composable(Routes.CATEGORIES) { CategoryManagerRoute(onBack = { nav.popBackStack() }) }
        }
    }
}

/** Shown while reads come from the offline cache, or changes are waiting to sync. */
@Composable
private fun OfflineBanner(offline: Boolean, pending: Int) {
    val colors = CentsibleTheme.colors
    val waiting = if (pending == 1) "1 change waiting to sync" else "$pending changes waiting to sync"
    Text(
        when {
            offline && pending > 0 -> "Offline · $waiting"
            offline -> "Offline · showing saved data"
            else -> "Syncing · $waiting"
        },
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
    Destination.NetWorth -> navigate(Routes.NET_WORTH)
    Destination.Trends -> navigate(Routes.TRENDS)
    Destination.YearInReview -> navigate(Routes.review())
    Destination.Server -> navigate(Routes.SERVER)
    Destination.Review -> navigate(Routes.REVIEW)
    is Destination.TransactionsFor -> navigate(Routes.transactionsFor(d))
    is Destination.Account -> navigate(Routes.account(d.id))
    is Destination.Transaction -> navigate(Routes.transaction(d.id, d.account))
}
