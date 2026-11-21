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
    /** The screenshots were recorded in October 2026; the screen's "today" is pinned there. */
    private val day = java.time.LocalDate.parse("2026-10-01")

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
        compose.setContent { CentsibleTheme(darkTheme = false) { MortgageScreen(MortgageUiState(Loadable.Ready(listOf(mortgage.copy(schedule = emptyList()))), canEdit = true), MortgageActions(), today = day) } }
        compose.onRoot().captureRoboImage("screenshots/mortgage_list.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Test fun mortgage_detail_dark() {
        compose.setContent { CentsibleTheme(darkTheme = true) { MortgageScreen(MortgageUiState(Loadable.Ready(listOf(mortgage)), detail = mortgage, canEdit = true), MortgageActions(), today = day) } }
        compose.onRoot().captureRoboImage("screenshots/mortgage_detail_dark.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Test fun mortgage_setup() {
        val accounts = listOf(AccountId("a") to "Joint Checking", AccountId("b") to "High-Yield Savings")
        val payees = listOf(PayeeId("p") to "Lakeside Mortgage", PayeeId("q") to "City Power")
        compose.setContent { CentsibleTheme(darkTheme = false) { MortgageScreen(MortgageUiState(Loadable.Ready(emptyList()), accounts = accounts, payees = payees, canEdit = true, creating = true), MortgageActions(), today = day) } }
        compose.waitForIdle()
        captureScreenRoboImage("screenshots/mortgage_setup.png")
    }

    private val accountDetails = listOf(
        app.centsible.core.model.Account(AccountId("a"), "Joint Checking", false, false, Money(832_166), syncSource = "simpleFin"),
        app.centsible.core.model.Account(AccountId("l"), "House mortgage", true, false, Money(-now.balance.minor)),
        app.centsible.core.model.Account(AccountId("h"), "House home", true, false, Money(42_000_000)),
        app.centsible.core.model.Account(AccountId("wf"), "Wells Fargo Home Mortgage", true, false, Money(-28_731_406), syncSource = "simpleFin", type = app.centsible.core.model.AccountType.Mortgage),
    )

    @Test fun mortgage_link_bank_account() {
        compose.setContent {
            CentsibleTheme(darkTheme = false) {
                MortgageScreen(MortgageUiState(Loadable.Ready(listOf(mortgage.copy(schedule = emptyList()))), canEdit = true, accountDetails = accountDetails, linking = mortgage), MortgageActions(), today = day)
            }
        }
        compose.waitForIdle()
        captureScreenRoboImage("screenshots/mortgage_link_bank_account.png")
    }

    @Test fun mortgage_linked_to_bank() {
        val linked = mortgage.copy(schedule = emptyList(), loanAccountId = AccountId("wf"), loanSynced = true, unrecorded = 0, balance = Money(28_731_406))
        compose.setContent { CentsibleTheme(darkTheme = false) { MortgageScreen(MortgageUiState(Loadable.Ready(listOf(linked)), canEdit = true, accountDetails = accountDetails), MortgageActions(), today = day) } }
        compose.onRoot().captureRoboImage("screenshots/mortgage_linked_to_bank.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }
}
