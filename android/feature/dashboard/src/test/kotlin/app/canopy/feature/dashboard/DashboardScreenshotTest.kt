package app.canopy.feature.dashboard

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.canopy.core.designsystem.component.Loadable
import app.canopy.core.designsystem.theme.CanopyTheme
import app.canopy.core.testing.SampleHousehold
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import app.canopy.core.extensions.DashboardContext
import app.canopy.core.model.Member
import app.canopy.core.model.MemberId
import app.canopy.core.model.Role
import app.canopy.core.testing.FakeBudgetEngine
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
            CanopyTheme(darkTheme = false) {
                DashboardScreen(
                    DashboardUiState("Jo", Loadable.Ready(context), listOf(NetWorthWidget(), BudgetSummaryWidget(), RecentTransactionsWidget())),
                    onNavigate = {}, onRetry = {}, now = LocalTime.of(9, 0),
                )
            }
        }
        compose.onRoot().captureRoboImage("screenshots/dashboard_light.png")
    }
}
