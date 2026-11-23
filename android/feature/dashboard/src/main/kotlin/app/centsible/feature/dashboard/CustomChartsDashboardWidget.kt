package app.centsible.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import app.centsible.core.domain.ChartKind
import app.centsible.core.domain.ChartWidgetConfig
import app.centsible.core.domain.Glanceable
import app.centsible.core.domain.GlanceableStore
import app.centsible.core.domain.WidgetSettings
import app.centsible.core.domain.WidgetTheme
import app.centsible.core.extensions.DashboardContext
import app.centsible.core.extensions.DashboardWidget
import app.centsible.core.extensions.Destination
import app.centsible.core.model.CategoryId
import app.centsible.core.model.Feature
import app.centsible.core.model.YearMonth
import app.centsible.core.ui.chart.ChartPreviewCard
import app.centsible.core.ui.chart.DrillRing
import app.centsible.core.ui.chart.SankeyChart
import app.centsible.core.ui.chart.SankeyColors
import app.centsible.core.designsystem.component.LocalCategoryLook
import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.BudgetMonth
import app.centsible.core.model.MAX_CATEGORY_LEVEL
import app.centsible.core.model.Money
import app.centsible.core.model.SankeyGraph
import app.centsible.core.model.spendingDrillTree
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import java.time.LocalDate
import javax.inject.Inject

/** Renders user-created custom charts pinned to the in-app Home dashboard. */
class CustomChartsDashboardWidget @Inject constructor(
    private val settings: WidgetSettings,
    private val store: GlanceableStore,
) : DashboardWidget {
    override val id = "core.custom_charts"
    override val title = "Custom charts"
    override val order = 25
    override val requires = setOf(Feature.TransactionsRead)

    @Composable
    override fun Content(context: DashboardContext) {
        val saved by settings.savedCharts.collectAsState(initial = emptyList())
        val widgetTheme by settings.widgetTheme.collectAsState(initial = WidgetTheme.Classic)
        val shown = saved.filter { it.showOnDashboard }
        if (shown.isEmpty()) return

        var glanceable by remember { mutableStateOf<Glanceable?>(null) }
        LaunchedEffect(context.month, context.recentTransactions.size) {
            glanceable = store.read()
        }
        val today = LocalDate.now()

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            shown.forEach { chart ->
                val month = context.month
                when {
                    // In the app these two are live: the ring zooms into groups and categories, the Sankey animates.
                    chart.config.kind == ChartKind.Categories && chart.config.categoryIds.isEmpty() && month != null ->
                        DrillRingCard(chart.config, month, context)
                    chart.config.kind == ChartKind.Sankey && glanceable?.sankey?.isEmpty == false ->
                        SankeyCard(chart.config, glanceable!!.sankey!!, context)
                    else -> ChartPreviewCard(
                        config = chart.config,
                        data = glanceable,
                        today = today,
                        height = 190.dp,
                        widgetTheme = widgetTheme,
                        onClick = { context.navigate(destinationFor(chart.config, context)) },
                    )
                }
            }
        }
    }

    private fun destinationFor(config: ChartWidgetConfig, context: DashboardContext): Destination = when (config.kind) {
        ChartKind.Pace, ChartKind.Monthly, ChartKind.CashFlow -> Destination.Trends
        ChartKind.Week -> Destination.Transactions
        ChartKind.Categories -> Destination.Budget
        ChartKind.NetWorth -> Destination.NetWorth
        ChartKind.Sankey -> Destination.MoneyFlow
        ChartKind.Category -> {
            val cid = config.categoryId
            val name = config.categoryName ?: "Category"
            val ym = context.month?.month ?: LocalDate.now().let { YearMonth.of(it.year, it.monthValue) }
            if (cid != null) Destination.TransactionsFor.month(name, ym, categoryId = CategoryId(cid))
            else Destination.Budget
        }
    }
}

@Composable
private fun ChartCard(title: String, onClick: (() -> Unit)?, content: @Composable () -> Unit) {
    val colors = CentsibleTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(colors.card)
            .border(1.dp, colors.border, RoundedCornerShape(24.dp))
            .let { m -> onClick?.let { m.clickable(onClick = it) } ?: m }
            .padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/** "Where it went", zoomable: groups, then their categories, then sub-categories. */
@Composable
private fun DrillRingCard(config: ChartWidgetConfig, month: BudgetMonth, context: DashboardContext) {
    val colors = CentsibleTheme.colors
    val look = LocalCategoryLook.current
    val roots = remember(month) { month.spendingDrillTree() }
    val label = java.time.Month.of(month.month.month).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
    ChartCard(config.title?.takeIf { it.isNotBlank() } ?: "Where it went · $label", onClick = null) {
        if (roots.isEmpty()) {
            Text("Nothing spent yet this month.", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
            return@ChartCard
        }
        DrillRing(
            roots = roots,
            money = { MoneyFormat.compact(Money(it)) },
            palette = listOf(colors.accent, colors.series1, colors.positive, colors.warning, Color(0xFF8E6BD8), colors.series2),
            colorFor = { n -> (n.categoryId?.raw ?: n.groupId?.raw)?.takeIf { !n.isOwn && !n.isOther }?.let { look.forId(it)?.color }?.let { Color(it) } },
            legendRows = if (config.showLegend) 6 else 0,
            onLeaf = { n ->
                val d = when {
                    n.categoryId != null -> Destination.TransactionsFor.month(n.name, month.month, categoryId = n.categoryId, ownOnly = n.isOwn)
                    n.groupId != null -> Destination.TransactionsFor.month(n.name, month.month, groupId = n.groupId)
                    else -> null
                }
                d?.let(context.navigate)
            },
        )
    }
}

/** Money flow on Home: this month's Sankey, drawn in; tapping opens the full report. */
@Composable
private fun SankeyCard(config: ChartWidgetConfig, full: SankeyGraph, context: DashboardContext) {
    val colors = CentsibleTheme.colors
    val look = LocalCategoryLook.current
    val graph = remember(full, config.depth) { full.limitDepth(config.depth ?: MAX_CATEGORY_LEVEL) }
    val palette = listOf(colors.accent, colors.series1, Color(0xFF8E6BD8), colors.warning, colors.series2, colors.textTertiary)
    val colorOf = remember(graph, colors, look) { SankeyColors.of(graph, colors, palette) { id -> look.forId(id)?.color?.let { Color(it) } } }
    ChartCard(config.title?.takeIf { it.isNotBlank() } ?: "Money flow · this month", onClick = { context.navigate(Destination.MoneyFlow) }) {
        SankeyChart(
            graph = graph,
            height = 220.dp,
            money = { MoneyFormat.compact(Money(it)) },
            colorOf = colorOf,
            minColumnWidth = 48.dp,
            labels = config.showLegend,
        )
    }
}
