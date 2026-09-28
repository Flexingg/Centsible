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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import app.centsible.core.designsystem.component.MonthSwitcher
import app.centsible.core.designsystem.component.StatLabel
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.domain.BudgetChanges
import app.centsible.core.domain.InsightsGateway
import app.centsible.core.domain.SelectedBudget
import app.centsible.core.domain.userMessage
import app.centsible.core.model.CategoryTrend
import app.centsible.core.model.Insight
import app.centsible.core.model.Insights
import app.centsible.core.model.YearMonth
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TrendsUiState(val month: YearMonth = thisMonth(), val data: Loadable<Insights> = Loadable.Loading) {
    val canGoBack get() = month > thisMonth().plus(-12)
    val canGoForward get() = month < thisMonth()
}

@HiltViewModel
class TrendsViewModel @Inject constructor(
    private val insights: InsightsGateway,
    private val selectedBudget: SelectedBudget,
    changes: BudgetChanges,
) : ViewModel() {
    private val state = MutableStateFlow(TrendsUiState())
    val uiState: StateFlow<TrendsUiState> = state.asStateFlow()

    init {
        load()
        viewModelScope.launch { changes.changes.collect { load() } }
    }

    fun month(delta: Int) {
        state.update { it.copy(month = it.month.plus(delta), data = Loadable.Loading) }
        load()
    }

    fun load() = viewModelScope.launch {
        val month = state.value.month
        val r = runCatching { insights.insights(selectedBudget(), month) }
        if (state.value.month == month) state.update { it.copy(data = r.fold({ v -> Loadable.Ready(v) }, { e -> Loadable.Failed(e.userMessage()) })) }
    }
}

@Composable
fun TrendsRoute(onBack: () -> Unit, viewModel: TrendsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TrendsScreen(state, onBack = onBack, onMonth = viewModel::month, onRetry = { viewModel.load() })
}

@Composable
fun TrendsScreen(state: TrendsUiState, onBack: () -> Unit = {}, onMonth: (Int) -> Unit = {}, onRetry: () -> Unit = {}) {
    val colors = CentsibleTheme.colors
    Scaffold(
        containerColor = colors.canvas,
        topBar = {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("Trends", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                MonthSwitcher(state.month, onPrevious = { onMonth(-1) }.takeIf { state.canGoBack }, onNext = { onMonth(1) }.takeIf { state.canGoForward })
            }
        },
    ) { padding ->
        when (val d = state.data) {
            Loadable.Loading -> LoadingState(Modifier.padding(padding))
            is Loadable.Failed -> MessageState("Couldn't load trends", d.message, emoji = "📉", actionLabel = "Try again", onAction = onRetry, modifier = Modifier.padding(padding))
            is Loadable.Ready -> {
                val i = d.value
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { PaceCard(i) }
                    if (i.alerts.isEmpty()) {
                        item {
                            Text(
                                "Nothing unusual this month. 👌",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    } else {
                        item { StatLabel("Worth a look", Modifier.padding(start = 4.dp, top = 4.dp)) }
                        items(i.alerts, key = { it.id }) { AlertCard(it) }
                    }
                    val rows = i.categories.filter { it.spent.minor > 0 || it.typical.minor > 0 }
                    if (rows.isNotEmpty()) {
                        item { StatLabel("By category", Modifier.padding(start = 4.dp, top = 4.dp)) }
                        item {
                            CentsibleCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                                rows.forEachIndexed { n, c ->
                                    if (n > 0) HorizontalDivider(color = colors.border)
                                    TrendRow(c, i.complete)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaceCard(i: Insights) {
    val colors = CentsibleTheme.colors
    val over = i.typical.minor > 0 && i.projected.minor > i.typical.minor * 11 / 10
    CentsibleCard(contentPadding = PaddingValues(20.dp)) {
        StatLabel(if (i.complete) "Spent" else "Spent so far")
        MoneyText(i.spent, style = MaterialTheme.typography.displaySmall, animate = true)
        val sentence = when {
            i.typical.isZero -> "Not enough history yet to compare with."
            i.complete -> "Against about ${MoneyFormat.format(i.typical)} in a usual month."
            else -> "On pace for ${MoneyFormat.format(i.projected)} by the end of the month, against about ${MoneyFormat.format(i.typical)} usually."
        }
        Text(sentence, style = MaterialTheme.typography.bodyMedium, color = if (over) colors.warning else colors.textSecondary)
        if (!i.typical.isZero) {
            Spacer(Modifier.height(12.dp))
            ComparisonBar(i.projected.minor, i.typical.minor, height = 10)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (i.complete) "This month" else "Day ${i.daysElapsed} of ${i.daysInMonth}", style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
                Text("The line marks a usual month", style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
            }
        }
    }
}

/** The projected amount as a bar; a tick marks the usual amount. */
@Composable
private fun ComparisonBar(value: Long, usual: Long, height: Int) {
    val colors = CentsibleTheme.colors
    val scale = maxOf(value, usual, 1L) * 1.1f
    val fill = (value / scale).coerceIn(0f, 1f)
    val tick = (usual / scale).coerceIn(0f, 1f)
    val color = when {
        usual <= 0 -> colors.series1
        value > usual * 13 / 10 -> colors.warning
        value < usual * 7 / 10 -> colors.positive
        else -> colors.series1
    }
    Box(Modifier.fillMaxWidth().height(height.dp).background(colors.border, RoundedCornerShape(50)).clearAndSetSemantics { }) {
        Box(Modifier.fillMaxWidth(fill).fillMaxHeight().background(color, RoundedCornerShape(50)))
        if (usual > 0) {
            Row(Modifier.fillMaxSize()) {
                Spacer(Modifier.weight(tick.coerceAtLeast(0.001f)))
                Box(Modifier.width(2.dp).fillMaxHeight().background(colors.textPrimary))
                Spacer(Modifier.weight((1f - tick).coerceAtLeast(0.001f)))
            }
        }
    }
}

@Composable
private fun AlertCard(a: Insight) {
    val colors = CentsibleTheme.colors
    val (emoji, tint) = when (a.kind) {
        Insight.Kind.CategoryPace -> (if (a.severity == Insight.Severity.Good) "🌱" else "📈") to (if (a.severity == Insight.Severity.Good) colors.positive else colors.warning)
        Insight.Kind.UnusualTransaction -> "🧐" to colors.warning
        Insight.Kind.NewMerchant -> "🆕" to colors.accent
        Insight.Kind.PriceChange -> "🏷️" to (if (a.severity == Insight.Severity.Good) colors.positive else colors.warning)
    }
    CentsibleCard(contentPadding = PaddingValues(16.dp)) {
        Row {
            Box(Modifier.width(4.dp).height(40.dp).background(tint, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(12.dp))
            Text(emoji, style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics { })
            Spacer(Modifier.width(12.dp))
            Column {
                Text(a.title, style = MaterialTheme.typography.titleSmall)
                Text(a.detail, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
        }
    }
}

@Composable
private fun TrendRow(c: CategoryTrend, complete: Boolean) {
    val colors = CentsibleTheme.colors
    val change = c.changePct
    val description = buildString {
        append("${c.name}: ${MoneyFormat.format(c.spent)} spent")
        if (!complete) append(", on pace for ${MoneyFormat.format(c.projected)}")
        if (!c.typical.isZero) append(", usually ${MoneyFormat.format(c.typical)}")
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp).clearAndSetSemantics { contentDescription = description }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (change != null && c.typical.minor > 0) {
                val (label, color) = when {
                    change >= 30 -> "+$change%" to colors.warning
                    change <= -30 -> "$change%" to colors.positive
                    change > 0 -> "+$change%" to colors.textSecondary
                    else -> "$change%" to colors.textSecondary
                }
                Text(label, style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.padding(end = 8.dp))
            }
            MoneyText(c.spent, style = MaterialTheme.typography.bodyLarge, showCents = false)
        }
        Spacer(Modifier.height(6.dp))
        ComparisonBar(c.projected.minor, c.typical.minor, height = 6)
        Text(
            if (c.typical.isZero) "New this month" else "Usually ${MoneyFormat.format(c.typical)}" + if (!complete) " · on pace for ${MoneyFormat.format(c.projected)}" else "",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textTertiary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
