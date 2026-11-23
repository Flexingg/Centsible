package app.centsible.core.model

/**
 * Where the money went, as a Sankey: income categories flow into the month's money, which
 * flows into each top-level group (and "Saved" when not all of it was spent), then down
 * through each level of categories. A parent's bar is its whole branch; what it spent
 * itself simply ends there. Money only, no drawing: [layout] turns it into boxes and
 * bands, which the app draws with Compose and the home-screen widget with a Canvas.
 */
data class SankeyGraph(val nodes: List<SankeyNode>, val links: List<SankeyLink>) {
    val columns: Int get() = (nodes.maxOfOrNull { it.column } ?: -1) + 1
    val isEmpty get() = links.isEmpty()
    fun node(key: String) = nodes.first { it.key == key }

    /** Only spending levels down to [depth] (1 = groups): a full graph shown shallower. */
    fun limitDepth(depth: Int): SankeyGraph {
        val maxColumn = 1 + depth.coerceIn(1, MAX_CATEGORY_LEVEL)
        val kept = nodes.filter { it.column <= maxColumn }
        val keys = kept.map { it.key }.toSet()
        return SankeyGraph(kept, links.filter { it.from in keys && it.to in keys })
    }
}

data class SankeyNode(
    val key: String,
    val name: String,
    val column: Int,
    /** Positive minor units. */
    val value: Long,
    val kind: Kind,
    val groupId: CategoryGroupId? = null,
    val categoryId: CategoryId? = null,
    /** The group it belongs to (for its color); the hub and income have none. */
    val colorKey: String? = null,
) {
    enum class Kind { Income, FromSavings, Hub, Group, Category, Saved, Uncategorized, Other }
}

data class SankeyLink(val from: String, val to: String, val value: Long)

object Sankey {
    const val HUB = "hub"

    /**
     * [depth] is the deepest level of spending to show: 1 = groups only, 2 = their
     * categories, up to 5 (everything). Within each parent, flows under [minShare] of the total are
     * folded into one "Other" node so the chart stays readable.
     */
    fun of(flows: FlowsReport, tree: CategoryTree, depth: Int = MAX_CATEGORY_LEVEL, minShare: Float = 0.015f): SankeyGraph {
        val levels = depth.coerceIn(1, MAX_CATEGORY_LEVEL)
        val nodes = mutableListOf<SankeyNode>()
        val links = mutableListOf<SankeyLink>()

        // Income: roll sub-categories up into their top-level income category.
        val incomeByRoot = linkedMapOf<String, Pair<FlowCategory, Long>>()
        for (f in flows.income.filter { it.amount.minor > 0 }) {
            val root = f.categoryId?.let { id -> tree.ancestors(id).lastOrNull() ?: id }
            val key = root?.let { "in:${it.raw}" } ?: "in:none"
            val name = root?.let { tree[it]?.name } ?: f.name
            val prev = incomeByRoot[key]
            incomeByRoot[key] = (prev?.first ?: f.copy(name = name, categoryId = root)) to ((prev?.second ?: 0L) + f.amount.minor)
        }
        val income = incomeByRoot.values.sumOf { it.second }

        // Spending, as positive amounts per category (refunds netted, never below zero).
        val own = linkedMapOf<CategoryId, Long>()
        val groupOf = mutableMapOf<CategoryId, CategoryGroupId>()
        val groupNames = linkedMapOf<CategoryGroupId, String>()
        var uncategorized = 0L
        for (f in flows.spending) {
            val id = f.categoryId
            val group = id?.let { tree[it]?.groupId } ?: f.groupId
            if (id == null || group == null) {
                uncategorized += -f.amount.minor
                continue
            }
            own[id] = (own[id] ?: 0L) + -f.amount.minor
            groupOf[id] = group
            groupNames.putIfAbsent(group, f.groupName ?: group.raw)
        }
        uncategorized = uncategorized.coerceAtLeast(0)
        // A category the tree doesn't know (just added elsewhere) counts as top-level.
        val strays = own.keys.filter { it !in tree }.groupBy { groupOf.getValue(it) }
        fun rootsOf(g: CategoryGroupId) = tree.rootsOf(g) + strays[g].orEmpty()
        fun total(id: CategoryId): Long = (own[id] ?: 0L).coerceAtLeast(0) + tree.childrenOf(id).sumOf(::total)
        val groupTotals = groupNames.keys.associateWith { g -> rootsOf(g).sumOf(::total) }.filterValues { it > 0 }
        val spending = groupTotals.values.sum() + uncategorized
        if (income <= 0 && spending <= 0) return SankeyGraph(emptyList(), emptyList())

        // Column 0: income (and savings drawn down); column 1: the hub.
        val hubValue = maxOf(income, spending)
        incomeByRoot.entries.sortedByDescending { it.value.second }.forEach { (key, v) ->
            nodes += SankeyNode(key, v.first.name, 0, v.second, SankeyNode.Kind.Income, v.first.groupId, v.first.categoryId)
            links += SankeyLink(key, HUB, v.second)
        }
        if (spending > income) {
            nodes += SankeyNode("from-savings", "From savings", 0, spending - income, SankeyNode.Kind.FromSavings)
            links += SankeyLink("from-savings", HUB, spending - income)
        }
        nodes += SankeyNode(HUB, "Money in", 1, hubValue, SankeyNode.Kind.Hub)

        val cutoff = (hubValue * minShare).toLong()

        // Column 2 onwards: groups, then each level of categories.
        fun branch(parentKey: String, ids: List<CategoryId>, column: Int, level: Int, group: CategoryGroupId) {
            val items = ids.map { it to total(it) }.filter { it.second > 0 }.sortedByDescending { it.second }
            val (big, small) = items.partition { it.second >= cutoff }
            val shown = if (small.size > 1) big else items
            for ((id, value) in shown) {
                val key = "c:${id.raw}"
                nodes += SankeyNode(key, tree[id]?.name ?: id.raw, column, value, SankeyNode.Kind.Category, group, id, "g:${group.raw}")
                links += SankeyLink(parentKey, key, value)
                if (level + 1 <= levels) branch(key, tree.childrenOf(id), column + 1, level + 1, group)
            }
            if (small.size > 1) {
                val key = "other:$parentKey"
                val value = small.sumOf { it.second }
                nodes += SankeyNode(key, "Other", column, value, SankeyNode.Kind.Other, group, null, "g:${group.raw}")
                links += SankeyLink(parentKey, key, value)
            }
        }
        for ((g, value) in groupTotals.entries.sortedByDescending { it.value }) {
            val key = "g:${g.raw}"
            nodes += SankeyNode(key, groupNames.getValue(g), 2, value, SankeyNode.Kind.Group, g, null, key)
            links += SankeyLink(HUB, key, value)
            if (levels >= TOP_CATEGORY_LEVEL) branch(key, rootsOf(g), 3, TOP_CATEGORY_LEVEL, g)
        }
        if (uncategorized > 0) {
            nodes += SankeyNode("uncategorized", "Uncategorized", 2, uncategorized, SankeyNode.Kind.Uncategorized)
            links += SankeyLink(HUB, "uncategorized", uncategorized)
        }
        if (income > spending) {
            nodes += SankeyNode("saved", "Saved", 2, income - spending, SankeyNode.Kind.Saved)
            links += SankeyLink(HUB, "saved", income - spending)
        }
        return SankeyGraph(nodes, links)
    }
}

/** A node's box, and each link's band, in the layout's own units (0..width, 0..height). */
data class SankeyLayout(val width: Float, val height: Float, val nodes: List<Box>, val links: List<Band>) {
    data class Box(val node: SankeyNode, val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
        val midY get() = (y0 + y1) / 2
    }

    /** A band from (x0, y0Top..y0Top+thickness) on the source to (x1, y1Top..) on the target. */
    data class Band(val link: SankeyLink, val from: Box, val to: Box, val x0: Float, val y0: Float, val x1: Float, val y1: Float, val thickness: Float)

    /** The box under a point, for taps. */
    fun hit(x: Float, y: Float, slop: Float = 8f): Box? =
        nodes.firstOrNull { x >= it.x0 - slop && x <= it.x1 + slop && y >= it.y0 - slop && y <= it.y1 + slop }
}

/**
 * Lays the graph out: columns spread evenly across [width], each column's boxes stacked
 * with [gap] between them, sized to one shared scale so the densest column fills
 * [height]. Children start level with where their band leaves the parent, so bands run
 * as flat as they can, and are pushed apart only where they'd overlap.
 */
fun SankeyGraph.layout(width: Float, height: Float, nodeWidth: Float = 10f, gap: Float = 6f): SankeyLayout {
    if (nodes.isEmpty()) return SankeyLayout(width, height, emptyList(), emptyList())
    val cols = columns
    val byColumn = nodes.groupBy { it.column }
    val scale = byColumn.values.minOf { col ->
        val total = col.sumOf { it.value }.toFloat()
        if (total <= 0f) Float.MAX_VALUE else (height - gap * (col.size - 1)).coerceAtLeast(height * 0.3f) / total
    }
    val x = { c: Int -> if (cols <= 1) 0f else c * (width - nodeWidth) / (cols - 1) }
    val incoming = links.groupBy { it.to }
    val outgoing = links.groupBy { it.from }
    val boxes = mutableMapOf<String, SankeyLayout.Box>()
    // Where the next outgoing band leaves each placed node.
    val outCursor = mutableMapOf<String, Float>()

    for (c in 0 until cols) {
        val col = byColumn[c].orEmpty()
        // Desired top: where the band from its (first) source leaves that source.
        val desired = col.map { n ->
            val src = incoming[n.key]?.firstOrNull()?.from
            val want = if (src != null && src in boxes) {
                val at = outCursor[src] ?: boxes.getValue(src).y0
                outCursor[src] = at + (incoming[n.key]!!.first().value * scale)
                at
            } else {
                Float.NaN
            }
            n to want
        }
        // Columns without sources (income) stack from the top, centred.
        val placed = if (desired.all { it.second.isNaN() }) {
            val total = col.sumOf { it.value } * scale + gap * (col.size - 1)
            var y = ((height - total) / 2).coerceAtLeast(0f)
            col.map { n -> (n to y).also { y += n.value * scale + gap } }
        } else {
            // Keep the parents' order; push down where they'd overlap, then up if past the bottom.
            val ordered = desired.map { (n, w) -> n to (if (w.isNaN()) 0f else w) }
            val tops = ordered.map { it.second }.toMutableList()
            for (i in 1 until tops.size) {
                val prevBottom = tops[i - 1] + ordered[i - 1].first.value * scale + gap
                if (tops[i] < prevBottom) tops[i] = prevBottom
            }
            val last = tops.lastIndex
            val overflow = tops[last] + ordered[last].first.value * scale - height
            if (overflow > 0) {
                tops[last] -= overflow
                for (i in last - 1 downTo 0) {
                    val maxTop = tops[i + 1] - gap - ordered[i].first.value * scale
                    if (tops[i] > maxTop) tops[i] = maxTop
                }
            }
            ordered.mapIndexed { i, (n, _) -> n to tops[i].coerceAtLeast(0f) }
        }
        for ((n, y) in placed) {
            boxes[n.key] = SankeyLayout.Box(n, x(c), y, x(c) + nodeWidth, y + (n.value * scale).coerceAtLeast(1f))
        }
    }

    // Bands: stacked on each side in the order of the box at the other end.
    val outOffset = mutableMapOf<String, Float>()
    val inOffset = mutableMapOf<String, Float>()
    val bands = mutableListOf<SankeyLayout.Band>()
    for ((from, list) in outgoing) {
        val src = boxes[from] ?: continue
        for (l in list.sortedBy { boxes[it.to]?.y0 ?: 0f }) {
            val dst = boxes[l.to] ?: continue
            val t = l.value * scale
            val y0 = src.y0 + (outOffset[from] ?: 0f)
            outOffset[from] = (outOffset[from] ?: 0f) + t
            bands += SankeyLayout.Band(l, src, dst, src.x1, y0, dst.x0, 0f, t)
        }
    }
    // Incoming side, ordered by the source's position.
    val withIn = bands.groupBy { it.link.to }.flatMap { (to, list) ->
        list.sortedBy { it.from.y0 }.map { b ->
            val y1 = b.to.y0 + (inOffset[to] ?: 0f)
            inOffset[to] = (inOffset[to] ?: 0f) + b.thickness
            b.copy(y1 = y1)
        }
    }
    val order = links.withIndex().associate { (i, l) -> (l.from to l.to) to i }
    return SankeyLayout(width, height, nodes.mapNotNull { boxes[it.key] }, withIn.sortedBy { order[it.link.from to it.link.to] })
}
