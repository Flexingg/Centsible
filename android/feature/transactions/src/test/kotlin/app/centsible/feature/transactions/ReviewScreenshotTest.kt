package app.centsible.feature.transactions

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.testing.SampleHousehold
import com.github.takahirom.roborazzi.captureRoboImage
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
class ReviewScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun data(queue: List<app.centsible.core.model.Transaction>, done: Int = 0) = ReviewData(
        queue = queue,
        total = queue.size + done,
        more = false,
        since = "2026-09-20T00:00:00Z",
        categoryNames = SampleHousehold.budgetMonth.groups.flatMap { it.categories }.associate { it.id.raw to it.name },
        accountNames = SampleHousehold.accounts.associate { it.id.raw to it.name },
        categories = emptyList(),
        done = done,
    )

    @Test fun review_stack() {
        // An uncategorized one on top, so the card asks for a category.
        val top = SampleHousehold.transactions.first { !it.isTransfer && !it.isParent }.copy(categoryId = null)
        val queue = listOf(top) + SampleHousehold.transactions.filter { it.id != top.id }.take(4)
        compose.setContent {
            CentsibleTheme(darkTheme = false) { ReviewScreen(ReviewUiState(Loadable.Ready(data(queue, done = 3))), ReviewActions(), today = LocalDate.of(2026, 9, 26)) }
        }
        compose.onRoot().captureRoboImage("screenshots/review_stack.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Test fun review_caught_up_dark() {
        compose.setContent {
            CentsibleTheme(darkTheme = true) { ReviewScreen(ReviewUiState(Loadable.Ready(data(emptyList(), done = 5))), ReviewActions(), today = LocalDate.of(2026, 9, 26)) }
        }
        compose.onRoot().captureRoboImage("screenshots/review_caught_up_dark.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }
}
