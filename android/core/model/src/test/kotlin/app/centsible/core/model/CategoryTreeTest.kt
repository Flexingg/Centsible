package app.centsible.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryTreeTest {
    private val needs = CategoryGroupId("needs")
    private val wants = CategoryGroupId("wants")
    private val incomeGroup = CategoryGroupId("income")

    private fun c(id: String, parent: String? = null, group: CategoryGroupId = needs, income: Boolean = false) =
        Category(CategoryId(id), id.replaceFirstChar { it.uppercase() }, group, income, false, parent?.let(::CategoryId))

    // Needs: food > groceries > costco > bulk, food > dining; bills. Wants: fun > games. Income: pay.
    private val groups = listOf(
        CategoryGroup(needs, "Needs", false, false, listOf(c("food"), c("groceries", "food"), c("costco", "groceries"), c("bulk", "costco"), c("dining", "food"), c("bills"))),
        CategoryGroup(wants, "Wants", false, false, listOf(c("fun", group = wants), c("games", "fun", wants))),
        CategoryGroup(incomeGroup, "Income", true, false, listOf(c("pay", group = incomeGroup, income = true))),
    )
    private val tree = CategoryTree.of(groups)
    private fun id(s: String) = CategoryId(s)

    @Test fun `levels, paths and subtrees`() {
        assertEquals(2, tree.level(id("food")))
        assertEquals(5, tree.level(id("bulk")))
        assertEquals("Food › Groceries › Costco", tree.pathLabel(id("costco")))
        assertEquals(listOf("food", "groceries", "costco", "bulk", "dining"), tree.subtree(id("food")).map { it.raw })
        assertEquals(listOf(id("food"), id("bills")), tree.rootsOf(needs))
        assertEquals(4, tree.height(id("food")))
        assertEquals(listOf(2, 3, 4, 5, 3, 2), tree.flatten(needs).map { it.second })
    }

    @Test fun `moves stay within five levels, never loop, and keep income apart`() {
        assertFalse(tree.canAddChild(id("bulk")))
        assertTrue(tree.canAddChild(id("costco")))
        assertFalse("under its own child", tree.canMove(id("food"), id("dining")))
        assertFalse("too deep: food is 4 high", tree.canMove(id("food"), id("bills")))
        assertTrue(tree.canMove(id("groceries"), id("bills")))
        assertTrue("games to Needs", tree.canMove(id("games"), id("costco")))
        assertFalse(tree.canMove(id("pay"), id("bills")))
        assertTrue(tree.canMove(id("costco"), null))
    }

    @Test fun `links that don't hold read as top level`() {
        val t = CategoryTree.of(
            listOf(
                CategoryGroup(needs, "Needs", false, false, listOf(c("a", "gone"), c("b", "b"), c("x", "y"), c("y", "x"))),
                CategoryGroup(wants, "Wants", false, false, listOf(c("w", "a", wants))),
            ),
        )
        assertNull(t.parentOf(id("a")))
        assertNull(t.parentOf(id("b")))
        assertNull("loop", t.parentOf(id("x")))
        assertNull("other group", t.parentOf(id("w")))
    }

    @Test fun `rollups add sub-categories to their parents`() {
        fun bc(id: String, budgeted: Long, spent: Long, parent: String? = null) =
            BudgetCategory(CategoryId(id), id, false, Money(budgeted), Money(-spent), Money(budgeted - spent), Money.Zero, false, parent?.let(::CategoryId))
        val group = BudgetGroup(
            needs, "Needs", false, false, Money.Zero, Money.Zero, Money.Zero, Money.Zero,
            listOf(bc("food", 100, 20), bc("groceries", 300, 250, "food"), bc("costco", 0, 40, "groceries"), bc("bills", 500, 500)),
        )
        val rows = group.rollups()
        assertEquals(listOf("food", "groceries", "costco", "bills"), rows.map { it.id.raw })
        assertEquals(listOf(2, 3, 4, 2), rows.map { it.level })
        val food = rows.first()
        assertEquals(400, food.budgeted.minor)
        assertEquals(-310, food.spent.minor)
        assertTrue(food.hasChildren)
        assertEquals(-40, rows[2].balance.minor)
    }

    @Test fun `the drill tree zooms group, category, sub-category, with the parent's own money as a slice`() {
        val spent = mapOf("food" to -1_000L, "groceries" to -3_000L, "costco" to -2_000L, "bulk" to -500L, "dining" to -1_500L, "bills" to -6_000L, "games" to -800L, "fun" to 200L)
            .mapKeys { id(it.key) }
        val roots = spendingDrillTree(groups, spent)
        assertEquals(listOf("Needs", "Wants"), roots.map { it.name })
        val needsNode = roots.first()
        assertEquals(14_000L, needsNode.amount)
        val food = needsNode.children.first { it.name == "Food" }
        assertEquals(8_000L, food.amount)
        assertEquals(listOf("Groceries", "Dining", "Food (general)"), food.children.map { it.name })
        assertTrue(food.children.last().isOwn)
        val groceries = food.children.first()
        assertEquals(5_500L, groceries.amount)
        assertTrue(groceries.canDrill)
        assertFalse(needsNode.children.first { it.name == "Bills" }.canDrill)
        // A refund bigger than its spending doesn't go below zero.
        assertEquals(800L, roots.last().amount)
    }

    @Test fun `top slices fold the rest into Other, which drills into them`() {
        val nodes = (1..6).map { DrillNode("k$it", "n$it", (10 - it).toLong()) }
        val top = nodes.topSlices(4)
        assertEquals(4, top.size)
        assertTrue(top.last().isOther)
        assertEquals(listOf("n4", "n5", "n6"), top.last().children.map { it.name })
        assertEquals(nodes.sumOf { it.amount }, top.sumOf { it.amount })
    }
}
