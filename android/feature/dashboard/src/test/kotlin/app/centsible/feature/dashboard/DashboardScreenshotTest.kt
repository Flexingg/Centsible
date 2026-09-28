package app.centsible.feature.dashboard

import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.testing.SampleHousehold
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import app.centsible.core.extensions.DashboardContext
import app.centsible.core.model.Member
import app.centsible.core.model.MemberId
import app.centsible.core.model.Role
import app.centsible.core.testing.FakeBudgetEngine
import java.time.LocalTime
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class DashboardScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val context = DashboardContext(
        budget = SampleHousehold.budget.id,
        member = Member(MemberId("m"), "Jo", Role.Owner, false, emptyList()),
        capabilities = FakeBudgetEngine().capabilities,
        month = SampleHousehold.budgetMonth,
        accounts = SampleHousehold.accounts,
        recentTransactions = SampleHousehold.transactions,
        categoryNames = SampleHousehold.budgetMonth.groups.flatMap { it.categories }.associate { it.id.raw to it.name },
        navigate = {},
        today = java.time.LocalDate.of(SampleHousehold.budgetMonth.month.year, SampleHousehold.budgetMonth.month.month, 18),
    )

    @Test fun dashboard_light() {
        compose.setContent {
            CentsibleTheme(darkTheme = false) {
                DashboardScreen(
                    DashboardUiState("Jo", Loadable.Ready(context), listOf(BudgetDialWidget(), NetWorthWidget(), BudgetSummaryWidget(), RecentTransactionsWidget())),
                    onNavigate = {}, onRetry = {}, now = LocalTime.of(9, 0),
                )
            }
        }
        compose.onRoot().captureRoboImage("screenshots/dashboard_light.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    /** A 10" tablet in landscape: navigation rail, content kept to a readable width. */
    @Test
    @Config(sdk = [35], qualifiers = "w1280dp-h800dp-xhdpi")
    fun dashboard_tablet() {
        compose.setContent {
            CentsibleTheme(darkTheme = false) {
                app.centsible.core.designsystem.component.AdaptiveNavScaffold(
                    tabs = listOf(
                        app.centsible.core.designsystem.component.NavTab("home", "Home", androidx.compose.material.icons.Icons.Rounded.Home),
                        app.centsible.core.designsystem.component.NavTab("accounts", "Accounts", androidx.compose.material.icons.Icons.Rounded.AccountBalance),
                        app.centsible.core.designsystem.component.NavTab("transactions", "Transactions", androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ReceiptLong),
                        app.centsible.core.designsystem.component.NavTab("budget", "Budget", androidx.compose.material.icons.Icons.Rounded.PieChart),
                        app.centsible.core.designsystem.component.NavTab("more", "More", androidx.compose.material.icons.Icons.Rounded.Menu),
                    ),
                    selected = "home",
                    onSelect = {},
                    addButton = {
                        androidx.compose.material3.FloatingActionButton(onClick = {}) {
                            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Add, contentDescription = "Add transaction")
                        }
                    },
                ) {
                    DashboardScreen(
                        DashboardUiState("Jo", Loadable.Ready(context), listOf(BudgetDialWidget(), NetWorthWidget(), BudgetSummaryWidget(), RecentTransactionsWidget())),
                        onNavigate = {}, onRetry = {}, now = LocalTime.of(9, 0),
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage("screenshots/dashboard_tablet.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Test fun budget_dial_dark() {
        compose.setContent {
            CentsibleTheme(darkTheme = true) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.padding(16.dp)) { BudgetDialWidget().Content(context) }
            }
        }
        compose.onRoot().captureRoboImage("screenshots/budget_dial_dark.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }
}
