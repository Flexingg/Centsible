package app.centsible.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.centsible.core.designsystem.component.BrandDial
import app.centsible.core.designsystem.component.CardShape
import app.centsible.core.designsystem.component.DialSegment
import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.designsystem.component.MoneyText
import app.centsible.core.designsystem.motion.Motion
import app.centsible.core.designsystem.motion.staggeredEntrance
import app.centsible.core.extensions.DashboardContext
import app.centsible.core.extensions.DashboardWidget
import app.centsible.core.extensions.Destination
import app.centsible.core.model.BudgetMonth
import app.centsible.core.model.CategoryTree
import app.centsible.core.model.DrillNode
import app.centsible.core.model.spendingDrillTree
import app.centsible.core.model.topSlices
import app.centsible.core.designsystem.motion.rememberHaptics
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import app.centsible.core.model.Feature
import app.centsible.core.model.Money
import java.time.LocalDate
import javax.inject.Inject

/**
 * The app icon, as the month's budget: each colored segment is a group's spending, as a
 * share of what's budgeted (the whole arc); the needle is how far through the month we
 * are. Spending segments that stop short of the needle mean you're under pace.
 *
 * Tapping a segment (on the ring or in the list) zooms in: it grows to fill the ring while
 * the rest fall away, then its categories sweep in, measured against its own budget. That
 * goes on down through sub-categories as far as there's spending to show; Back (or the
 * breadcrumb) zooms out. A segment with nothing under it opens its transactions.
 */
class BudgetDialWidget @Inject constructor() : DashboardWidget {
    override val id = "core.budget-dial"
    override val title = "Budget dial"
    override val order = 20
    override val requires = setOf(Feature.BudgetEnvelope)

    @Composable
    override fun Content(context: DashboardContext) {
        val month = context.month ?: return
        // Groups and categories the household gave a color keep it; the rest take the icon's colors in order.
        val look = app.centsible.core.designsystem.component.LocalCategoryLook.current
        val roots = remember(month) { month.spendingDrillTree() }
        var path by remember(month.month) { mutableStateOf(listOf<DrillNode>()) }
        // Keep the path pointing at the current nodes when the month's numbers change.
        val livePath = remember(roots, path) { BudgetDial.resolve(roots, path) }
        val dial = BudgetDial.of(month, context.today, livePath, roots).let { d ->
            d.copy(slices = d.slices.map { s -> look.forId(s.lookId)?.color?.let { s.copy(color = Color(it)) } ?: s })
        }
        val haptics = rememberHaptics()
        val scope = rememberCoroutineScope()
        // The zoom: the tapped segment grows to [zoomTo] of the ring while the others shrink away.
        val zoom = remember { Animatable(0f) }
        var zooming by remember { mutableStateOf<Pair<Int, Float>?>(null) }
        var entrance by remember { mutableStateOf(0) }
        fun open(s: BudgetDial.Slice) {
            val node = s.node ?: return
            when {
                zooming != null -> Unit
                node.canDrill -> scope.launch {
                    haptics.tap()
                    val i = dial.slices.indexOf(s)
                    val next = BudgetDial.of(month, context.today, livePath + node, roots)
                    zooming = i to next.slices.sumOf { it.fraction.toDouble() }.toFloat()
                    zoom.snapTo(0f)
                    zoom.animateTo(1f, tween(Motion.MEDIUM, easing = Motion.EaseInOut))
                    path = livePath + node
                    entrance++
                    zooming = null
                }
                else -> BudgetDial.destination(node, month)?.let(context.navigate)
            }
        }
        fun back(to: Int = livePath.size - 1) {
            if (livePath.isEmpty()) return
            haptics.tap()
            path = livePath.take(to.coerceAtLeast(0))
            entrance++
        }
        BackHandler(enabled = livePath.isNotEmpty()) { back() }

        val segments = zooming?.let { (i, to) ->
            val t = zoom.value
            dial.slices.mapIndexed { j, sl ->
                DialSegment(if (j == i) sl.fraction + (to - sl.fraction) * t else sl.fraction * (1f - t), sl.color.copy(alpha = if (j == i) 1f else 1f - t))
            }
        } ?: dial.slices.map { DialSegment(it.fraction, it.color) }

        val cream = Motion.Cream
        val soft = cream.copy(alpha = 0.72f)
        Surface(
            onClick = { if (livePath.isEmpty()) context.navigate(Destination.Budget) else back() },
            modifier = Modifier.fillMaxWidth(),
            shape = CardShape,
            color = Motion.Forest,
            contentColor = cream,
        ) {
            Column(Modifier.padding(20.dp).animateContentSize()) {
                if (livePath.isNotEmpty()) {
                    Breadcrumb(livePath.map { it.name }, cream, soft, onBack = { back() }, onCrumb = { back(it) })
                    Spacer(Modifier.height(8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrandDial(
                        segments = segments,
                        needle = dial.pace,
                        size = 136.dp,
                        key = if (entrance == 0) month.month else "${month.month}/$entrance",
                        spin = entrance == 0,
                        description = dial.description,
                        onSegmentTap = { i -> dial.slices.getOrNull(i)?.let(::open) },
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(dial.title, style = MaterialTheme.typography.labelMedium, color = soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        MoneyText(dial.spent, style = MaterialTheme.typography.headlineMedium, showCents = false, color = cream, animate = true)
                        Text("spent of ${MoneyFormat.format(dial.budgeted, showCents = false)}", style = MaterialTheme.typography.bodyMedium, color = soft)
                        Spacer(Modifier.height(10.dp))
                        Text(dial.status, style = MaterialTheme.typography.labelLarge, color = cream)
                        dial.day?.let { (d, of) -> Text("Day $d of $of", style = MaterialTheme.typography.bodySmall, color = soft) }
                    }
                }
                if (dial.slices.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    dial.slices.forEachIndexed { i, s ->
                        val drills = s.node?.canDrill == true
                        val canOpen = drills || (s.node?.let { BudgetDial.destination(it, month) } != null)
                        Row(
                            Modifier.fillMaxWidth()
                                .then(
                                    if (canOpen) {
                                        Modifier.clickable(onClickLabel = if (drills) "Zoom into ${s.name}" else "See ${s.name} transactions") { open(s) }
                                    } else {
                                        Modifier
                                    },
                                )
                                .padding(vertical = 4.dp)
                                .staggeredEntrance(i + 4, key = "${month.month}/$entrance"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(Modifier.size(10.dp).background(s.color, CircleShape))
                            Text(s.name, style = MaterialTheme.typography.bodyMedium, color = cream, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(MoneyFormat.format(s.amount, showCents = false), style = MaterialTheme.typography.labelLarge, color = soft)
                            if (drills) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = soft, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

/** "‹ Wants › Fun": back, and each level to jump straight back to it. */
@Composable
private fun Breadcrumb(names: List<String>, strong: Color, soft: Color, onBack: () -> Unit, onCrumb: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Zoom out", tint = strong)
        }
        Spacer(Modifier.width(4.dp))
        val crumbs = listOf("All") + names
        crumbs.forEachIndexed { i, name ->
            val last = i == crumbs.lastIndex
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                color = if (last) strong else soft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).then(if (last) Modifier else Modifier.clickable(onClickLabel = "Zoom out to $name") { onCrumb(i) }),
            )
            if (!last) Text("  ›  ", style = MaterialTheme.typography.labelLarge, color = soft)
        }
    }
}

/** What the dial shows, worked out from Actual's month (display arithmetic only). */
internal data class BudgetDial(
    val spent: Money,
    val budgeted: Money,
    val slices: List<Slice>,
    val pace: Float?,
    val day: Pair<Int, Int>?,
    val status: String,
    val title: String = "This month",
) {
    /** [node] is what tapping it opens (zooms into, or its transactions). */
    data class Slice(
        val name: String,
        val amount: Money,
        val fraction: Float,
        val color: Color,
        val groupId: app.centsible.core.model.CategoryGroupId? = null,
        val node: DrillNode? = null,
    ) {
        /** Where a household color would be kept: the group's or the category's id. */
        val lookId: String? get() = node?.takeIf { !it.isOther && !it.isOwn }?.let { it.categoryId?.raw ?: it.groupId?.raw } ?: groupId?.raw
    }

    val description: String
        get() = "Budget dial${if (title != "This month") " for $title" else ""}: ${MoneyFormat.format(spent, showCents = false)} spent of ${MoneyFormat.format(budgeted, showCents = false)}. $status."

    companion object {
        private const val TOP = 4

        /** Follows [path] by key through fresh [roots] (stops where a level no longer exists). */
        fun resolve(roots: List<DrillNode>, path: List<DrillNode>): List<DrillNode> {
            val out = mutableListOf<DrillNode>()
            var level = roots
            for (p in path) {
                val found = level.topSlices(TOP + 1).firstOrNull { it.key == p.key } ?: level.firstOrNull { it.key == p.key } ?: break
                out += found
                level = found.children
            }
            return out
        }

        /** Where a segment with nothing under it goes: its transactions this month. */
        fun destination(node: DrillNode, month: BudgetMonth): Destination? = when {
            node.isOther -> null
            node.categoryId != null -> Destination.TransactionsFor.month(node.name, month.month, categoryId = node.categoryId, ownOnly = node.isOwn)
            node.groupId != null -> Destination.TransactionsFor.month(node.name, month.month, groupId = node.groupId)
            else -> null
        }

        fun of(month: BudgetMonth, today: LocalDate, path: List<DrillNode> = emptyList(), roots: List<DrillNode> = month.spendingDrillTree()): BudgetDial {
            val at = path.lastOrNull()
            val spent: Money
            val budgeted: Money
            if (at == null) {
                spent = month.totalSpent.abs()
                budgeted = month.totalBudgeted
            } else {
                spent = Money(at.amount)
                budgeted = Money(budgetOf(at, month))
            }
            val level = (at?.children ?: roots).topSlices(TOP + 1)
            // The whole arc is the budget; if nothing is budgeted (or spending passed it), it's what was spent.
            val whole = maxOf(budgeted.minor, spent.minor, level.sumOf { it.amount }).toFloat()
            val slices = level.mapIndexed { i, n ->
                val color = if (n.isOther) Motion.Cream.copy(alpha = 0.55f) else Motion.Segments[i % Motion.Segments.size]
                Slice(n.name, Money(n.amount), n.amount / whole, color, n.groupId.takeIf { at == null }, n)
            }.takeIf { whole > 0f } ?: emptyList()

            val ym = java.time.YearMonth.of(month.month.year, month.month.month)
            val days = ym.lengthOfMonth()
            val (pace, day) = when {
                java.time.YearMonth.from(today) == ym -> Pair(today.dayOfMonth.toFloat() / days, today.dayOfMonth to days)
                ym.atDay(1).isBefore(today) -> Pair(1f, null)
                else -> Pair(0f, null)
            }
            val share = if (budgeted.minor > 0) spent.minor.toFloat() / budgeted.minor else null
            val status = when {
                share == null -> if (spent.isZero) "Nothing budgeted yet" else "Nothing budgeted"
                share > 1f -> "${MoneyFormat.format(Money(spent.minor - budgeted.minor), showCents = false)} over budget"
                pace >= 1f -> "Under budget"
                share <= pace + 0.02f -> "On pace"
                else -> "Ahead of pace"
            }
            return BudgetDial(spent, budgeted, slices, pace, day, status, at?.name ?: "This month")
        }

        /** What's budgeted for a node: its group, its whole branch, or (for "general") just itself. */
        private fun budgetOf(node: DrillNode, month: BudgetMonth): Long {
            val tree = CategoryTree.of(month)
            val budgeted = month.groups.flatMap { it.categories }.associate { it.id to it.budgeted.minor }
            return when {
                node.isOther -> node.children.sumOf { budgetOf(it, month) }
                node.isOwn -> node.categoryId?.let { budgeted[it] } ?: 0L
                node.categoryId != null -> tree.subtree(node.categoryId!!).sumOf { budgeted[it] ?: 0L }
                node.groupId != null -> month.groups.firstOrNull { it.id == node.groupId }?.categories?.sumOf { it.budgeted.minor } ?: 0L
                else -> 0L
            }
        }
    }
}
