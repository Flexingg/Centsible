package app.centsible.core.domain

import app.centsible.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TileMetricsTest {
    private val trend = Glanceable.Trend(
        monthToDate = listOf(10_000L, 25_000, 40_000), // today (the 3rd) spent 15,000
        lastMonth = emptyList(), daysInMonth = 30, week = listOf(25_000L, 15_000, 0, 0, 0, 0, 0), weekdayToday = 1,
        lastWeekTotal = 0, weekAllowance = 70_000,
    )
    private val g = Glanceable(
        budgetName = null, month = "November", envelope = true, toBudget = Money(12_300), spent = Money(40_000), budgeted = Money(300_000),
        reviewCount = 0, bills = emptyList(), updatedAt = 0, trend = trend, leftInBudget = Money(260_000),
        categories = listOf(Glanceable.CategorySlice("Groceries", Money(20_000), Money(60_000), "c-groc", Money(40_000))),
    )

    @Test fun `left today spreads what was left this morning over the days left, minus today's spending`() {
        // This morning: 260,000 + 15,000 = 275,000 over 28 days (3rd..30th) = 9,821 a day; 15,000 spent: -5,179.
        val r = TileMetrics.read(TileConfig(TileMetric.LeftToday), g, dayOfMonth = 3, daysInMonth = 30)
        assertEquals(Money(275_000L / 28 - 15_000), r.amount)
        assertEquals(true, r.negative)
    }

    @Test fun `on the last day everything left is today's`() {
        val r = TileMetrics.read(TileConfig(TileMetric.LeftToday), g, dayOfMonth = 30, daysInMonth = 30)
        assertEquals(Money(260_000), r.amount)
    }

    @Test fun `the other figures`() {
        assertEquals(Money(260_000), TileMetrics.read(TileConfig(TileMetric.LeftMonth), g, 3, 30).amount)
        assertEquals(Money(70_000 - 40_000), TileMetrics.read(TileConfig(TileMetric.LeftWeek), g, 3, 30).amount)
        assertEquals(Money(15_000), TileMetrics.read(TileConfig(TileMetric.SpentToday), g, 3, 30).amount)
        assertEquals(false, TileMetrics.read(TileConfig(TileMetric.SpentToday), g, 3, 30).negative)
        assertEquals(Money(12_300), TileMetrics.read(TileConfig(TileMetric.ToBudget), g, 3, 30).amount)
        val cat = TileMetrics.read(TileConfig(TileMetric.Category, "c-groc", "Groceries"), g, 3, 30)
        assertEquals("Groceries left", cat.title)
        assertEquals(Money(40_000), cat.amount)
    }

    @Test fun `missing data shows no amount rather than a wrong one`() {
        val bare = g.copy(trend = null)
        assertNull(TileMetrics.read(TileConfig(TileMetric.LeftWeek), bare, 3, 30).amount)
        assertNull(TileMetrics.read(TileConfig(TileMetric.SpentToday), bare, 3, 30).amount)
        assertNull(TileMetrics.read(TileConfig(TileMetric.Category, "gone", "Old"), g, 3, 30).amount)
        assertEquals("Old left", TileMetrics.read(TileConfig(TileMetric.Category, "gone", "Old"), g, 3, 30).title)
    }
}
