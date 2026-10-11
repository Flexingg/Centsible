package app.centsible.feature.budget

import androidx.compose.ui.test.junit4.createComposeRule
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.CategoryHistory
import app.centsible.core.model.CategoryId
import app.centsible.core.model.Money
import app.centsible.core.model.YearMonth
import app.centsible.core.testing.SampleHousehold
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The category sheet with everything on, on a viewport tall enough to show all of it: the
 * sheet scrolls on a real phone, and this is what the bottom of it looks like.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1500dp-xxhdpi")
class CategorySheetBottomTest {
    @get:Rule val compose = createComposeRule()

    @Test fun category_sheet_full() {
        val c = CategoryId("c-dining")
        val spent = listOf(28_000L, 31_500, 26_000, 34_800, 29_900, 41_200, 30_400, 27_700, 33_100, 36_900, 32_200, 34_580)
        val history = CategoryHistory(
            c, SampleHousehold.month,
            spent.mapIndexed { i, s -> CategoryHistory.Month(YearMonth.of(2026, 9).plus(i - 11), Money(s), Money(30_000)) },
            Money(34_067), Money(33_250), Money(31_800), 11, Money(41_200), Money(26_000),
        )
        val state = BudgetUiState(
            month = SampleHousehold.month,
            availableMonths = listOf(SampleHousehold.month),
            data = Loadable.Ready(SampleHousehold.budgetMonth),
            canEdit = true, canMoveMoney = true, canToggleRollover = true, canApplyGoals = true, canEditNotes = true,
            noteLoaded = true, note = "",
            selectedCategory = c, history = history,
        )
        compose.setContent { CentsibleTheme(darkTheme = false) { BudgetScreen(state, {}, {}, {}, {}, { _, _ -> }, { _, _, _ -> }, { _, _ -> }, {}) } }
        compose.waitForIdle()
        captureScreenRoboImage("screenshots/category_sheet_full.png")
    }
}
