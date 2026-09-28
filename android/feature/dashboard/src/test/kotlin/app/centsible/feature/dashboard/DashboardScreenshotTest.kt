package app.centsible.feature.dashboard

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
    )

    @Test fun dashboard_light() {
        compose.setContent {
            CentsibleTheme(darkTheme = false) {
                DashboardScreen(
                    DashboardUiState("Jo", Loadable.Ready(context), listOf(NetWorthWidget(), BudgetSummaryWidget(), RecentTransactionsWidget())),
                    onNavigate = {}, onRetry = {}, now = LocalTime.of(9, 0),
                )
            }
        }
        compose.onRoot().captureRoboImage("screenshots/dashboard_light.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }
}
