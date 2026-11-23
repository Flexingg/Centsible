package app.centsible.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SankeyTest {
    private val needs = CategoryGroupId("needs")
    private val wants = CategoryGroupId("wants")
    private val inc = CategoryGroupId("income")
    private fun id(s: String) = CategoryId(s)
    private fun c(name: String, parent: String? = null, group: CategoryGroupId = needs, income: Boolean = false) =
        Category(id(name), name, group, income, false, parent?.let(::CategoryId))

    private val tree = CategoryTree.of(
        listOf(
            CategoryGroup(needs, "Needs", false, false, listOf(c("Food"), c("Groceries", "Food"), c("Costco", "Groceries"), c("Bills"))),
            CategoryGroup(wants, "Wants", false, false, listOf(c("Fun", group = wants))),
            CategoryGroup(inc, "Income", true, false, listOf(c("Pay", group = inc, income = true), c("Bonus", "Pay", inc, true), c("Interest", group = inc, income = true))),
        ),
    )

    private fun flow(name: String, amount: Long, group: CategoryGroupId?, parent: String? = null) =
        FlowCategory(id(name), name, group, group?.raw?.replaceFirstChar { it.uppercase() }, parent?.let(::CategoryId), Money(amount))

    private fun report(vararg spending: FlowCategory, income: List<FlowCategory> = listOf(flow("Pay", 8_000, inc), flow("Bonus", 2_000, inc, "Pay"), flow("Interest", 100, inc))) =
        FlowsReport(YearMonth("2026-11"), YearMonth("2026-11"), Money(income.sumOf { it.amount.minor }), Money(spending.sumOf { it.amount.minor }), income, spending.toList())

    private val spend = report(
        flow("Food", -500, needs),
        flow("Groceries", -1_500, needs, "Food"),
        flow("Costco", -1_000, needs, "Groceries"),
        flow("Bills", -4_000, needs),
        flow("Fun", -1_000, wants),
        FlowCategory(null, "Uncategorized", null, null, null, Money(-200)),
    )

    @Test fun `money flows from income through the hub into groups and down each level`() {
        val g = Sankey.of(spend, tree, minShare = 0f)
        // Income rolls up into top-level income categories.
        assertEquals(listOf("Pay" to 10_000L, "Interest" to 100L), g.nodes.filter { it.column == 0 }.map { it.name to it.value })
        assertEquals(10_100L, g.node(Sankey.HUB).value)
        assertEquals(listOf("Needs", "Wants", "Uncategorized", "Saved"), g.nodes.filter { it.column == 2 }.map { it.name })
        assertEquals(7_000L, g.node("g:needs").value)
        assertEquals(1_900L, g.node("saved").value) // 10,100 in, 8,200 out
        assertEquals(3_000L, g.node("c:Food").value)
        assertEquals(1_000L, g.node("c:Costco").value)
        assertEquals(5, g.node("c:Costco").column)
        // Every node's outflow is at most what flows in.
        for (n in g.nodes) {
            val out = g.links.filter { it.from == n.key }.sumOf { it.value }
            assertTrue("${n.name} out $out > ${n.value}", out <= n.value)
        }
    }

    @Test fun `depth limits how far down it goes, and spending past income draws on savings`() {
        val shallow = Sankey.of(spend, tree, depth = 2, minShare = 0f)
        assertEquals(3, shallow.nodes.maxOf { it.column })
        assertFalse(shallow.nodes.any { it.name == "Groceries" })

        val over = report(flow("Bills", -12_000, needs))
        val g = Sankey.of(over, tree)
        assertEquals(1_900L, g.node("from-savings").value)
        assertFalse(g.nodes.any { it.kind == SankeyNode.Kind.Saved })
        assertEquals(12_000L, g.node(Sankey.HUB).value)
    }

    @Test fun `small flows fold into Other`() {
        val many = report(flow("Food", -50, needs), flow("Bills", -9_000, needs), flow("Fun", -60, wants))
        val bigTree = CategoryTree.of(
            listOf(CategoryGroup(needs, "Needs", false, false, listOf(c("Food"), c("Bills"), c("Gas"), c("Water")))),
        )
        val g = Sankey.of(
            report(flow("Food", -50, needs), flow("Gas", -40, needs), flow("Water", -30, needs), flow("Bills", -9_000, needs)),
            bigTree,
            minShare = 0.05f,
        )
        val other = g.nodes.single { it.kind == SankeyNode.Kind.Other }
        assertEquals(120L, other.value)
        assertEquals(listOf("Bills", "Other"), g.nodes.filter { it.column == 3 }.map { it.name })
        // A single small one isn't worth folding: it keeps its name.
        assertTrue(Sankey.of(many, tree, minShare = 0.05f).nodes.any { it.name == "Fun" })
    }

    @Test fun `layout keeps boxes inside, in order, and bands meet their boxes`() {
        val g = Sankey.of(spend, tree, minShare = 0f)
        val l = g.layout(600f, 300f, nodeWidth = 10f, gap = 6f)
        assertEquals(g.nodes.size, l.nodes.size)
        for (b in l.nodes) {
            assertTrue("${b.node.name} ${b.y0}..${b.y1}", b.y0 >= -0.01f && b.y1 <= 300.01f)
        }
        for ((_, col) in l.nodes.groupBy { it.node.column }) {
            col.zipWithNext().forEach { (a, b) -> assertTrue("${a.node.name} overlaps ${b.node.name}", b.y0 >= a.y1 - 0.01f) }
        }
        for (band in l.links) {
            assertTrue(band.y0 >= band.from.y0 - 0.01f && band.y0 + band.thickness <= band.from.y1 + 0.01f)
            assertTrue(band.y1 >= band.to.y0 - 0.01f && band.y1 + band.thickness <= band.to.y1 + 0.01f)
        }
        val hub = l.nodes.first { it.node.key == Sankey.HUB }
        assertEquals(hub, l.hit((hub.x0 + hub.x1) / 2, hub.midY))
    }

    @Test fun `a full graph can be shown shallower`() {
        val full = Sankey.of(spend, tree, minShare = 0f)
        val groupsOnly = full.limitDepth(1)
        assertEquals(2, groupsOnly.nodes.maxOf { it.column })
        assertEquals(Sankey.of(spend, tree, depth = 3, minShare = 0f), full.limitDepth(3))
    }

    @Test fun `nothing in or out is an empty graph`() {
        val g = Sankey.of(report(income = emptyList()), tree)
        assertTrue(g.isEmpty)
        assertTrue(g.layout(100f, 100f).nodes.isEmpty())
    }
}
