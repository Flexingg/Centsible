package app.canopy.feature.planning

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.canopy.core.designsystem.component.Loadable
import app.canopy.core.designsystem.theme.CanopyTheme
import app.canopy.core.model.Category
import app.canopy.core.model.CategoryGroup
import app.canopy.core.model.Payee
import app.canopy.core.model.PayeeId
import app.canopy.core.testing.SampleHousehold
import app.canopy.core.testing.SamplePlanning
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
class PlanningScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val today = LocalDate.of(2026, 9, 27)
    private val recurring = RecurringData(SamplePlanning.schedules, SamplePlanning.payeeNames, SampleHousehold.accounts)

    @Test fun recurring_light() {
        compose.setContent { CanopyTheme(darkTheme = false) { RecurringScreen(RecurringUiState(Loadable.Ready(recurring), canEdit = true), onBack = {}, today = today) } }
        compose.onRoot().captureRoboImage("screenshots/recurring_light.png")
    }

    @Test fun recurring_sheet() {
        compose.setContent {
            CanopyTheme(darkTheme = false) {
                RecurringScreen(RecurringUiState(Loadable.Ready(recurring), canEdit = true, canSkip = true, canPost = true, editing = SamplePlanning.schedules[2]), onBack = {}, today = today)
            }
        }
        compose.waitForIdle()
        captureScreenRoboImage("screenshots/recurring_sheet.png")
    }

    @Test fun merchants_light() {
        compose.setContent { CanopyTheme(darkTheme = false) { MerchantsScreen(MerchantsUiState(Loadable.Ready(SamplePlanning.payees.filter { it.transferAccountId == null }), canEdit = true), onBack = {}) } }
        compose.onRoot().captureRoboImage("screenshots/merchants_light.png")
    }

    @Test fun rules_light() {
        val groups = SampleHousehold.budgetMonth.groups.map { g -> CategoryGroup(g.id, g.name, g.isIncome, g.hidden, g.categories.map { Category(it.id, it.name, g.id, g.isIncome, it.hidden) }) }
        val names = Describe.Names(SamplePlanning.payeeNames + ("p-amazon" to "Amazon"), groups.flatMap { it.categories }.associate { it.id.raw to it.name }, emptyMap())
        val data = RulesData(SamplePlanning.rules, names, SamplePlanning.payeeNames.map { (id, n) -> Payee(PayeeId(id), n, null) }, groups)
        compose.setContent { CanopyTheme(darkTheme = false) { RulesScreen(RulesUiState(Loadable.Ready(data), canEdit = true), onBack = {}) } }
        compose.onRoot().captureRoboImage("screenshots/rules_light.png")
    }
}
