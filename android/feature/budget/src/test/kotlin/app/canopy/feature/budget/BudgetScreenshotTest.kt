package app.canopy.feature.budget

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.canopy.core.designsystem.component.Loadable
import app.canopy.core.designsystem.theme.CanopyTheme
import app.canopy.core.model.CategoryId
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
class BudgetScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val ready = BudgetUiState(
        month = SampleHousehold.month,
        availableMonths = listOf(SampleHousehold.month.plus(-1), SampleHousehold.month),
        data = Loadable.Ready(SampleHousehold.budgetMonth),
        canEdit = true,
        canMoveMoney = true,
        canToggleRollover = true,
    )

    private fun render(state: BudgetUiState, dark: Boolean = false) = compose.setContent {
        CanopyTheme(darkTheme = dark) {
            BudgetScreen(state, {}, {}, {}, {}, { _, _ -> }, { _, _, _ -> }, { _, _ -> }, {})
        }
    }

    @Test fun budget_light() {
        render(ready)
        compose.onRoot().captureRoboImage("screenshots/budget_light.png")
    }

    @Test fun budget_dark() {
        render(ready, dark = true)
        compose.onRoot().captureRoboImage("screenshots/budget_dark.png")
    }

    @Test fun category_sheet() {
        render(ready.copy(selectedCategory = CategoryId("c-dining")))
        compose.waitForIdle()
        captureScreenRoboImage("screenshots/budget_category_sheet.png")
    }

    @Test fun budget_error() {
        render(ready.copy(data = Loadable.Failed("Can't reach your bridge. Check your connection.")))
        compose.onRoot().captureRoboImage("screenshots/budget_error.png")
    }

    @Test fun category_sheet_goals() {
        render(
            ready.copy(
                selectedCategory = CategoryId("c-dining"),
                canApplyGoals = true,
                canEditNotes = true,
                noteLoaded = true,
                note = "Date nights and takeout\n#template 350",
            ),
        )
        compose.waitForIdle()
        captureScreenRoboImage("screenshots/budget_category_goals.png")
    }
}
