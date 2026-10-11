package app.centsible.feature.budget

import app.centsible.core.model.CategoryId
import app.centsible.core.model.withoutCategories
import app.centsible.core.testing.SampleHousehold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class OffBooksTest {
    private val month = SampleHousehold.budgetMonth

    @Test fun `off-the-books categories leave their group and the month's totals`() {
        val id = CategoryId("c-dining")
        val dining = month.groups.flatMap { it.categories }.first { it.id == id }
        val group = month.groups.first { g -> g.categories.any { it.id == id } }
        val out = month.withoutCategories(setOf(id))
        assertFalse(out.groups.flatMap { it.categories }.any { it.id == id })
        assertEquals(month.totalSpent.minor - dining.spent.minor, out.totalSpent.minor)
        assertEquals(month.totalBudgeted.minor - dining.budgeted.minor, out.totalBudgeted.minor)
        val g = out.groups.first { it.id == group.id }
        assertEquals(group.spent.minor - dining.spent.minor, g.spent.minor)
        assertEquals(group.balance.minor - dining.balance.minor, g.balance.minor)
        // Actual's own To Budget is left alone.
        assertEquals(month.toBudget, out.toBudget)
    }

    @Test fun `nothing off the books changes nothing`() {
        assertSame(month, month.withoutCategories(emptySet()))
        assertSame(month, month.withoutCategories(setOf(CategoryId("not-here"))))
    }
}
