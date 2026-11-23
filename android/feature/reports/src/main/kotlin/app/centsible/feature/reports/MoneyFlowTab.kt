package app.centsible.feature.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.centsible.core.designsystem.component.CentsibleCard
import app.centsible.core.designsystem.component.LocalCategoryLook
import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.designsystem.component.MoneyText
import app.centsible.core.designsystem.component.StatLabel
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.extensions.Destination
import app.centsible.core.model.MAX_CATEGORY_LEVEL
import app.centsible.core.model.Money
import app.centsible.core.model.Sankey
import app.centsible.core.model.SankeyNode
import app.centsible.core.ui.chart.SankeyChart
import app.centsible.core.ui.chart.SankeyColors

private val DEPTHS = listOf(1 to "Groups", 2 to "2", 3 to "3", 4 to "4", 5 to "All")

/**
 * Money flow: income on the left, through the month's money, into each group and down
 * through its categories as deep as [ReportsUiState.flowDepth]. Tap a box for its amount
 * and share, and to open its transactions.
 */
@Composable
internal fun MoneyFlowTab(state: ReportsUiState, actions: ReportsActions) {
    val colors = CentsibleTheme.colors
    val look = LocalCategoryLook.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CentsibleCard(contentPadding = PaddingValues(16.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1 to "This month", 3 to "3M", 6 to "6M", 12 to "12M").forEach { (m, label) ->
                    FilterChip(selected = state.flowMonths == m, onClick = { actions.flowMonths(m) }, label = { Text(label) })
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Levels", style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
                Spacer(Modifier.width(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DEPTHS.forEach { (d, label) ->
                        FilterChip(selected = state.flowDepth == d, onClick = { actions.flowDepth(d) }, label = { Text(label) })
                    }
                }
            }
        }
        Loaded(state.flow, actions.retry) { flow ->
            val graph = remember(flow, state.flowDepth) { Sankey.of(flow.report, flow.tree, depth = state.flowDepth.coerceIn(1, MAX_CATEGORY_LEVEL)) }
            val palette = listOf(colors.accent, colors.series1, Color(0xFF8E6BD8), colors.warning, colors.series2, colors.textTertiary)
            val colorOf = remember(graph, colors, look) { SankeyColors.of(graph, colors, palette) { id -> look.forId(id)?.color?.let { Color(it) } } }
            val income = flow.report.totalIncome
            val spent = Money(-flow.report.totalSpending.minor)
            CentsibleCard(contentPadding = PaddingValues(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Figure("Came in", income)
                    Figure("Went out", spent)
                    Figure(if (income.minor >= spent.minor) "Saved" else "From savings", Money(kotlin.math.abs(income.minor - spent.minor)))
                }
                Spacer(Modifier.height(12.dp))
                if (graph.isEmpty) {
                    Text("Nothing came in or went out in this period.", style = MaterialTheme.typography.bodyMedium, color = colors.textTertiary)
                } else {
                    SankeyChart(
                        graph = graph,
                        height = 380.dp,
                        money = { MoneyFormat.format(Money(it), showCents = false) },
                        colorOf = colorOf,
                        selected = state.flowSelected,
                        onNodeTap = { actions.selectFlow(it.key) },
                        key = graph to state.flowMonths,
                    )
                    Text(
                        "Tap a box to see it on its own. Wider than the screen? Scroll sideways.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textTertiary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            state.flowSelected?.let { key -> graph.nodes.firstOrNull { it.key == key } }?.let { node ->
                SelectedFlow(node, income, colorOf[node.key] ?: colors.accent, state, actions)
            }
            // Every number the chart shows, readable without color: each group and what's under it.
            if (!graph.isEmpty) {
                CentsibleCard(contentPadding = PaddingValues(0.dp)) {
                    flowRows(graph).forEachIndexed { i, (node, level) ->
                        if (i > 0) HorizontalDivider(color = colors.border)
                        Row(
                            Modifier.fillMaxWidth().clickable { actions.selectFlow(node.key) }.padding(start = 16.dp + 16.dp * level, end = 16.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                node.name,
                                style = if (level == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                MoneyFormat.format(Money(node.value), showCents = false),
                                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Groups (and Saved, Uncategorized) with their categories under them, depth first. */
private fun flowRows(graph: app.centsible.core.model.SankeyGraph): List<Pair<SankeyNode, Int>> {
    val children = graph.links.groupBy({ it.from }, { it.to })
    val byKey = graph.nodes.associateBy { it.key }
    return buildList {
        fun walk(key: String, level: Int) {
            byKey[key]?.let { add(it to level) }
            children[key].orEmpty().forEach { walk(it, level + 1) }
        }
        graph.nodes.filter { it.column == 2 }.forEach { walk(it.key, 0) }
    }
}

@Composable
private fun Figure(label: String, amount: Money) {
    Column {
        StatLabel(label)
        MoneyText(amount, style = MaterialTheme.typography.titleMedium, showCents = false, animate = true)
    }
}

@Composable
private fun SelectedFlow(node: SankeyNode, income: Money, color: Color, state: ReportsUiState, actions: ReportsActions) {
    val colors = CentsibleTheme.colors
    val (start, end) = state.flowRange
    val first = java.time.LocalDate.of(start.year, start.month, 1)
    val last = java.time.LocalDate.of(end.year, end.month, 1).plusMonths(1).minusDays(1)
    val title = if (state.flowMonths == 1) node.name else "${node.name} · last ${state.flowMonths} months"
    val open: Destination.TransactionsFor? = when {
        node.kind == SankeyNode.Kind.Category || (node.kind == SankeyNode.Kind.Income && node.categoryId != null) ->
            Destination.TransactionsFor(title, categoryId = node.categoryId, since = first.toString(), until = last.toString())
        node.kind == SankeyNode.Kind.Group -> Destination.TransactionsFor(title, groupId = node.groupId, since = first.toString(), until = last.toString())
        else -> null
    }
    CentsibleCard(contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(Modifier.size(12.dp).background(color, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(node.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { actions.selectFlow(null) }) { Text("Clear") }
        }
        MoneyText(Money(node.value), style = MaterialTheme.typography.headlineSmall, showCents = false)
        if (income.minor > 0 && node.kind != SankeyNode.Kind.Hub) {
            val share = node.value * 100.0 / income.minor
            Text(
                when (node.kind) {
                    SankeyNode.Kind.Income, SankeyNode.Kind.FromSavings -> "${"%.0f".format(share)}% of what came in"
                    else -> "${"%.1f".format(share)}% of income"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
        }
        if (open != null) {
            TextButton(onClick = { actions.openTransactions(open) }, contentPadding = PaddingValues(0.dp)) { Text("See transactions") }
        }
    }
}
