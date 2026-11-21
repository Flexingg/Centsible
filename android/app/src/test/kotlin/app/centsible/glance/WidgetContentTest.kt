package app.centsible.glance

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.glance.testing.unit.hasTextEqualTo
import app.centsible.core.domain.Glanceable
import app.centsible.core.model.Money
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WidgetContentTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private val data = Glanceable(
        budgetName = "Household", month = "November", envelope = true,
        toBudget = Money(42_000), spent = Money(180_000), budgeted = Money(300_000), reviewCount = 4,
        bills = listOf(Glanceable.Bill("Rent", Money(150_000), "2026-12-01", "due in 3 days"), Glanceable.Bill("Netflix", Money(1_599), "2026-11-29", "due tomorrow")),
        updatedAt = 1_763_650_000_000,
    )

    @Test fun `budget widget shows what's left, the spending and the review count`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(260.dp, 120.dp))
        provideComposable { BudgetContent(context, data) }
        onNode(hasTextEqualTo("$420")).assertExists()
        onNode(hasTextEqualTo("to budget")).assertExists()
        onNode(hasTextEqualTo("$1,800 of $3,000 spent")).assertExists()
        onNode(hasTextEqualTo("Review 4")).assertExists()
        onNode(hasTextEqualTo("+ Add")).assertExists()
    }

    @Test fun `tracking budgets show spending instead`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        provideComposable { BudgetContent(context, data.copy(envelope = false)) }
        onNode(hasTextEqualTo("spent this month")).assertExists()
        onNode(hasTextEqualTo("to budget")).assertDoesNotExist()
    }

    @Test fun `with the app lock on, no amounts are shown`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        provideComposable { BudgetContent(context, data.copy(locked = true)) }
        onNode(hasText("App lock is on")).assertExists()
        onNode(hasText("$")).assertDoesNotExist()
    }

    @Test fun `bills widget lists the next bills`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(250.dp, 200.dp))
        provideComposable { BillsContent(context, data) }
        onNode(hasTextEqualTo("Rent")).assertExists()
        onNode(hasTextEqualTo("$1,500.00")).assertExists()
        onNode(hasTextEqualTo("due tomorrow")).assertExists()
    }

    @Test fun `bills widget locked and empty states`() {
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { BillsContent(context, data.copy(locked = true)) }
            onNode(hasTextEqualTo("Rent")).assertDoesNotExist()
        }
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { BillsContent(context, data.copy(bills = emptyList())) }
            onNode(hasTextEqualTo("Nothing due in the next two weeks.")).assertExists()
        }
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { BillsContent(context, null) }
            onNode(hasText("Open Centsible once")).assertExists()
        }
    }
}
