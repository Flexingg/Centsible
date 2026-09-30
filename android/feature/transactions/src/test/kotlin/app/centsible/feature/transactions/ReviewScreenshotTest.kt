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

    @Test fun review_rule_prompt() {
        // Just put a merchant's transaction in a category: offer a rule for the next ones.
        val queue = SampleHousehold.transactions.filter { !it.isTransfer && !it.isParent && it.payeeId != null }.take(3)
        val t = queue.first()
        val prompt = RulePrompt(t.payeeId!!, t.payeeName ?: "Merchant", SampleHousehold.budgetMonth.groups.first().categories.first().id, "Groceries")
        compose.setContent {
            CentsibleTheme(darkTheme = false) { ReviewScreen(ReviewUiState(Loadable.Ready(data(queue, done = 1)), rulePrompt = prompt), ReviewActions(), today = LocalDate.of(2026, 9, 26)) }
        }
        compose.onRoot().captureRoboImage("screenshots/review_rule_prompt.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }

    @Test fun link_transfer_sheet() {
        val names = SampleHousehold.accounts.associate { it.id.raw to it.name }
        val base = SampleHousehold.transactions.first { !it.isTransfer && !it.isParent }
        val checking = SampleHousehold.accounts[0]
        val card = SampleHousehold.accounts.first { it.id != checking.id }
        val t = base.copy(accountId = checking.id, amount = app.centsible.core.model.Money(-123456), payeeName = "CAPITAL ONE ONLINE PMT", date = "2026-09-24", categoryId = null)
        fun c(id: String, amount: Long, payee: String, date: String, exact: Boolean, days: Int) =
            app.centsible.core.model.TransferCandidate(base.copy(id = app.centsible.core.model.TransactionId(id), accountId = card.id, amount = app.centsible.core.model.Money(amount), payeeName = payee, date = date), exact, days)
        val state = LinkTransferState(
            candidates = listOf(c("a", 123456, "PAYMENT - THANK YOU", "2026-09-26", true, 2), c("b", 4321, "Refund from Hardware", "2026-09-23", false, 1), c("c", 2500, "Statement credit", "2026-09-20", false, 4)),
            accountNames = names,
        )
        compose.setContent { CentsibleTheme(darkTheme = false) { LinkTransferContent(t, state, onQuery = {}, onPick = {}, onDismiss = {}) } }
        com.github.takahirom.roborazzi.captureScreenRoboImage("screenshots/link_transfer_sheet.png")
    }

    @Test fun category_picker_create() {
        val state = app.centsible.core.ui.CategoryPickerState(groups = SampleHousehold.budgetMonth.groups.map { g ->
            app.centsible.core.model.CategoryGroup(g.id, g.name, isIncome = g.isIncome, hidden = g.hidden, categories = g.categories.map { app.centsible.core.model.Category(it.id, it.name, g.id, isIncome = g.isIncome, hidden = it.hidden) })
        }, loading = false, canEdit = true)
        compose.setContent {
            CentsibleTheme(darkTheme = false) {
                app.centsible.core.ui.CategoryPickerContent(
                    "Category", state, selected = null, allowNone = false, includeIncome = true, exclude = emptySet(),
                    onPick = {}, onDismiss = {}, onCreate = { _, _, _ -> }, onRename = { _, _ -> }, onHide = {}, startCreating = true,
                )
            }
        }
        com.github.takahirom.roborazzi.captureScreenRoboImage("screenshots/category_picker_create.png")
    }

    @Test fun category_picker_list() {
        val state = app.centsible.core.ui.CategoryPickerState(groups = SampleHousehold.budgetMonth.groups.map { g ->
            app.centsible.core.model.CategoryGroup(g.id, g.name, isIncome = g.isIncome, hidden = g.hidden, categories = g.categories.map { app.centsible.core.model.Category(it.id, it.name, g.id, isIncome = g.isIncome, hidden = it.hidden) })
        }, loading = false, canEdit = true)
        compose.setContent {
            CentsibleTheme(darkTheme = true) {
                app.centsible.core.ui.CategoryPickerContent(
                    "Category", state, selected = state.groups.first().categories.first().id, allowNone = true, includeIncome = true, exclude = emptySet(),
                    onPick = {}, onDismiss = {}, onCreate = { _, _, _ -> }, onRename = { _, _ -> }, onHide = {},
                )
            }
        }
        com.github.takahirom.roborazzi.captureScreenRoboImage("screenshots/category_picker_list_dark.png")
    }

    @Test fun review_caught_up_dark() {
        compose.setContent {
            CentsibleTheme(darkTheme = true) { ReviewScreen(ReviewUiState(Loadable.Ready(data(emptyList(), done = 5))), ReviewActions(), today = LocalDate.of(2026, 9, 26)) }
        }
        compose.onRoot().captureRoboImage("screenshots/review_caught_up_dark.png")
        app.centsible.core.uitesting.A11y.assertOk(compose)
    }
}
