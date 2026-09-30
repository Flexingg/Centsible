package app.centsible.feature.budget

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.Autopilot
import app.centsible.core.model.AverageBasis
import app.centsible.core.model.BudgetSuggestion
import app.centsible.core.model.CategoryId
import app.centsible.core.model.CoverMove
import app.centsible.core.model.Money
import app.centsible.core.model.Overspent
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
        CentsibleTheme(darkTheme = dark) {
            BudgetScreen(state, {}, {}, {}, {}, { _, _ -> }, { _, _, _ -> }, { _, _ -> }, {})
        }
    }

    @Test fun budget_light() {
        render(ready)
        compose.onRoot().captureRoboImage("screenshots/budget_light.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Test fun budget_dark() {
        render(ready, dark = true)
        compose.onRoot().captureRoboImage("screenshots/budget_dark.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Test fun category_sheet() {
        render(ready.copy(selectedCategory = CategoryId("c-dining")))
        compose.waitForIdle()
        captureScreenRoboImage("screenshots/budget_category_sheet.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Test fun category_sheet_yearly() {
        val c = CategoryId("c-dining")
        render(ready.copy(selectedCategory = c, annual = mapOf(c.raw to app.centsible.core.model.AnnualBudget(c, app.centsible.core.model.Money(120_000), 1, 8, app.centsible.core.model.Money(30_000), app.centsible.core.model.Money(10_000), app.centsible.core.model.Money(10_000)))))
        captureScreenRoboImage("screenshots/category_sheet_yearly.png")
    }

    @Test fun budget_error() {
        render(ready.copy(data = Loadable.Failed("Can't reach your bridge. Check your connection.")))
        compose.onRoot().captureRoboImage("screenshots/budget_error.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
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
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Test fun budget_holding() {
        val m = SampleHousehold.budgetMonth
        render(ready.copy(canHold = true, data = Loadable.Ready(m.copy(toBudget = app.centsible.core.model.Money(0), forNextMonth = m.toBudget))))
        compose.onRoot().captureRoboImage("screenshots/budget_holding.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Test fun budget_large_text() {
        compose.setContent {
            CentsibleTheme(darkTheme = false) {
                app.centsible.core.uitesting.LargeText { BudgetScreen(ready, {}, {}, {}, {}, { _, _ -> }, { _, _, _ -> }, { _, _ -> }, {}) }
            }
        }
        compose.onRoot().captureRoboImage("screenshots/budget_large_text.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    private fun avg(a3: Long, a6: Long, a12: Long) = mapOf(AverageBasis.Three to Money(a3), AverageBasis.Six to Money(a6), AverageBasis.Twelve to Money(a12))
    private fun up(m: Long) = (m + 99) / 100 * 100
    private fun suggestion(id: String, name: String, budgeted: Long, last: Long, a3: Long, a6: Long, a12: Long, months: Int = 12) =
        BudgetSuggestion(CategoryId(id), name, "Everyday", Money(budgeted), Money(last), avg(a3, a6, a12), avg(up(a3), up(a6), up(a12)), months)

    private val autopilot = Autopilot(
        SampleHousehold.month, Money(42_000),
        listOf(
            suggestion("c-groceries", "Groceries", 60_000, 71_245, 68_310, 66_020, 64_180),
            suggestion("c-dining", "Dining Out", 25_000, 18_950, 21_433, 24_800, 26_100),
            suggestion("c-gas", "Gas", 18_000, 17_420, 17_900, 17_650, 18_200),
            suggestion("c-pets", "Pets", 0, 6_499, 6_499, 6_499, 6_499, months = 1),
        ),
        listOf(Overspent(CategoryId("c-dining"), "Dining Out", Money(4_320)), Overspent(CategoryId("c-gas"), "Gas", Money(1_150))),
        listOf(
            CoverMove("to-budget", "To Budget", CategoryId("c-dining"), "Dining Out", Money(4_320)),
            CoverMove("c-fun", "Fun Money", CategoryId("c-gas"), "Gas", Money(1_150)),
        ),
        Money.Zero,
    )

    @Test fun autopilot_sheet() {
        render(ready.copy(autopilot = Loadable.Ready(autopilot), autopilotSelected = setOf(CategoryId("c-groceries"), CategoryId("c-dining"), CategoryId("c-pets"))))
        compose.waitForIdle()
        captureScreenRoboImage("screenshots/budget_autopilot.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Test fun cover_sheet() {
        render(ready.copy(cover = Loadable.Ready(autopilot)))
        compose.waitForIdle()
        captureScreenRoboImage("screenshots/budget_cover.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }
}
