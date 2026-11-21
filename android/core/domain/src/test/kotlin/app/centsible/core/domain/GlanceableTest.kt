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
