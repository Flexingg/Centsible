package app.centsible.feature.transactions

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.testing.SampleHousehold
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class TransactionsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun transactions_light() {
        val data = TransactionsData(
            items = SampleHousehold.transactions,
            categoryNames = SampleHousehold.budgetMonth.groups.flatMap { it.categories }.associate { it.id.raw to it.name },
            accountNames = SampleHousehold.accounts.associate { it.id.raw to it.name },
            nextCursor = "next",
        )
        compose.setContent { CentsibleTheme(darkTheme = false) { TransactionsScreen(TransactionsUiState(data = Loadable.Ready(data)), onRetry = {}, onLoadMore = {}, today = LocalDate.of(2026, 9, 26)) } }
        compose.onRoot().captureRoboImage("screenshots/transactions_light.png")
    }
}
