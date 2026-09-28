package app.centsible.feature.reports

import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.centsible.core.designsystem.component.CentsibleCard
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.component.LoadingState
import app.centsible.core.designsystem.component.MessageState
import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.designsystem.component.MoneyText
import app.centsible.core.designsystem.component.StatLabel
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.domain.BudgetChanges
import app.centsible.core.domain.InsightsGateway
import app.centsible.core.domain.SelectedBudget
import app.centsible.core.domain.userMessage
import app.centsible.core.model.Money
import app.centsible.core.model.NetWorth
import app.centsible.core.model.NetWorthAccount
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NetWorthUiState(val months: Int = 12, val data: Loadable<NetWorth> = Loadable.Loading, val selected: Int? = null) {
    companion object {
        val RANGES = listOf(6 to "6 months", 12 to "1 year", 36 to "3 years", 60 to "5 years", 120 to "10 years")
    }
}

@HiltViewModel
class NetWorthViewModel @Inject constructor(
    private val insights: InsightsGateway,
    private val selectedBudget: SelectedBudget,
    changes: BudgetChanges,
) : ViewModel() {
    private val state = MutableStateFlow(NetWorthUiState())
    val uiState: StateFlow<NetWorthUiState> = state.asStateFlow()

    init {
        load()
        viewModelScope.launch { changes.changes.collect { load() } }
    }

    fun range(months: Int) {
        state.update { it.copy(months = months, selected = null) }
        load()
    }

    fun select(i: Int) = state.update { it.copy(selected = i) }

    fun load() = viewModelScope.launch {
        val months = state.value.months
        val r = runCatching { insights.netWorth(selectedBudget(), months) }
        if (state.value.months == months) state.update { it.copy(data = r.fold({ v -> Loadable.Ready(v) }, { e -> Loadable.Failed(e.userMessage()) })) }
    }
}

@Composable
fun NetWorthRoute(onBack: () -> Unit, viewModel: NetWorthViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    NetWorthScreen(state, onBack, viewModel::range, viewModel::select) { viewModel.load() }
}

internal fun monthLabel(m: app.centsible.core.model.YearMonth) =
    "${java.time.Month.of(m.month).getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${m.year}"

@Composable
fun NetWorthScreen(state: NetWorthUiState, onBack: () -> Unit = {}, onRange: (Int) -> Unit = {}, onSelect: (Int) -> Unit = {}, onRetry: () -> Unit = {}) {
    val colors = CentsibleTheme.colors
    Scaffold(
        containerColor = colors.canvas,
        topBar = {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("Net worth", style = MaterialTheme.typography.headlineSmall)
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NetWorthUiState.RANGES.forEach { (m, label) -> FilterChip(selected = state.months == m, onClick = { onRange(m) }, label = { Text(label) }) }
                }
            }
            when (val d = state.data) {
                Loadable.Loading -> item { LoadingState(Modifier.height(260.dp)) }
                is Loadable.Failed -> item { MessageState("Couldn't load net worth", d.message, emoji = "🏔️", actionLabel = "Try again", onAction = onRetry, modifier = Modifier.height(320.dp)) }
                is Loadable.Ready -> {
                    val nw = d.value
                    if (nw.points.isEmpty()) return@LazyColumn
                    val index = (state.selected ?: nw.points.lastIndex).coerceIn(0, nw.points.lastIndex)
                    val point = nw.points[index]
                    item {
                        CentsibleCard(contentPadding = PaddingValues(20.dp)) {
                            StatLabel(if (state.selected == null) "Net worth" else monthLabel(point.month))
                            MoneyText(point.netWorth, style = MaterialTheme.typography.displaySmall, animate = true)
                            val first = nw.points.first().netWorth
                            val prev = nw.points.getOrNull(index - 1)?.netWorth
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 4.dp)) {
                                prev?.let { Change("vs month before", point.netWorth - it, it) }
                                if (index > 0) Change("since ${monthLabel(nw.points.first().month)}", point.netWorth - first, first)
                            }
                            Spacer(Modifier.height(12.dp))
                            LineChart(
                                labels = nw.points.map { monthLabel(it.month) },
                                values = nw.points.map { it.netWorth.minor },
                                selected = state.selected,
                                onSelect = onSelect,
                                description = "Net worth from ${MoneyFormat.format(nw.points.first().netWorth)} in ${monthLabel(nw.points.first().month)} to ${MoneyFormat.format(nw.points.last().netWorth)} in ${monthLabel(nw.points.last().month)}",
                            )
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column {
                                    StatLabel("Assets")
                                    MoneyText(point.assets, style = MaterialTheme.typography.titleMedium, showCents = false, color = colors.positive)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    StatLabel("Debts")
                                    MoneyText(point.liabilities.abs(), style = MaterialTheme.typography.titleMedium, showCents = false, color = colors.negative)
                                }
                            }
                        }
                    }
                    val open = nw.accounts.filter { !(it.closed && it.balances.getOrNull(index)?.isZero != false) }
                    val assets = open.filter { !(it.balances.getOrNull(index) ?: Money.Zero).isNegative }.sortedByDescending { it.balances.getOrNull(index)?.minor ?: 0 }
                    val debts = open.filter { (it.balances.getOrNull(index) ?: Money.Zero).isNegative }.sortedBy { it.balances.getOrNull(index)?.minor ?: 0 }
                    if (assets.isNotEmpty()) item { AccountGroup("Assets", assets, index) }
                    if (debts.isNotEmpty()) item { AccountGroup("Debts", debts, index) }
                    item {
                        Text(
                            "Includes off-budget accounts like investments and loans. Balances are at the end of each month.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textTertiary,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Change(label: String, delta: Money, base: Money) {
    val colors = CentsibleTheme.colors
    val pct = if (base.isZero) null else (delta.minor * 100.0 / kotlin.math.abs(base.minor)).roundToInt()
    Column {
        Text(
            MoneyFormat.format(delta).let { if (!delta.isNegative) "+$it" else it } + (pct?.let { " (${if (it >= 0) "+" else ""}$it%)" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
            color = if (delta.isNegative) colors.negative else colors.positive,
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
    }
}

@Composable
private fun AccountGroup(title: String, accounts: List<NetWorthAccount>, index: Int) {
    val colors = CentsibleTheme.colors
    val total = Money(accounts.sumOf { it.balances.getOrNull(index)?.minor ?: 0 })
    CentsibleCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            StatLabel(title, Modifier.weight(1f))
            MoneyText(total.abs(), style = MaterialTheme.typography.titleSmall, showCents = false)
        }
        accounts.forEach { a ->
            HorizontalDivider(color = colors.border)
            val balance = a.balances.getOrNull(index) ?: Money.Zero
            val change = balance - (a.balances.firstOrNull() ?: Money.Zero)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp).clearAndSetSemantics {
                    contentDescription = "${a.name}: ${MoneyFormat.format(balance)}, ${if (change.isNegative) "down" else "up"} ${MoneyFormat.format(change.abs())} over the period"
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(a.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (a.offBudget) "Tracking" else "Budget", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
                }
                Sparkline(a.balances.take(index + 1), Modifier.size(width = 56.dp, height = 24.dp))
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    MoneyText(balance.abs(), style = MaterialTheme.typography.bodyLarge, showCents = false)
                    if (!change.isZero) {
                        // Up is good either way: more in an asset, or a debt closer to zero.
                        val good = !change.isNegative
                        Text(
                            (if (change.isNegative) "−" else "+") + MoneyFormat.format(change.abs()),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (good) colors.positive else colors.negative,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Sparkline(values: List<Money>, modifier: Modifier) {
    val color = CentsibleTheme.colors.series1
    if (values.size < 2) return
    val trace = app.centsible.core.designsystem.motion.rememberEntrance(durationMillis = app.centsible.core.designsystem.motion.Motion.LONG, easing = app.centsible.core.designsystem.motion.Motion.Dial)
    Canvas(modifier) {
        val lo = values.minOf { it.minor }.toFloat()
        val hi = values.maxOf { it.minor }.toFloat()
        val span = (hi - lo).takeIf { it > 0f } ?: 1f
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val p = Offset(step * i, size.height - (v.minor - lo) / span * size.height)
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        clipRect(right = size.width * trace + 2.dp.toPx()) {
            drawPath(path, color, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
