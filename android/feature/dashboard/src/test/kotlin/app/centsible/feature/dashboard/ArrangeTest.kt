package app.centsible.feature.dashboard

import app.centsible.core.model.HomeLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class ArrangeTest {
    private val widgets = listOf(BudgetDialWidget(), NetWorthWidget(), BudgetSummaryWidget(), RecentTransactionsWidget())

    @Test
    fun `follows the person's order, new cards last`() {
        val layout = HomeLayout(order = listOf("core.recent-transactions", "core.budget-dial", "gone.widget"))
        assertEquals(
            listOf("core.recent-transactions", "core.budget-dial", "core.net-worth", "core.budget-summary"),
            arrange(widgets, layout).map { it.id },
        )
    }

    @Test
    fun `no layout keeps the default order`() {
        assertEquals(widgets.map { it.id }, arrange(widgets, HomeLayout()).map { it.id })
    }
}
