package app.centsible.feature.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.AccountId
import app.centsible.core.model.Money
import app.centsible.core.model.PlaidAccount
import app.centsible.core.model.PlaidItem
import app.centsible.core.model.PlaidStatus
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class PlaidScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val status = PlaidStatus(
        configured = true, clientId = "fakeclientidfortests0001", environment = "production", androidPackageName = "app.centsible.budget",
        items = listOf(
            PlaidItem("item_wf", "Wells Fargo", PlaidItem.Status.Ok, null, 2),
            PlaidItem("item_disc", "Discover", PlaidItem.Status.Reauth, "The bank wants you to sign in again. Reconnect the bank.", 1),
        ),
    )
    private val accounts = listOf(
        PlaidAccount("p1", "item_wf", "Wells Fargo", "Everyday Checking", false, "6975", Money(115_918), AccountId("acc-checking"), "Joint Checking"),
        PlaidAccount("p2", "item_wf", "Wells Fargo", "Way2Save Savings", false, "1111", Money(2_450_000), AccountId("acc-savings"), "High-Yield Savings"),
        PlaidAccount("p3", "item_disc", "Discover", "Discover it Card", true, "4242", Money(-24_504), AccountId("acc-visa"), "Visa Signature"),
    )

    @Test fun plaid_connected_with_history() {
        val state = PlaidUiState(status = Loadable.Ready(status), isOwner = true, canEdit = true, accounts = Loadable.Ready(accounts), historyMonths = 12)
        compose.setContent { CentsibleTheme(darkTheme = false) { PlaidScreen(state, PlaidActions()) } }
        compose.onRoot().captureRoboImage("screenshots/plaid_connected.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }
}
