package app.centsible.core.domain

import app.centsible.core.model.AmountOp
import app.centsible.core.model.Money
import app.centsible.core.model.Schedule
import app.centsible.core.testing.SampleHousehold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class GlanceableTest {
    private val today = LocalDate.parse("2026-11-20")

    private fun schedule(id: String, name: String?, amount: Long, vararg dates: String) = Schedule(
        id, name, dates.firstOrNull(), false, false, null, null, Money(amount), null, AmountOp.Is, null, dates.firstOrNull(), dates.toList(),
    )

    @Test fun `bills are the next few in two weeks, soonest first, as positive amounts`() {
        val bills = Glanceable.bills(
            listOf(
                schedule("rent", "Rent", -150_000, "2026-12-01"),
                schedule("net", "Netflix", -1_599, "2026-11-21"),
                schedule("far", "Insurance", -40_000, "2027-01-15"),
                schedule("gone", "Gym", -3_000, "2026-11-19"),
            ),
            today, { null },
        )
        assertEquals(listOf("Netflix", "Rent"), bills.map { it.title })
        assertEquals(Money(1_599), bills.first().amount)
        assertEquals("due tomorrow", bills.first().whenText)
    }

    @Test fun `at most five bills`() {
        val many = (1..8).map { schedule("s$it", "Bill $it", -1000, today.plusDays(it.toLong()).toString()) }
        assertEquals(5, Glanceable.bills(many, today, { null }).size)
    }

    @Test fun `built from the month, with spending as a positive share of the budget`() {
        val month = SampleHousehold.budgetMonth
        val g = Glanceable.from(month, "Household", reviewCount = 4, bills = emptyList(), now = 1L, monthLabel = "November")
        assertEquals(month.toBudget, g.toBudget)
        assertTrue(!g.spent.isNegative)
        assertEquals(4, g.reviewCount)
        val share = g.spentShare
        if (month.totalBudgeted.minor > 0) assertTrue(share != null && share in 0f..1f) else assertNull(share)
    }
}

class GlanceableTrendTest {
    // Friday 20 November 2026; this week runs Monday 16th to Sunday 22nd.
    private val today = LocalDate.parse("2026-11-20")

    @Test fun `running totals, this week and last week`() {
        val days = mapOf(
            LocalDate.parse("2026-10-01") to 1_000L,
            LocalDate.parse("2026-10-31") to 500L,
            LocalDate.parse("2026-11-01") to 2_000L,
            LocalDate.parse("2026-11-10") to 300L, // last week (Mon 9th .. Sun 15th)
            LocalDate.parse("2026-11-16") to 200L, // this week: Monday
            LocalDate.parse("2026-11-17") to 400L,
            LocalDate.parse("2026-11-20") to 100L, // today, Friday
            LocalDate.parse("2026-11-21") to 999L, // tomorrow: not counted
        )
        val t = Glanceable.Trend.from(days, today, monthBudget = 300_000)
        assertEquals(20, t.monthToDate.size)
        assertEquals(2_000L + 300 + 200 + 400 + 100, t.spentThisMonth)
        assertEquals(31, t.lastMonth.size)
        assertEquals(1_500L, t.lastMonth.last())
        assertEquals(1_000L, t.lastMonthByNow) // by the 20th of October
        assertEquals(listOf(200L, 400, 0, 0, 100, 0, 0), t.week)
        assertEquals(4, t.weekdayToday)
        assertEquals(300L, t.lastWeekTotal)
        assertEquals(300_000L * 7 / 30, t.weekAllowance)
        assertEquals(30, t.daysInMonth)
    }

    @Test fun `no budget, no allowance`() {
        assertNull(Glanceable.Trend.from(emptyMap(), today, monthBudget = 0).weekAllowance)
        assertNull(Glanceable.Trend.from(emptyMap(), today, monthBudget = null).weekAllowance)
    }

    @Test fun `top categories are the biggest spends, as positive amounts`() {
        val top = Glanceable.topCategories(SampleHousehold.budgetMonth, max = 3)
        assertEquals(3, top.size)
        assertTrue(top.zipWithNext().all { (a, b) -> a.spent.minor >= b.spent.minor })
        assertTrue(top.all { it.spent.minor > 0 })
    }
}
