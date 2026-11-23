package app.centsible.core.model

/** The group is level 1, its own categories level 2; sub-categories go down to here. */
const val MAX_CATEGORY_LEVEL = 5

/** The level a group's own (top-level) categories are at. */
const val TOP_CATEGORY_LEVEL = 2

/**
 * A budget's categories as a tree: group (level 1), its categories (level 2), and their
 * sub-categories down to level 5. Actual only has the first two; the bridge keeps the
 * parent links ([Category.parentId]). Every level from 2 down can hold transactions and an
 * envelope of its own, so a parent's figures are its own plus its sub-categories'.
 *
 * Links that don't hold (a parent that's gone, or in another group) read as top-level, so
 * the tree is always well formed.
 */
class CategoryTree private constructor(entries: List<Entry>) {

    /** What the tree needs to know about a category. */
    data class Entry(
        val id: CategoryId,
        val name: String,
        val groupId: CategoryGroupId,
        val parentId: CategoryId?,
        val isIncome: Boolean = false,
        val hidden: Boolean = false,
    )

    private val byId: Map<CategoryId, Entry>
    private val parents: Map<CategoryId, CategoryId>
    private val kids: Map<CategoryId, List<CategoryId>>
    private val roots: Map<CategoryGroupId, List<CategoryId>>

    /** Every category, in the order given (Actual's sort order). */
    val entries: List<Entry> = entries

    init {
        byId = entries.associateBy { it.id }
        val links = entries.mapNotNull { e ->
            val p = e.parentId?.let(byId::get) ?: return@mapNotNull null
            if (p.id == e.id || p.groupId != e.groupId) null else e.id to p.id
        }.toMap().toMutableMap()
        // Break loops and anything deeper than allowed.
        fun depth(id: CategoryId): Int {
            val seen = mutableSetOf(id)
            var n = TOP_CATEGORY_LEVEL
            var at = links[id]
            while (at != null) {
                if (!seen.add(at)) return Int.MAX_VALUE
                n++
                at = links[at]
            }
            return n
        }
        val tooDeep = links.keys.filter { depth(it) > MAX_CATEGORY_LEVEL }
        tooDeep.forEach(links::remove)
        parents = links
        kids = entries.filter { it.id in links }.groupBy({ links.getValue(it.id) }, { it.id })
        roots = entries.filter { it.id !in links }.groupBy({ it.groupId }, { it.id })
    }

    operator fun get(id: CategoryId): Entry? = byId[id]
    operator fun contains(id: CategoryId) = id in byId

    fun parentOf(id: CategoryId): CategoryId? = parents[id]
    fun childrenOf(id: CategoryId): List<CategoryId> = kids[id].orEmpty()
    fun hasChildren(id: CategoryId) = !kids[id].isNullOrEmpty()

    /** The group's top-level (level 2) categories. */
    fun rootsOf(group: CategoryGroupId): List<CategoryId> = roots[group].orEmpty()

    /** 2 for a group's own categories, down to [MAX_CATEGORY_LEVEL]. */
    fun level(id: CategoryId): Int = TOP_CATEGORY_LEVEL + ancestors(id).size

    /** Parent first, then its parent, up to the top-level category. */
    fun ancestors(id: CategoryId): List<CategoryId> = generateSequence(parents[id]) { parents[it] }.toList()

    /** Names from the top-level category down to this one. */
    fun path(id: CategoryId): List<String> = (ancestors(id).reversed() + id).mapNotNull { byId[it]?.name }

    /** "Food › Groceries › Costco". */
    fun pathLabel(id: CategoryId, separator: String = " › "): String = path(id).joinToString(separator)

    /** [id] and everything under it, [id] first (depth first, in order). */
    fun subtree(id: CategoryId): List<CategoryId> = buildList {
        fun walk(at: CategoryId) {
            add(at)
            kids[at].orEmpty().forEach(::walk)
        }
        walk(id)
    }

    /** 1 for a category with no sub-categories, 2 if it has some, and so on. */
    fun height(id: CategoryId): Int = 1 + (kids[id].orEmpty().maxOfOrNull(::height) ?: 0)

    /** Whether another level fits under [id]. */
    fun canAddChild(id: CategoryId): Boolean = id in byId && level(id) < MAX_CATEGORY_LEVEL

    /**
     * Whether [id] (with its sub-categories) can go under [parent], or to the top of its
     * group with null: no loops, income stays with income, at most level 5.
     */
    fun canMove(id: CategoryId, parent: CategoryId?): Boolean {
        val me = byId[id] ?: return false
        if (parent == null) return true
        val p = byId[parent] ?: return false
        if (parent == id || parent in subtree(id)) return false
        if (p.isIncome != me.isIncome) return false
        return level(parent) + height(id) <= MAX_CATEGORY_LEVEL
    }

    /** The group's categories depth first, each with its level (2..5): for indented lists. */
    fun flatten(group: CategoryGroupId): List<Pair<Entry, Int>> = buildList {
        fun walk(at: CategoryId, level: Int) {
            byId[at]?.let { add(it to level) }
            kids[at].orEmpty().forEach { walk(it, level + 1) }
        }
        rootsOf(group).forEach { walk(it, TOP_CATEGORY_LEVEL) }
    }

    companion object {
        fun of(groups: List<CategoryGroup>) = CategoryTree(
            groups.flatMap { g -> g.categories.map { Entry(it.id, it.name, g.id, it.parentId, g.isIncome, it.hidden) } },
        )

        fun of(month: BudgetMonth) = CategoryTree(
            month.groups.flatMap { g -> g.categories.map { Entry(it.id, it.name, g.id, it.parentId, g.isIncome, it.hidden) } },
        )

        fun ofEntries(entries: List<Entry>) = CategoryTree(entries)

        val Empty = CategoryTree(emptyList())
    }
}

/**
 * A budget category with its sub-categories' figures added: what a parent row shows.
 * [own] is the category itself as Actual has it.
 */
data class BudgetRollup(
    val own: BudgetCategory,
    val level: Int,
    val budgeted: Money,
    val spent: Money,
    val balance: Money,
    val received: Money,
    val hasChildren: Boolean,
) {
    val id get() = own.id
    val name get() = own.name

    /** Like [BudgetCategory.progress], over the whole branch. */
    val progress: Float
        get() {
            val available = balance.minor - spent.minor
            if (available <= 0) return if (spent.isZero) 0f else 1f
            return ((-spent.minor).toFloat() / available).coerceIn(0f, 1f)
        }
    val isOverspent get() = balance.isNegative
}

/**
 * The group's categories depth first, each with its branch's totals. Display arithmetic
 * on Actual's own per-category numbers: nothing is re-derived.
 */
fun BudgetGroup.rollups(tree: CategoryTree = CategoryTree.of(listOf(this.asCategoryGroup()))): List<BudgetRollup> {
    val byId = categories.associateBy { it.id }
    fun sum(id: CategoryId, f: (BudgetCategory) -> Money) = Money(tree.subtree(id).sumOf { byId[it]?.let(f)?.minor ?: 0L })
    return tree.flatten(id).mapNotNull { (e, level) ->
        val c = byId[e.id] ?: return@mapNotNull null
        BudgetRollup(c, level, sum(c.id) { it.budgeted }, sum(c.id) { it.spent }, sum(c.id) { it.balance }, sum(c.id) { it.received }, tree.hasChildren(c.id))
    }
}

private fun BudgetGroup.asCategoryGroup() = CategoryGroup(id, name, isIncome, hidden, categories.map { Category(it.id, it.name, id, isIncome, it.hidden, it.parentId) })

/**
 * One slice of a drill-down ring: a group, a category (with its branch's total), the
 * money a parent category holds itself ([isOwn], shown as "Food (general)"), or the
 * smaller ones folded together ([isOther]). [children] are what tapping it zooms into.
 */
data class DrillNode(
    val key: String,
    val name: String,
    /** Positive: money spent (refunds netted, never below zero). */
    val amount: Long,
    val groupId: CategoryGroupId? = null,
    val categoryId: CategoryId? = null,
    val children: List<DrillNode> = emptyList(),
    val isOwn: Boolean = false,
    val isOther: Boolean = false,
) {
    val canDrill get() = children.size > 1 || children.singleOrNull()?.let { !it.isOwn } == true
}

/**
 * Spending as a tree for the drill-down ring: groups, then each level of categories.
 * [spent] is each category's own spending (negative, as Actual reports it); hidden groups
 * and categories, and anything in [skip], are left out.
 */
fun spendingDrillTree(
    groups: List<CategoryGroup>,
    spent: Map<CategoryId, Long>,
    skip: Set<CategoryId> = emptySet(),
): List<DrillNode> {
    val tree = CategoryTree.of(groups)
    fun node(id: CategoryId, group: CategoryGroupId): DrillNode? {
        val e = tree[id] ?: return null
        if (e.hidden || id in skip) return null
        val own = (-(spent[id] ?: 0L)).coerceAtLeast(0L)
        val kids = tree.childrenOf(id).mapNotNull { node(it, group) }.filter { it.amount > 0 }.sortedByDescending { it.amount }
        val total = own + kids.sumOf { it.amount }
        val children = if (kids.isEmpty()) emptyList() else buildList {
            addAll(kids)
            if (own > 0) add(DrillNode("own:${id.raw}", "${e.name} (general)", own, group, id, isOwn = true))
        }.sortedByDescending { it.amount }
        return DrillNode("c:${id.raw}", e.name, total, group, id, children)
    }
    return groups.filter { !it.isIncome && !it.hidden }.mapNotNull { g ->
        val kids = tree.rootsOf(g.id).mapNotNull { node(it, g.id) }.filter { it.amount > 0 }.sortedByDescending { it.amount }
        val total = kids.sumOf { it.amount }
        if (total <= 0) null else DrillNode("g:${g.id.raw}", g.name, total, g.id, null, kids)
    }.sortedByDescending { it.amount }
}

/** The month's spending as a drill-down tree (see [spendingDrillTree]). */
fun BudgetMonth.spendingDrillTree(skip: Set<CategoryId> = emptySet()): List<DrillNode> =
    spendingDrillTree(groups.map { it.asCategoryGroup() }, groups.flatMap { it.categories }.associate { it.id to it.spent.minor }, skip)

/** At most [max] slices: the biggest, then the rest folded into "Other" (tapping it shows them). */
fun List<DrillNode>.topSlices(max: Int): List<DrillNode> {
    if (size <= max) return this
    val keep = take(max - 1)
    val rest = drop(max - 1)
    return keep + DrillNode("other:${rest.first().key}", "Other", rest.sumOf { it.amount }, isOther = true, children = rest)
}
