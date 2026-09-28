package app.centsible.feature.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.Account
import app.centsible.core.model.AccountId
import app.centsible.core.model.AccountSyncResult
import app.centsible.core.model.Backfill
import app.centsible.core.model.BudgetId
import app.centsible.core.model.BankSyncOverview
import app.centsible.core.model.BankSyncSettings
import app.centsible.core.model.ExternalAccount
import app.centsible.core.model.FieldMapping
import app.centsible.core.model.Money
import app.centsible.core.model.SyncRunResult
import app.centsible.core.model.SyncSchedule
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
class BankSyncScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val intervals = listOf(0, 2, 4, 6, 12, 24)
    private val notConnected = BankSyncUiState(
        overview = Loadable.Ready(BankSyncOverview(false, 0, 24, SyncSchedule(0, null, null, null), intervals)),
        isOwner = true, canEdit = true,
    )
    private val checking = Account(AccountId("acc-checking"), "Joint Checking", false, false, Money(832166), "simpleFin", "2026-09-28T08:00:00Z", "ok")
    private val visa = Account(AccountId("acc-visa"), "Visa Signature", false, false, Money(-61734), "simpleFin", "2026-09-28T08:00:00Z", "reauth-required")
    private val savings = Account(AccountId("acc-savings"), "High-Yield Savings", false, false, Money(2450000))
    private val external = listOf(
        ExternalAccount("SF-1", "Everyday Checking", "Example Bank", Money(832166), checking.id, checking.name),
        ExternalAccount("SF-2", "Signature Visa", "Card Co", Money(-61734), visa.id, visa.name),
        ExternalAccount("SF-3", "Savings Plus", "Example Bank", Money(2450000), null, null),
    )
    private val connected = notConnected.copy(
        overview = Loadable.Ready(
            BankSyncOverview(
                true, 5, 24,
                SyncSchedule(6, "2026-09-28T08:00:00Z", "2026-09-28T14:00:00Z", SyncRunResult(4, 2, listOf("Visa Signature: The bank connection needs you to sign in again at SimpleFIN."), null)),
                intervals,
            ),
        ),
        external = Loadable.Ready(external),
        localAccounts = listOf(checking, visa, savings),
        syncResults = listOf(
            AccountSyncResult(checking.id, "Joint Checking", 3, null, "ok"),
            AccountSyncResult(visa.id, "Visa Signature", 0, "Failed syncing account \"Visa Signature.\"", "reauth-required"),
        ),
    )

    private fun render(state: BankSyncUiState) = compose.setContent { CentsibleTheme(darkTheme = false) { BankSyncScreen(state, BankSyncActions()) } }

    @Test fun bank_sync_not_connected() {
        render(notConnected)
        compose.onRoot().captureRoboImage("screenshots/bank_sync_setup.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Test fun bank_sync_connected() {
        render(connected)
        compose.onRoot().captureRoboImage("screenshots/bank_sync_connected.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Test fun bank_sync_link_sheet() {
        render(connected.copy(linking = external[2]))
        compose.waitForIdle()
        captureScreenRoboImage("screenshots/bank_sync_link.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Test fun bank_sync_account_options() {
        val settings = BankSyncSettings(importPending = false, payment = FieldMapping(payee = "notes", notes = "payeeName"))
        render(connected.copy(options = AccountOptions(external[1], settings)))
        compose.waitForIdle()
        captureScreenRoboImage("screenshots/bank_sync_options.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    private fun withOverview(state: BankSyncUiState, change: (BankSyncOverview) -> BankSyncOverview) =
        state.copy(overview = Loadable.Ready(change((state.overview as Loadable.Ready).value)), syncResults = null)

    // Tall, so the Older history card at the bottom is on screen.
    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    @Test fun bank_sync_history_importing() {
        render(
            withOverview(connected) {
                it.copy(
                    historyAccess = true,
                    backfill = Backfill(
                        BudgetId("b"), listOf(checking.id, visa.id), "2021-09-28", Backfill.Status.Waiting, "2024-02-11", 11, 22, 1843,
                        "Paused to stay within SimpleFIN's daily limit. It carries on by itself as the limit frees up.",
                    ),
                )
            },
        )
        compose.onRoot().captureRoboImage("screenshots/bank_sync_history_importing.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    @Test fun bank_sync_history_start() {
        render(withOverview(connected) { it.copy(historyAccess = true) }.copy(historyYears = 5))
        compose.onRoot().captureRoboImage("screenshots/bank_sync_history_start.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    @Test fun bank_sync_history_needs_reconnect() {
        render(withOverview(connected) { it.copy(historyAccess = false) })
        compose.onRoot().captureRoboImage("screenshots/bank_sync_history_reconnect.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Test fun bank_sync_bridge_outdated() {
        render(notConnected.copy(overview = Loadable.Failed(app.centsible.core.domain.BridgeException.OUTDATED)))
        compose.onRoot().captureRoboImage("screenshots/bank_sync_bridge_outdated.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }
}
