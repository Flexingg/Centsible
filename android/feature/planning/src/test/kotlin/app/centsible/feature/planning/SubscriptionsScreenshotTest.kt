package app.centsible.feature.planning

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.AccountId
import app.centsible.core.model.Frequency
import app.centsible.core.model.Money
import app.centsible.core.model.PayeeId
import app.centsible.core.model.PriceChange
import app.centsible.core.model.Recurrence
import app.centsible.core.model.RecurringCandidate
import app.centsible.core.model.Subscriptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1200dp-xxhdpi")
class SubscriptionsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun c(name: String, amount: Long, freq: Frequency = Frequency.Monthly, interval: Int = 1, approx: Boolean = false, account: String = "Visa Signature") = RecurringCandidate(
        PayeeId(name), name, AccountId("a"), account, Money(amount), approx, Recurrence(freq, interval, "2026-06-12"), "2026-09-12",
        Money(amount * when (freq) { Frequency.Weekly -> 52 / interval; Frequency.Yearly -> 1; else -> 12 / interval }), amount > 0,
    )

    private val subs = Subscriptions(
        listOf(
            c("Netflix", -1_599), c("Spotify", -1_199), c("Planet Fitness", -2_499, account = "Joint Checking"),
            c("City Water", -6_830, approx = true, account = "Joint Checking"), c("Employer Payroll", 310_000, Frequency.Weekly, 2, account = "Joint Checking"),
        ),
        listOf(PriceChange("s1", "YouTube Premium", Money(-1_399), Money(-1_599), 14, "2026-09-03")),
    )

    @Test fun subscriptions() {
        compose.setContent { CentsibleTheme(darkTheme = false) { SubscriptionsScreen(SubscriptionsUiState(Loadable.Ready(subs), canEdit = true)) } }
        compose.onRoot().captureRoboImage("screenshots/subscriptions.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }
}
