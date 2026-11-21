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

    private val trend = Glanceable.Trend(
        monthToDate = listOf(5_000L, 12_000, 30_000, 41_000),
        lastMonth = List(30) { (it + 1) * 9_000L },
        daysInMonth = 30,
        week = listOf(4_000L, 0, 11_000, 0, 0, 0, 0),
        weekdayToday = 2,
        lastWeekTotal = 52_000,
        weekAllowance = 70_000,
    )
    private val withTrend = data.copy(
        trend = trend,
        categories = listOf(Glanceable.CategorySlice("Groceries", Money(42_000), Money(60_000)), Glanceable.CategorySlice("Dining Out", Money(31_000), Money(25_000))),
    )

    @Test fun `pace widget compares with last month by the same day`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(260.dp, 180.dp))
        provideComposable { PaceContent(context, withTrend) }
        onNode(hasTextEqualTo("$410")).assertExists()
        // Last month by the 4th: $360, so $50 more.
        onNode(hasTextEqualTo("$50 more than last month by now")).assertExists()
    }

    @Test fun `week widget shows the week against the allowance`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(260.dp, 180.dp))
        provideComposable { WeekContent(context, withTrend) }
        onNode(hasTextEqualTo("$150")).assertExists()
        onNode(hasTextEqualTo("$550 left of $700 · last week $520")).assertExists()
    }

    @Test fun `categories widget lists spent against budgeted`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(260.dp, 180.dp))
        provideComposable { CategoriesContent(context, withTrend) }
        onNode(hasTextEqualTo("Groceries")).assertExists()
        onNode(hasTextEqualTo("$420 / $600")).assertExists()
        onNode(hasTextEqualTo("$310 / $250")).assertExists()
    }

    @Test fun `chart widgets hide everything when locked, and ask for a bridge update without daily data`() {
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { PaceContent(context, withTrend.copy(locked = true)) }
            onNode(hasText("$")).assertDoesNotExist()
        }
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { WeekContent(context, data) }
            onNode(hasText("Update Centsible on your server")).assertExists()
        }
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { CategoriesContent(context, withTrend.copy(locked = true)) }
            onNode(hasText("Groceries")).assertDoesNotExist()
        }
    }

    @Test fun `charts draw at the requested size`() {
        val colors = Charts.Colors(0xFF1C9A62.toInt(), 0xFF9A9DA5.toInt(), 0xFFE9E6E1.toInt(), 0xFFDC3F42.toInt())
        val pace = Charts.pace(trend, 600_000, 600, 240, colors)
        org.junit.Assert.assertEquals(600, pace.width)
        org.junit.Assert.assertEquals(240, pace.height)
        val week = Charts.week(trend, 500, 200, colors)
        org.junit.Assert.assertEquals(500, week.width)
        // Degenerate sizes don't crash.
        Charts.pace(trend.copy(monthToDate = emptyList(), lastMonth = emptyList()), null, 0, 0, colors)
    }
}
