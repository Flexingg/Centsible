package app.centsible.feature.planning

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.AccountId
import app.centsible.core.model.Money
import app.centsible.core.model.Mortgage
import app.centsible.core.model.PayeeId
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class MortgageScreenshotTest {
    @get:Rule val compose = createComposeRule()

    /** $300,000 at 6% over 30 years from 2023, $200 extra a month. */
    private val schedule: List<Mortgage.Row> = run {
        val rows = mutableListOf<Mortgage.Row>()
        var owed = 30_000_000L
        var n = 1
        var date = java.time.LocalDate.of(2023, 1, 1)
        while (owed > 0 && n <= 360) {
            val interest = Math.round(owed * 0.005)
            val principal = minOf(owed, 179_865 - interest)
            val extra = minOf(20_000L, owed - principal)
            owed -= principal + extra
            rows += Mortgage.Row(n, date.toString(), Money(interest + principal), Money(interest), Money(principal), Money(extra), Money(owed), if (date.isBefore(java.time.LocalDate.of(2026, 9, 15))) date.toString() else null)
            n++
            date = date.plusMonths(1)
        }
        rows
    }
    private val now = schedule.last { it.date <= "2026-09-30" }

    private val mortgage = Mortgage(
        id = "m1", name = "House mortgage", principal = Money(30_000_000), rate = 6.0, termMonths = 360, firstPayment = "2023-01-01",
        escrow = Money(30_000), extra = Money(20_000), payeeId = PayeeId("p"), paymentAccountId = AccountId("a"), loanAccountId = AccountId("l"),
        homeAccountId = AccountId("h"), loanSynced = false, monthlyPayment = Money(179_865), monthlyTotal = Money(229_865),
        balance = now.balance, scheduledBalance = now.balance, aheadBy = Money.Zero, paymentsMade = now.n, paymentsLeft = schedule.size - now.n,
        payoffDate = schedule.last().date, originalPayoffDate = "2052-12-01", interestPaid = Money(schedule.filter { it.n <= now.n }.sumOf { it.interest.minor }),
        interestLeft = Money(schedule.filter { it.n > now.n }.sumOf { it.interest.minor }), interestSaved = Money(9_412_300),
        homeValue = Money(42_000_000), equity = Money(42_000_000 - now.balance.minor), paymentsFound = now.n, unrecorded = 1, schedule = schedule,
    )

    @Test fun mortgage_list() {
        compose.setContent { CentsibleTheme(darkTheme = false) { MortgageScreen(MortgageUiState(Loadable.Ready(listOf(mortgage.copy(schedule = emptyList()))), canEdit = true), MortgageActions()) } }
        compose.onRoot().captureRoboImage("screenshots/mortgage_list.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Test fun mortgage_detail_dark() {
        compose.setContent { CentsibleTheme(darkTheme = true) { MortgageScreen(MortgageUiState(Loadable.Ready(listOf(mortgage)), detail = mortgage, canEdit = true), MortgageActions()) } }
        compose.onRoot().captureRoboImage("screenshots/mortgage_detail_dark.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Test fun mortgage_setup() {
        val accounts = listOf(AccountId("a") to "Joint Checking", AccountId("b") to "High-Yield Savings")
        val payees = listOf(PayeeId("p") to "Lakeside Mortgage", PayeeId("q") to "City Power")
        compose.setContent { CentsibleTheme(darkTheme = false) { MortgageScreen(MortgageUiState(Loadable.Ready(emptyList()), accounts = accounts, payees = payees, canEdit = true, creating = true), MortgageActions()) } }
        compose.waitForIdle()
        captureScreenRoboImage("screenshots/mortgage_setup.png")
    }
}
