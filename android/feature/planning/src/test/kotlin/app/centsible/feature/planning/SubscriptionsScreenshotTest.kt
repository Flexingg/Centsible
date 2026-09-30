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
import app.centsible.core.model.RecurringPattern
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

    private fun occ(vararg xs: Pair<String, Long>) = xs.map { (d, a) -> RecurringPattern.Occurrence(d, Money(a)) }

    private val patterns = Subscriptions(
        emptyList(),
        emptyList(),
        listOf(
            RecurringPattern(
                PayeeId("power"), "City Power", AccountId("a"), "Joint Checking", income = false, days = listOf(12), firstWeekday = false, weekendAfter = true,
                description = "Due around the 12th of each month",
                occurrences = occ("2026-03-12" to -9000, "2026-04-13" to -9500, "2026-05-12" to -12000, "2026-06-11" to -16000, "2026-07-13" to -18000, "2026-08-12" to -17500, "2026-09-12" to -13000),
                min = Money(-9500), max = Money(-18000), average3 = Money(-16167), varies = true,
                next = RecurringPattern.Projection("2026-10-12", Money(-8500), fromLastYear = true),
            ),
            RecurringPattern(
                PayeeId("job"), "Acme Payroll", AccountId("a"), "Joint Checking", income = true, days = listOf(1, 15), firstWeekday = true, weekendAfter = true,
                description = "Paid the first weekday and around the 15th of each month",
                occurrences = occ("2026-07-01" to 410000, "2026-07-15" to 395000, "2026-08-03" to 431000, "2026-08-14" to 388000, "2026-09-01" to 402500, "2026-09-15" to 399000),
                min = Money(388000), max = Money(431000), average3 = Money(396833), varies = true,
                next = RecurringPattern.Projection("2026-10-01", Money(410500), fromLastYear = false),
            ),
        ),
    )

    @Test fun subscriptions_patterns() {
        compose.setContent { CentsibleTheme(darkTheme = false) { SubscriptionsScreen(SubscriptionsUiState(Loadable.Ready(patterns), canEdit = true)) } }
        compose.onRoot().captureRoboImage("screenshots/subscriptions_patterns.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Test fun subscriptions() {
        compose.setContent { CentsibleTheme(darkTheme = false) { SubscriptionsScreen(SubscriptionsUiState(Loadable.Ready(subs), canEdit = true)) } }
        compose.onRoot().captureRoboImage("screenshots/subscriptions.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }
}
