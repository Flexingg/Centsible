package app.centsible.feature.planning

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.Category
import app.centsible.core.model.CategoryGroup
import app.centsible.core.model.Payee
import app.centsible.core.model.PayeeId
import app.centsible.core.testing.SampleHousehold
import app.centsible.core.testing.SamplePlanning
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
        compose.setContent { CentsibleTheme(darkTheme = false) { RecurringScreen(RecurringUiState(Loadable.Ready(recurring), canEdit = true), onBack = {}, today = today) } }
        compose.onRoot().captureRoboImage("screenshots/recurring_light.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Test fun recurring_sheet() {
        compose.setContent {
            CentsibleTheme(darkTheme = false) {
                RecurringScreen(RecurringUiState(Loadable.Ready(recurring), canEdit = true, canSkip = true, canPost = true, editing = SamplePlanning.schedules[2]), onBack = {}, today = today)
            }
        }
        compose.waitForIdle()
        captureScreenRoboImage("screenshots/recurring_sheet.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Test fun merchants_light() {
        compose.setContent { CentsibleTheme(darkTheme = false) { MerchantsScreen(MerchantsUiState(Loadable.Ready(SamplePlanning.payees.filter { it.transferAccountId == null }), canEdit = true), onBack = {}) } }
        compose.onRoot().captureRoboImage("screenshots/merchants_light.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Test fun rules_light() {
        val groups = SampleHousehold.budgetMonth.groups.map { g -> CategoryGroup(g.id, g.name, g.isIncome, g.hidden, g.categories.map { Category(it.id, it.name, g.id, g.isIncome, it.hidden) }) }
        val names = Describe.Names(SamplePlanning.payeeNames + ("p-amazon" to "Amazon"), groups.flatMap { it.categories }.associate { it.id.raw to it.name }, emptyMap())
        val data = RulesData(SamplePlanning.rules, names, SamplePlanning.payeeNames.map { (id, n) -> Payee(PayeeId(id), n, null) }, groups)
        compose.setContent { CentsibleTheme(darkTheme = false) { RulesScreen(RulesUiState(Loadable.Ready(data), canEdit = true), onBack = {}) } }
        compose.onRoot().captureRoboImage("screenshots/rules_light.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    private fun editorData(): RuleEditorData {
        val groups = SampleHousehold.budgetMonth.groups.map { g -> CategoryGroup(g.id, g.name, g.isIncome, g.hidden, g.categories.map { Category(it.id, it.name, g.id, g.isIncome, it.hidden) }) }
        val payees = SamplePlanning.payeeNames.map { (id, n) -> Payee(PayeeId(id), n, null) }
        val names = Describe.Names(SamplePlanning.payeeNames, groups.flatMap { it.categories }.associate { it.id.raw to it.name }, SampleHousehold.accounts.associate { it.id.raw to it.name })
        return RuleEditorData(payees, groups, SampleHousehold.accounts, names, scheduleRule = false)
    }

    @Config(qualifiers = "w411dp-h2200dp-xxhdpi")
    @Test fun rule_editor_formula() {
        val data = editorData()
        val payee = data.payees.first()
        val groceries = data.groups.flatMap { it.categories }.first { it.name == "Groceries" }
        val form = RuleFormState(
            conditions = listOf(
                CondRow("payee", "is", app.centsible.core.model.RuleValue.Text(payee.id.raw)),
                CondRow("amount", "gt", app.centsible.core.model.RuleValue.Number(2000), AmountSign.Out),
            ),
            actions = listOf(
                ActRow("set", "category", app.centsible.core.model.RuleValue.Text(groceries.id.raw)),
                ActRow("set", "notes", app.centsible.core.model.RuleValue.Null, ValueMode.Formula, "=UPPER(payee_name) & \" \" & TEXT(ABS(amount)/100, \"0.00\")"),
            ),
        )
        val tx = SampleHousehold.transactions.first { it.payeeName == payee.name || true }
        val preview = app.centsible.core.model.RulePreview(
            12,
            listOf(
                app.centsible.core.model.RulePreviewItem(tx, listOf(app.centsible.core.model.RuleChange("category", "Groceries"), app.centsible.core.model.RuleChange("notes", "${payee.name.uppercase()} 87.34"))),
                app.centsible.core.model.RulePreviewItem(SampleHousehold.transactions[3], listOf(app.centsible.core.model.RuleChange("category", "Groceries"), app.centsible.core.model.RuleChange("notes", null, error = "Formula error: #NAME?"))),
            ),
            emptyList(),
        )
        compose.setContent {
            CentsibleTheme(darkTheme = false) {
                RuleEditorScreen(RuleEditorUiState(id = "r1", data = Loadable.Ready(data), form = form, canEdit = true, preview = preview), RuleEditorActions())
            }
        }
        compose.onRoot().captureRoboImage("screenshots/rule_editor_formula.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Config(qualifiers = "w411dp-h1600dp-xxhdpi")
    @Test fun rule_editor_splits_dark() {
        val data = editorData()
        val cats = data.groups.flatMap { it.categories }
        val form = RuleFormState(
            stage = "post",
            conditions = listOf(CondRow("imported_payee", "contains", app.centsible.core.model.RuleValue.Text("COSTCO"))),
            actions = emptyList(),
            splits = listOf(
                SplitBlock(SplitMethod.Fixed, amount = 5000, actions = listOf(ActRow("set", "category", app.centsible.core.model.RuleValue.Text(cats[1].id.raw)))),
                SplitBlock(SplitMethod.Remainder, actions = listOf(ActRow("set", "category", app.centsible.core.model.RuleValue.Text(cats[2].id.raw)))),
            ),
        )
        compose.setContent {
            CentsibleTheme(darkTheme = true) {
                RuleEditorScreen(RuleEditorUiState(data = Loadable.Ready(data), form = form, canEdit = true, preview = app.centsible.core.model.RulePreview(0, emptyList(), emptyList())), RuleEditorActions())
            }
        }
        compose.onRoot().captureRoboImage("screenshots/rule_editor_splits_dark.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }
}
