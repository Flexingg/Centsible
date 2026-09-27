package app.centsible.feature.transactions

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.AccountId
import app.centsible.core.model.CategoryGroup
import app.centsible.core.model.Category
import app.centsible.core.model.CategoryId
import app.centsible.core.model.Payee
import app.centsible.core.model.PayeeId
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
class EditorScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val groups = SampleHousehold.budgetMonth.groups.map { g ->
        CategoryGroup(g.id, g.name, g.isIncome, g.hidden, g.categories.map { Category(it.id, it.name, g.id, g.isIncome, it.hidden) })
    }
    private val base = EditorUiState(
        loading = false,
        accounts = SampleHousehold.accounts,
        groups = groups,
        payees = listOf(Payee(PayeeId("p1"), "Trader Joe's", null), Payee(PayeeId("p2"), "Target", null), Payee(PayeeId("p3"), "Joint Checking", AccountId("acc-checking"))),
        canEdit = true,
        canSplit = true,
        canTransfer = true,
    )

    private fun render(state: EditorUiState, name: String) {
        compose.setContent { CentsibleTheme(darkTheme = false) { TransactionEditorScreen(state, EditorActions()) } }
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    @Test fun editor_new_expense() = render(
        base.copy(form = TransactionForm(amount = "87.34", payee = "Tra", accountId = AccountId("acc-visa"), categoryId = CategoryId("c-groceries"), date = LocalDate.now())),
        "editor_new_expense",
    )

    @Test fun editor_split() = render(
        base.copy(
            original = SampleHousehold.transactions[8],
            canDelete = true,
            form = TransactionForm(
                amount = "158.70",
                payee = "Costco",
                accountId = AccountId("acc-visa"),
                date = LocalDate.of(2026, 9, 12),
                splits = listOf(
                    SplitRow(1, amount = "120.00", categoryId = CategoryId("c-groceries")),
                    SplitRow(2, amount = "25.00", categoryId = CategoryId("c-kids")),
                    SplitRow(3, amount = "", categoryId = null),
                ),
            ),
        ),
        "editor_split",
    )

    @Test fun editor_transfer() = render(
        base.copy(form = TransactionForm(kind = TxKind.Transfer, amount = "500.00", accountId = AccountId("acc-checking"), transferAccountId = AccountId("acc-savings"))),
        "editor_transfer",
    )

    @Test fun editor_remember_category() = render(
        base.copy(
            canCreateRules = true,
            rememberCategory = true,
            form = TransactionForm(amount = "23.10", payee = "Blue Bottle", accountId = AccountId("acc-visa"), categoryId = CategoryId("c-dining"), date = LocalDate.now()),
        ),
        "editor_remember_category",
    )
}
