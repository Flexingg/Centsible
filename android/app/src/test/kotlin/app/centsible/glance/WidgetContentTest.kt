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

    private val today = java.time.LocalDate.parse("2026-11-04")
    private val rich = withTrend.copy(
        categories = listOf(
            Glanceable.CategorySlice("Groceries", Money(42_000), Money(60_000), "c-groc", Money(18_000)),
            Glanceable.CategorySlice("Dining Out", Money(31_000), Money(25_000), "c-dine", Money(-6_000)),
        ),
        months = listOf(
            Glanceable.MonthFlow("2026-09", Money(500_000), Money(320_000)),
            Glanceable.MonthFlow("2026-10", Money(500_000), Money(410_000)),
            Glanceable.MonthFlow("2026-11", Money(250_000), Money(41_000)),
        ),
        netWorth = listOf(Glanceable.NetWorthPoint("2026-09", Money(10_000_000)), Glanceable.NetWorthPoint("2026-10", Money(10_400_000)), Glanceable.NetWorthPoint("2026-11", Money(10_650_000))),
        sankey = app.centsible.core.model.Sankey.of(
            app.centsible.core.testing.SamplePlanning.flows(),
            app.centsible.core.model.CategoryTree.of(app.centsible.core.testing.SampleHousehold.budgetMonth),
        ),
    )

    @Test fun `the money flow survives being stored`() {
        val graph = rich.sankey!!
        val stored = PrefsGlanceableStoreCodec.decodeSankey(PrefsGlanceableStoreCodec.encodeSankey(graph))
        org.junit.Assert.assertEquals(graph, stored)
        // Shown shallower on request.
        org.junit.Assert.assertTrue(graph.limitDepth(1).nodes.none { it.column > 2 })
    }

    private fun chart(kind: app.centsible.core.domain.ChartKind, data: Glanceable? = rich, cat: String? = null, block: androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTest.() -> Unit) =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(280.dp, 190.dp))
            provideComposable { ChartContent(context, data, app.centsible.core.domain.ChartWidgetConfig(kind, cat, cat?.let { "Groceries" }), today) }
            block()
        }

    @Test fun `pace chart compares with last month by the same day`() = chart(app.centsible.core.domain.ChartKind.Pace) {
        onNode(hasTextEqualTo("$410")).assertExists()
        // Last month by the 4th: $360, so $50 more.
        onNode(hasTextEqualTo("$50 more than last month by now")).assertExists()
    }

    @Test fun `week chart shows the week against the allowance`() = chart(app.centsible.core.domain.ChartKind.Week) {
        onNode(hasTextEqualTo("$150")).assertExists()
        onNode(hasTextEqualTo("$550 left of $700")).assertExists()
    }

    @Test fun `category ring and its legend`() = chart(app.centsible.core.domain.ChartKind.Categories) {
        onNode(hasTextEqualTo("$730")).assertExists()
        onNode(hasTextEqualTo("Groceries")).assertExists()
        onNode(hasTextEqualTo("Dining Out")).assertExists()
    }

    @Test fun `one category shows what's left`() = chart(app.centsible.core.domain.ChartKind.Category, cat = "c-groc") {
        onNode(hasTextEqualTo("$180")).assertExists()
        onNode(hasText("Groceries")).assertExists()
    }

    @Test fun `monthly, cash flow and net worth charts`() {
        chart(app.centsible.core.domain.ChartKind.Monthly) {
            onNode(hasTextEqualTo("$410")).assertExists()
            onNode(hasText("average $3,650")).assertExists()
        }
        chart(app.centsible.core.domain.ChartKind.CashFlow) {
            onNode(hasTextEqualTo("+$2,090")).assertExists()
        }
        chart(app.centsible.core.domain.ChartKind.NetWorth) {
            onNode(hasTextEqualTo("$106,500")).assertExists()
            onNode(hasTextEqualTo("up $6,500 in 2 months")).assertExists()
        }
    }

    @Test fun `charts hide everything when locked, and ask for a server update without the data`() {
        chart(app.centsible.core.domain.ChartKind.Pace, data = rich.copy(locked = true)) { onNode(hasText("$")).assertDoesNotExist() }
        chart(app.centsible.core.domain.ChartKind.Week, data = data) { onNode(hasText("Update Centsible on your server")).assertExists() }
        chart(app.centsible.core.domain.ChartKind.NetWorth, data = data) { onNode(hasText("Update Centsible on your server")).assertExists() }
        chart(app.centsible.core.domain.ChartKind.Category, cat = null) { onNode(hasText("Pick a category")).assertExists() }
    }

    @Test fun `every chart draws at the requested size, light and dark, even with nothing in it`() {
        for (palette in listOf(app.centsible.core.designsystem.theme.LightCentsibleColors, app.centsible.core.designsystem.theme.DarkCentsibleColors)) {
            val art = app.centsible.core.ui.chart.ChartArt(palette, 2f)
            for (kind in app.centsible.core.domain.ChartKind.entries) {
                val face = app.centsible.core.ui.chart.ChartFaces.face(app.centsible.core.domain.ChartWidgetConfig(kind, "c-groc"), rich, today, art)
                val bmp = face.draw!!(art, 500, 260)
                org.junit.Assert.assertEquals(if (face.square) 260 else 500, bmp.width)
            }
            art.area(0, 0, emptyList(), 0)
            art.bars(10, 10, emptyList(), emptyList())
            art.donut(1, emptyList(), "", "")
        }
    }
}
