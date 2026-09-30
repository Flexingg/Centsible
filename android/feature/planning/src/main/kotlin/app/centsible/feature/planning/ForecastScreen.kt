package app.centsible.feature.planning

import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.centsible.core.designsystem.component.CentsibleCard
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.component.LoadingState
import app.centsible.core.designsystem.component.MessageState
import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.designsystem.component.MoneyText
import app.centsible.core.designsystem.component.StatLabel
import app.centsible.core.designsystem.component.toggleRow
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.Forecast
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.max

@Composable
fun ForecastRoute(onBack: () -> Unit, onOpenCalendar: () -> Unit, viewModel: ForecastViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ForecastScreen(
        state,
        ForecastActions(
            back = onBack,
            retry = viewModel::load,
            setDays = viewModel::setDays,
            setIncludeTypical = viewModel::setIncludeTypical,
            select = viewModel::select,
            openCalendar = onOpenCalendar,
        ),
    )
}

data class ForecastActions(
    val back: () -> Unit = {},
    val retry: () -> Unit = {},
    val setDays: (Int) -> Unit = {},
    val setIncludeTypical: (Boolean) -> Unit = {},
    val select: (Int?) -> Unit = {},
    val openCalendar: () -> Unit = {},
)

private val SHORT = DateTimeFormatter.ofPattern("MMM d")
internal fun shortDate(iso: String): String = LocalDate.parse(iso).format(SHORT)

@Composable
fun ForecastScreen(state: ForecastUiState, actions: ForecastActions) {
    val colors = CentsibleTheme.colors
    Scaffold(
        containerColor = colors.canvas,
        topBar = {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("Forecast", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = actions.openCalendar) { Text("Calendar") }
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
                    ForecastUiState.RANGES.forEach { d ->
                        FilterChip(selected = state.days == d, onClick = { actions.setDays(d) }, label = { Text(rangeLabel(d)) })
                    }
                }
            }
            when (val d = state.data) {
                Loadable.Loading -> item { LoadingState(Modifier.height(240.dp)) }
                is Loadable.Failed -> item { MessageState("Couldn't make a forecast", d.message, emoji = "📈", actionLabel = "Try again", onAction = actions.retry, modifier = Modifier.height(320.dp)) }
                is Loadable.Ready -> {
                    val f = d.value
                    item { ForecastSummary(f) }
                    item {
                        CentsibleCard {
                            val index = state.selectedIndex ?: f.days.lastIndex
                            val day = f.days.getOrNull(index)
                            if (day != null) {
                                StatLabel(if (state.selectedIndex == null) "In ${f.days.lastIndex} days" else shortDate(day.date))
                                MoneyText(day.balance, style = MaterialTheme.typography.headlineSmall, color = if (day.balance.isNegative) colors.negative else colors.textPrimary)
                            }
                            Spacer(Modifier.height(8.dp))
                            ForecastChart(f, state.selectedIndex, actions.select)
                            Row(Modifier.fillMaxWidth().toggleRow(state.includeTypical) { actions.setIncludeTypical(it) }.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Include everyday spending", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "Money in and out that isn't a recurring bill, averaged over the last 90 days: ${MoneyFormat.format(f.typicalDaily)} a day.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textSecondary,
                                    )
                                }
                                Switch(checked = state.includeTypical, onCheckedChange = null)
                            }
                        }
                    }
                    item { StatLabel("Coming up", Modifier.padding(start = 4.dp, top = 4.dp)) }
                    val upcoming = f.events.filter { !it.internalTransfer }
                    if (upcoming.isEmpty()) {
                        item {
                            Text(
                                "No recurring bills or income in this window. Add them in Recurring to make the forecast sharper.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    } else {
                        item {
                            CentsibleCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                                upcoming.take(40).forEachIndexed { i, e ->
                                    if (i > 0) HorizontalDivider(color = colors.border)
                                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.width(56.dp)) {
                                            Text(shortDate(e.date), style = MaterialTheme.typography.labelMedium, color = if (e.overdue) colors.negative else colors.textSecondary)
                                            if (e.overdue) Text("Overdue", style = MaterialTheme.typography.labelSmall, color = colors.negative)
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text(e.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            e.accountName?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textTertiary) }
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            Row(verticalAlignment = Alignment.Bottom) {
                                                if (e.estimate != null) Text("≈ ", style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary)
                                                MoneyText(e.amount, style = MaterialTheme.typography.bodyLarge, signed = true, color = if (e.amount.isNegative) colors.textPrimary else colors.positive)
                                            }
                                            f.day(e.date)?.let { Text("then ${MoneyFormat.format(it.balance)}", style = MaterialTheme.typography.labelSmall, color = if (it.balance.isNegative) colors.negative else colors.textTertiary) }
                                        }
                                    }
                                }
                                if (upcoming.size > 40) Text("and ${upcoming.size - 40} more", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary, modifier = Modifier.padding(vertical = 8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun rangeLabel(days: Int) = when (days) {
    180 -> "6 months"
    365 -> "1 year"
    else -> "$days days"
}

@Composable
private fun ForecastSummary(f: Forecast) {
    val colors = CentsibleTheme.colors
    val end = f.days.last().balance
    CentsibleCard(contentPadding = PaddingValues(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                StatLabel("Today")
                MoneyText(f.startingBalance, style = MaterialTheme.typography.titleLarge, showCents = false, animate = true)
            }
            Column(horizontalAlignment = Alignment.End) {
                StatLabel("Lowest")
                MoneyText(f.lowest.balance, style = MaterialTheme.typography.titleLarge, showCents = false, color = if (f.lowest.balance.isNegative) colors.negative else colors.textPrimary, animate = true)
                Text(shortDate(f.lowest.date), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
        }
        val change = end - f.startingBalance
        Text(
            when {
                f.lowest.balance.isNegative -> "Heads up: your accounts could dip below zero around ${shortDate(f.lowest.date)}. Move money or push a bill back."
                change.isNegative -> "Down ${MoneyFormat.format(change.abs())} over the next ${f.days.lastIndex} days."
                else -> "Up ${MoneyFormat.format(change)} over the next ${f.days.lastIndex} days."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (f.lowest.balance.isNegative) colors.negative else colors.textSecondary,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text("Your open budget accounts, from recurring bills and income.", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
    }
}

/** Projected balance line; a dashed zero line when it gets close, the low point marked. Drag to read any day. */
@Composable
private fun ForecastChart(f: Forecast, selected: Int?, onSelect: (Int?) -> Unit) {
    val colors = CentsibleTheme.colors
    val measurer = rememberTextMeasurer()
    val axis = TextStyle(fontSize = 11.sp, color = colors.textTertiary, fontFeatureSettings = "tnum")
    val values = f.days.map { it.balance.minor }
    if (values.size < 2) return
    val lowIndex = f.days.indexOfFirst { it.date == f.lowest.date }.coerceAtLeast(0)
    // Show zero on the scale once the balance gets anywhere near it.
    val minV = if (values.min() < values.max() / 4) minOf(values.min(), 0L).toDouble() else values.min().toDouble()
    val maxV = values.max().toDouble()
    val pad = max((maxV - minV) * 0.12, 50_00.0)
    val lo = minV - pad
    val hi = maxV + pad
    val description = "Projected balance from ${MoneyFormat.format(f.startingBalance)} today to ${MoneyFormat.format(f.days.last().balance)} on ${shortDate(f.days.last().date)}, lowest ${MoneyFormat.format(f.lowest.balance)} on ${shortDate(f.lowest.date)}"
    val trace = app.centsible.core.designsystem.motion.rememberEntrance(key = values, durationMillis = app.centsible.core.designsystem.motion.Motion.LONG, easing = app.centsible.core.designsystem.motion.Motion.Dial)
    fun indexAt(x: Float, width: Float, left: Float) = (((x - left) / ((width - 2 * left) / values.lastIndex))).let { kotlin.math.round(it).toInt() }.coerceIn(0, values.lastIndex)
    Canvas(
        Modifier.fillMaxWidth().height(180.dp)
            .semantics { contentDescription = description }
            .pointerInput(values.size) { detectTapGestures { onSelect(indexAt(it.x, size.width.toFloat(), 8.dp.toPx())) } }
            .pointerInput(values.size) { detectDragGestures(onDragEnd = {}) { change, _ -> onSelect(indexAt(change.position.x, size.width.toFloat(), 8.dp.toPx())) } },
    ) {
        val left = 8.dp.toPx()
        val right = size.width - 8.dp.toPx()
        val top = 12.dp.toPx()
        val bottom = size.height - 22.dp.toPx()
        val step = (right - left) / values.lastIndex
        fun y(v: Double) = (bottom - (bottom - top) * ((v - lo) / (hi - lo))).toFloat()
        fun pt(i: Int) = Offset(left + step * i, y(values[i].toDouble()))
        drawLine(colors.chartGrid, Offset(left, bottom), Offset(right, bottom), strokeWidth = 1.dp.toPx())
        if (lo < 0 && hi > 0) {
            drawLine(colors.negative.copy(alpha = 0.5f), Offset(left, y(0.0)), Offset(right, y(0.0)), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        }
        val line = Path().apply { values.indices.forEach { if (it == 0) moveTo(pt(it).x, pt(it).y) else lineTo(pt(it).x, pt(it).y) } }
        val area = Path().apply {
            addPath(line)
            lineTo(pt(values.lastIndex).x, bottom)
            lineTo(pt(0).x, bottom)
            close()
        }
        clipRect(right = left + (right - left) * trace + 4.dp.toPx()) {
            drawPath(area, colors.series1.copy(alpha = 0.10f))
            drawPath(line, colors.series1, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        if (trace < 1f) return@Canvas
        val low = pt(lowIndex)
        drawCircle(colors.card, radius = 6.dp.toPx(), center = low)
        drawCircle(if (f.lowest.balance.isNegative) colors.negative else colors.warning, radius = 4.dp.toPx(), center = low)
        selected?.let {
            val p = pt(it)
            drawLine(colors.border, Offset(p.x, top), Offset(p.x, bottom), strokeWidth = 1.dp.toPx())
            drawCircle(colors.card, radius = 6.dp.toPx(), center = p)
            drawCircle(colors.series1, radius = 4.dp.toPx(), center = p)
        }
        listOf(0, values.lastIndex).forEach { i ->
            val layout = measurer.measure(if (i == 0) "Today" else shortDate(f.days[i].date), axis)
            val x = (pt(i).x - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
            drawText(layout, topLeft = Offset(x, bottom + 6.dp.toPx()))
        }
    }
}
