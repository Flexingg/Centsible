package app.centsible.feature.transactions

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.Money
import app.centsible.core.model.TransactionId
import app.centsible.core.model.TransferPair
import app.centsible.core.testing.SampleHousehold
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
class TransfersScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val checking = SampleHousehold.accounts[0]
    private val card = SampleHousehold.accounts.first { it.id != checking.id }
    private val base = SampleHousehold.transactions.first { !it.isTransfer && !it.isParent }

    private fun pair(n: Int, minor: Long, out: String, into: String, from: String, to: String, days: Int, sure: Boolean) = TransferPair(
        base.copy(id = TransactionId("o$n"), accountId = checking.id, amount = Money(-minor), payeeName = out, date = from, categoryId = null),
        base.copy(id = TransactionId("i$n"), accountId = card.id, amount = Money(minor), payeeName = into, date = to, categoryId = null),
        days,
        sure,
    )

    @Test fun transfers_matches() {
        val pairs = listOf(
            pair(1, 123456, "CARD AUTOPAY", "PAYMENT THANK YOU", "2026-09-24", "2026-09-26", 2, true),
            pair(2, 50000, "Online transfer", "Payment received", "2026-09-12", "2026-09-12", 0, true),
            pair(3, 1350, "Transfer", "Credit", "2026-09-03", "2026-09-05", 2, false),
        )
        val names = SampleHousehold.accounts.associate { it.id.raw to it.name }
        compose.setContent {
            CentsibleTheme(darkTheme = false) { TransfersScreen(TransfersUiState(Loadable.Ready(TransfersData(pairs, auto = false, accountNames = names))), TransfersActions()) }
        }
        compose.onRoot().captureRoboImage("screenshots/transfers_matches.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }
}
