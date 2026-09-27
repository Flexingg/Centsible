package app.canopy.feature.accounts

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.canopy.core.designsystem.component.Loadable
import app.canopy.core.designsystem.theme.CanopyTheme
import app.canopy.core.testing.SampleHousehold
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
        compose.setContent { CanopyTheme(darkTheme = false) { AccountsScreen(AccountsUiState(Loadable.Ready(AccountsSummary.from(SampleHousehold.accounts)), canWrite = true), onRetry = {}) } }
        compose.onRoot().captureRoboImage("screenshots/accounts_light.png")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class AccountDetailScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun account_detail_light() {
        val visa = SampleHousehold.accounts.first { it.name == "Visa Signature" }
        val detail = AccountDetail(
            account = visa,
            transactions = SampleHousehold.transactions.filter { it.accountId == visa.id },
            nextCursor = null,
            otherAccounts = SampleHousehold.accounts.filter { it.id != visa.id },
            categoryNames = SampleHousehold.budgetMonth.groups.flatMap { it.categories }.associate { it.id.raw to it.name },
        )
        compose.setContent { CanopyTheme(darkTheme = false) { AccountDetailScreen(AccountDetailUiState(Loadable.Ready(detail), canWrite = true), onBack = {}, onRetry = {}) } }
        compose.onRoot().captureRoboImage("screenshots/account_detail_light.png")
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
        compose.setContent { CanopyTheme(darkTheme = false) { AccountDetailScreen(actionsState, onBack = {}, onRetry = {}) } }
        compose.onRoot().captureRoboImage("screenshots/account_detail_actions.png")
    }

    @Test fun account_import_preview() {
        val preview = kotlinx.coroutines.runBlocking {
            app.canopy.core.testing.FakeAccountServices().previewImport(
                app.canopy.core.model.BudgetId("b"), detail().account.id, "statement.ofx", ByteArray(0), app.canopy.core.model.ImportOptions(),
            )
        }
        val state = actionsState.copy(importing = PendingImport("visa-september.ofx", ByteArray(0), preview = preview, loading = false))
        compose.setContent { CanopyTheme(darkTheme = false) { AccountDetailScreen(state, onBack = {}, onRetry = {}) } }
        compose.waitForIdle()
        captureScreenRoboImage("screenshots/account_import_preview.png")
    }

    @Test fun account_reconcile() {
        val status = app.canopy.core.model.ReconcileStatus(app.canopy.core.model.Money(-58_234), app.canopy.core.model.Money(-3_500), app.canopy.core.model.Money(-61_734))
        val state = actionsState.copy(reconcile = ReconcileState(status = status, result = app.canopy.core.model.ReconcileResult(false, app.canopy.core.model.Money(-2_000), null, 0)))
        compose.mainClock.autoAdvance = false // the text cursor blinks forever, so idle never comes
        compose.setContent { CanopyTheme(darkTheme = false) { AccountDetailScreen(state, onBack = {}, onRetry = {}) } }
        compose.mainClock.advanceTimeBy(1_000)
        captureScreenRoboImage("screenshots/account_reconcile.png")
    }
}
