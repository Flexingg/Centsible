package app.centsible.feature.accounts

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.testing.SampleHousehold
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
class AccountsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun accounts_light() {
        compose.setContent { CentsibleTheme(darkTheme = false) { AccountsScreen(AccountsUiState(Loadable.Ready(AccountsSummary.from(SampleHousehold.accounts)), canWrite = true), onRetry = {}) } }
        compose.onRoot().captureRoboImage("screenshots/accounts_light.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Test fun accounts_large_text() {
        compose.setContent { CentsibleTheme(darkTheme = false) { app.centsible.core.uitesting.LargeText { AccountsScreen(AccountsUiState(Loadable.Ready(AccountsSummary.from(SampleHousehold.accounts)), canWrite = true), onRetry = {}) } } }
        compose.onRoot().captureRoboImage("screenshots/accounts_large_text.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class AccountDetailScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun account_detail_light() {
        val visa = SampleHousehold.accounts.first { it.name == "Visa Signature" }
        val txs = SampleHousehold.transactions.filter { it.accountId == visa.id }
        // Running balance, newest first: today's balance, then less each transaction going back.
        var running = visa.balance.minor
        val balances = txs.map { t -> app.centsible.core.model.Money(running).also { running -= t.amount.minor } }
        val detail = AccountDetail(
            account = visa,
            transactions = txs,
            nextCursor = null,
            otherAccounts = SampleHousehold.accounts.filter { it.id != visa.id },
            categoryNames = SampleHousehold.budgetMonth.groups.flatMap { it.categories }.associate { it.id.raw to it.name },
            balances = balances,
        )
        compose.setContent { CentsibleTheme(darkTheme = false) { AccountDetailScreen(AccountDetailUiState(Loadable.Ready(detail), canWrite = true), onBack = {}, onRetry = {}) } }
        compose.onRoot().captureRoboImage("screenshots/account_detail_light.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    private fun detail(): AccountDetail {
        val visa = SampleHousehold.accounts.first { it.name == "Visa Signature" }.copy(syncSource = "simpleFin", lastSync = "2026-09-26T14:02:00Z")
        return AccountDetail(
            account = visa,
            transactions = SampleHousehold.transactions.filter { it.accountId == visa.id },
            nextCursor = null,
            otherAccounts = SampleHousehold.accounts.filter { it.id != visa.id },
            categoryNames = SampleHousehold.budgetMonth.groups.flatMap { it.categories }.associate { it.id.raw to it.name },
        )
    }

    private val actionsState get() = AccountDetailUiState(Loadable.Ready(detail()), canWrite = true, canSync = true, canImport = true, canReconcile = true)

    @Test fun account_detail_actions() {
        compose.setContent { CentsibleTheme(darkTheme = false) { AccountDetailScreen(actionsState, onBack = {}, onRetry = {}) } }
        compose.onRoot().captureRoboImage("screenshots/account_detail_actions.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Test fun account_import_preview() {
        val preview = kotlinx.coroutines.runBlocking {
            app.centsible.core.testing.FakeAccountServices().previewImport(
                app.centsible.core.model.BudgetId("b"), detail().account.id, "statement.ofx", ByteArray(0), app.centsible.core.model.ImportOptions(),
            )
        }
        val state = actionsState.copy(importing = PendingImport("visa-september.ofx", ByteArray(0), preview = preview, loading = false))
        compose.setContent { CentsibleTheme(darkTheme = false) { AccountDetailScreen(state, onBack = {}, onRetry = {}) } }
        compose.waitForIdle()
        captureScreenRoboImage("screenshots/account_import_preview.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Test fun account_reconcile() {
        val status = app.centsible.core.model.ReconcileStatus(app.centsible.core.model.Money(-58_234), app.centsible.core.model.Money(-3_500), app.centsible.core.model.Money(-61_734))
        val state = actionsState.copy(reconcile = ReconcileState(status = status, result = app.centsible.core.model.ReconcileResult(false, app.centsible.core.model.Money(-2_000), null, 0)))
        compose.mainClock.autoAdvance = false // the text cursor blinks forever, so idle never comes
        compose.setContent { CentsibleTheme(darkTheme = false) { AccountDetailScreen(state, onBack = {}, onRetry = {}) } }
        compose.mainClock.advanceTimeBy(1_000)
        captureScreenRoboImage("screenshots/account_reconcile.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }
}
