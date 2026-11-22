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
import app.centsible.core.extensions.DashboardContext
import app.centsible.core.extensions.DashboardWidget
import app.centsible.core.extensions.Destination
import app.centsible.core.model.CategoryId
import app.centsible.core.model.Feature
import app.centsible.core.ui.chart.ChartPreviewCard
import java.time.LocalDate
import java.time.YearMonth
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
        val shown = saved.filter { it.showOnDashboard }
        if (shown.isEmpty()) return

        var glanceable by remember { mutableStateOf<Glanceable?>(null) }
        LaunchedEffect(context.month, context.recentTransactions.size) {
            glanceable = store.read()
        }
        val today = LocalDate.now()

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            shown.forEach { chart ->
                ChartPreviewCard(
                    config = chart.config,
                    data = glanceable,
                    today = today,
                    height = 190.dp,
                    onClick = { context.navigate(destinationFor(chart.config, context)) },
                )
            }
        }
    }

    private fun destinationFor(config: ChartWidgetConfig, context: DashboardContext): Destination = when (config.kind) {
        ChartKind.Pace, ChartKind.Monthly, ChartKind.CashFlow -> Destination.Trends
        ChartKind.Week -> Destination.Transactions
        ChartKind.Categories -> Destination.Budget
        ChartKind.NetWorth -> Destination.NetWorth
        ChartKind.Category -> {
            val cid = config.categoryId
            val name = config.categoryName ?: "Category"
            if (cid != null) Destination.TransactionsFor.month(name, context.month?.month ?: YearMonth.now(), categoryId = CategoryId(cid))
            else Destination.Budget
        }
    }
}
