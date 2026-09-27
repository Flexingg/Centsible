package app.centsible.feature.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.centsible.core.designsystem.component.CentsibleCard
import app.centsible.core.designsystem.component.CategoryAvatar
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.component.LoadingState
import app.centsible.core.designsystem.component.MessageState
import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.designsystem.component.MoneyText
import app.centsible.core.designsystem.component.MonthSwitcher
import app.centsible.core.designsystem.component.StatLabel
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.CashFlowMonth
import app.centsible.core.model.Money
import app.centsible.core.model.NetWorthPoint
import app.centsible.core.model.SpendingReport
import app.centsible.core.model.YearMonth
import app.centsible.core.model.sum
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

private fun YearMonth.short() = Month.of(month).getDisplayName(TextStyle.SHORT, Locale.getDefault())
private fun YearMonth.long() = Month.of(month).getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + year

@Composable
fun ReportsRoute(onBack: () -> Unit, viewModel: ReportsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ReportsScreen(state, onBack, ReportsActions(viewModel::tab, viewModel::range, viewModel::selectMonth, viewModel::selectPoint, viewModel::spendingMonth, { viewModel.refresh() }))
}

data class ReportsActions(
    val tab: (ReportTab) -> Unit = {},
    val range: (Int) -> Unit = {},
    val selectMonth: (Int) -> Unit = {},
    val selectPoint: (Int) -> Unit = {},
    val spendingMonth: (Int) -> Unit = {},
    val retry: () -> Unit = {},
)

@Composable
fun ReportsScreen(state: ReportsUiState, onBack: () -> Unit, actions: ReportsActions = ReportsActions()) {
    val colors = CentsibleTheme.colors
    Scaffold(
        containerColor = colors.canvas,
        topBar = {
            Column {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                    Text("Reports", style = MaterialTheme.typography.titleLarge)
                }
                PrimaryTabRow(selectedTabIndex = state.tab.ordinal, containerColor = colors.canvas) {
                    ReportTab.entries.forEach { t ->
                        Tab(
                            selected = state.tab == t,
                            onClick = { actions.tab(t) },
                            text = { Text(t.label) },
                            selectedContentColor = colors.accent,
                            unselectedContentColor = colors.textSecondary,
                        )
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state.tab) {
                ReportTab.CashFlow -> item { CashFlowTab(state, actions) }
                ReportTab.Spending -> item { SpendingTab(state, actions) }
                ReportTab.NetWorth -> item { NetWorthTab(state, actions) }
            }
        }
    }
}

@Composable
private fun <T> Loaded(data: Loadable<T>, retry: () -> Unit, content: @Composable (T) -> Unit) = when (data) {
    Loadable.Loading -> Box(Modifier.fillMaxWidth().height(240.dp)) { LoadingState() }
    is Loadable.Failed -> Box(Modifier.fillMaxWidth().height(320.dp)) { MessageState("Couldn't build this report", data.message, emoji = "📉", actionLabel = "Try again", onAction = retry) }
    is Loadable.Ready -> content(data.value)
}

@Composable
private fun CashFlowTab(state: ReportsUiState, actions: ReportsActions) {
    val colors = CentsibleTheme.colors
    Loaded(state.cashFlow, actions.retry) { months ->
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val income = months.map { it.income }.sum()
            val expenses = months.map { it.expenses }.sum()
            val net = income + expenses
            val focus = state.selectedMonth?.let { months.getOrNull(it) }
            CentsibleCard(contentPadding = PaddingValues(20.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3, 6, 12).forEach { m -> FilterChip(selected = state.months == m, onClick = { actions.range(m) }, label = { Text("${m}M") }) }
                }
                Spacer(Modifier.height(12.dp))
                StatLabel(if (focus != null) focus.month.long() else if (net.isNegative) "Overspent" else "Saved")
                MoneyText(
                    (focus?.net ?: net).abs(),
                    style = MaterialTheme.typography.displaySmall,
                    showCents = false,
                    color = if ((focus?.net ?: net).isNegative) colors.negative else colors.textPrimary,
                )
                val rateBase = focus?.income ?: income
                if (rateBase.minor > 0) {
                    val rate = ((focus?.net ?: net).minor * 100 / rateBase.minor)
                    Text("Savings rate $rate%", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                }
                Spacer(Modifier.height(16.dp))
                LegendRow(listOf("Income" to colors.series1, "Expenses" to colors.series2))
                Spacer(Modifier.height(8.dp))
                PairedColumnChart(
                    labels = months.map { it.month.short() },
                    a = months.map { it.income.minor },
                    b = months.map { -it.expenses.minor },
                    selected = state.selectedMonth,
                    onSelect = actions.selectMonth,
                    description = "Income and expenses by month. " + months.joinToString("; ") {
                        "${it.month.long()}: income ${MoneyFormat.format(it.income)}, expenses ${MoneyFormat.format(it.expenses.abs())}"
                    },
                )
                if (focus != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Readout("Income", focus.income)
                        Readout("Expenses", focus.expenses.abs())
                        Readout("Net", focus.net)
                    }
                }
            }
            // Table view: every number the chart shows, readable without color.
            CentsibleCard(contentPadding = PaddingValues(0.dp)) {
                TableRow(listOf("Month", "Income", "Expenses", "Net"), header = true)
                months.reversed().forEach { m ->
                    HorizontalDivider(color = colors.border)
                    TableRow(listOf(m.month.long(), MoneyFormat.format(m.income, false), MoneyFormat.format(m.expenses.abs(), false), MoneyFormat.format(m.net, false)))
                }
            }
        }
    }
}

@Composable
private fun Readout(label: String, amount: Money) {
    Column {
        StatLabel(label)
        MoneyText(amount, style = MaterialTheme.typography.titleSmall, showCents = false)
    }
}

@Composable
private fun TableRow(cells: List<String>, header: Boolean = false) {
    val colors = CentsibleTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        cells.forEachIndexed { i, c ->
            Text(
                c,
                style = (if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall).copy(fontFeatureSettings = "tnum"),
                color = if (header) colors.textTertiary else colors.textPrimary,
                textAlign = if (i == 0) androidx.compose.ui.text.style.TextAlign.Start else androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.weight(if (i == 0) 1.4f else 1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SpendingTab(state: ReportsUiState, actions: ReportsActions) {
    val colors = CentsibleTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MonthSwitcher(
            state.spendingMonth,
            onPrevious = { actions.spendingMonth(-1) },
            onNext = { actions.spendingMonth(1) }.takeIf { state.spendingMonth < thisMonth() },
        )
        Loaded(state.spending, actions.retry) { report: SpendingReport ->
            CentsibleCard {
                StatLabel("Spent")
                MoneyText(report.total.abs(), style = MaterialTheme.typography.displaySmall, showCents = false)
                Spacer(Modifier.height(12.dp))
                val max = report.categories.maxOfOrNull { -it.amount.minor }?.coerceAtLeast(1) ?: 1
                report.categories.filter { it.amount.isNegative }.forEach { c ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CategoryAvatar(c.name, size = 30.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row {
                                Text(c.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(MoneyFormat.format(c.amount.abs(), false), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
                            }
                            Spacer(Modifier.height(4.dp))
                            val share = (-c.amount.minor).toFloat() / max
                            Box(Modifier.fillMaxWidth().height(8.dp)) {
                                Box(
                                    Modifier.fillMaxWidth(share.coerceIn(0.02f, 1f)).fillMaxHeight()
                                        .background(colors.series1, RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)),
                                )
                            }
                        }
                    }
                }
                if (report.categories.none { it.amount.isNegative }) Text("No spending this month yet", color = colors.textTertiary)
            }
        }
    }
}

@Composable
private fun NetWorthTab(state: ReportsUiState, actions: ReportsActions) {
    val colors = CentsibleTheme.colors
    Loaded(state.netWorth, actions.retry) { points: List<NetWorthPoint> ->
        if (points.isEmpty()) return@Loaded
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val focus = points[state.selectedPoint ?: points.lastIndex]
            val change = points.last().netWorth - points.first().netWorth
            CentsibleCard(contentPadding = PaddingValues(20.dp)) {
                StatLabel(if (state.selectedPoint != null) focus.month.long() else "Net worth")
                MoneyText(focus.netWorth, style = MaterialTheme.typography.displaySmall, showCents = false)
                Text(
                    "${if (change.isNegative) "▼" else "▲"} ${MoneyFormat.format(change.abs(), false)} over ${points.size} months",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (change.isNegative) colors.negative else colors.positive,
                )
                Spacer(Modifier.height(12.dp))
                LineChart(
                    labels = points.map { it.month.short() + " " + (it.month.year % 100) },
                    values = points.map { it.netWorth.minor },
                    selected = state.selectedPoint,
                    onSelect = actions.selectPoint,
                    description = "Net worth by month. " + points.joinToString("; ") { "${it.month.long()}: ${MoneyFormat.format(it.netWorth, false)}" },
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Readout("Assets", focus.assets)
                    Readout("Liabilities", focus.liabilities.abs())
                }
            }
            CentsibleCard(contentPadding = PaddingValues(0.dp)) {
                TableRow(listOf("Month", "Assets", "Liabilities", "Net worth"), header = true)
                points.reversed().take(6).forEach { p ->
                    HorizontalDivider(color = colors.border)
                    TableRow(listOf(p.month.long(), MoneyFormat.format(p.assets, false), MoneyFormat.format(p.liabilities.abs(), false), MoneyFormat.format(p.netWorth, false)))
                }
            }
        }
    }
}
