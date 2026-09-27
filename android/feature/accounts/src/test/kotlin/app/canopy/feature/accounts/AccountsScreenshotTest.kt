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
}
