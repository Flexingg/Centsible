package app.centsible.feature.budget

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.Automation
import app.centsible.core.model.AutomationCategory
import app.centsible.core.model.AutomationSource
import app.centsible.core.model.CategoryGroupId
import app.centsible.core.model.CategoryId
import app.centsible.core.model.Money
import app.centsible.core.model.YearMonth
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
class AutomationsScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val month = YearMonth("2026-09")

    private fun cat(id: String, name: String, group: String, automations: List<Automation>, projected: Long?, source: AutomationSource = AutomationSource.Automations) =
        AutomationCategory(CategoryId(id), name, CategoryGroupId(group), group, false, false, if (automations.isEmpty()) AutomationSource.None else source, automations, projected?.let(::Money))

    @Test fun automations_list() {
        val list = listOf(
            cat("rent", "Rent", "Home", listOf(Automation.CoverSchedule(0, "Rent", full = true, adjustment = null)), 185000),
            cat("util", "Utilities", "Home", listOf(Automation.Average(1, 3, Automation.Adjustment.Percent(10.0))), 18700),
            cat("groc", "Groceries", "Food", listOf(Automation.Refill(1, Automation.Cap(Money(90000)))), 28780),
            cat("dine", "Dining Out", "Food", listOf(Automation.Fixed(2, Money(25000), null)), 25000, AutomationSource.Notes),
            cat("vac", "Vacation", "Goals", listOf(Automation.SaveBy(0, Money(300000), YearMonth("2027-06"), null, null)), 33333),
            cat("fun", "Fun Money", "Goals", listOf(Automation.Remainder(1.0, Automation.Cap(Money(20000)))), 0),
            cat("gift", "Gifts", "Goals", emptyList(), null),
        )
        compose.setContent {
            CentsibleTheme(darkTheme = false) {
                AutomationsScreen(AutomationsUiState(month = month, data = Loadable.Ready(list), canEdit = true), AutomationsActions())
            }
        }
        compose.onRoot().captureRoboImage("screenshots/automations_list.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    @Test fun automation_editor() {
        val draft = listOf(
            Automation.Fixed(0, Money(40000), null),
            Automation.SaveBy(1, Money(120000), YearMonth("2027-03"), Automation.Repeat(yearly = true, count = 1), null),
            Automation.Refill(2, Automation.Cap(Money(90000))),
        )
        val data = AutomationEditorData(
            CategoryId("groc"), "Groceries", false, AutomationSource.Notes, true, draft,
            schedules = listOf("Rent", "Internet"), incomeCategories = listOf("pay" to "Paycheck"),
        )
        compose.setContent {
            CentsibleTheme(darkTheme = false) {
                AutomationEditorScreen(
                    AutomationEditorUiState(
                        month = month, data = Loadable.Ready(data), draft = draft,
                        projected = Money(61220), perAutomation = listOf(Money(40000), Money(10000), Money(11220)), canEdit = true,
                    ),
                    AutomationEditorActions(),
                )
            }
        }
        compose.onRoot().captureRoboImage("screenshots/automation_editor.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }
}
