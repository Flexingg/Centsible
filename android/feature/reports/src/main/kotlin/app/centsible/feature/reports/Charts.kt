package app.centsible.feature.reports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.designsystem.motion.Motion
import app.centsible.core.designsystem.motion.rememberEntrance
import app.centsible.core.designsystem.theme.CentsibleTheme
import androidx.compose.ui.graphics.drawscope.clipRect
import app.centsible.core.model.Money
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

// Chart marks follow one spec: bars <= 24dp with a 4dp rounded data end and square
// baseline, a 2dp surface gap between touching bars, 2dp lines, 8dp end dots with a
// 2dp surface ring, hairline recessive gridlines, text in text colors (never series).

/** A clean, tight axis maximum (1, 1.25, 1.5, 2, 2.5, 3, 4, 5, 6 or 8 times a power of ten). */
internal fun niceCeiling(value: Double): Double {
    if (value <= 0) return 1.0
    val magnitude = 10.0.pow(kotlin.math.floor(log10(value)))
    val n = value / magnitude
    val step = listOf(1.0, 1.25, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0).first { it >= n }
    return step * magnitude
}

@Composable
internal fun LegendRow(items: List<Pair<String, Color>>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        items.forEach { (label, color) ->
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = CentsibleTheme.colors.textSecondary)
            Spacer(Modifier.width(16.dp))
        }
    }
}

/**
 * Paired columns per period (two series, same scale, one axis). Tap a period to select
 * it; the selection is announced to the caller, which shows the values.
 */
@Composable
internal fun PairedColumnChart(
    labels: List<String>,
    a: List<Long>,
    b: List<Long>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 200.dp,
    description: String,
) {
    val colors = CentsibleTheme.colors
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontSize = 11.sp, color = colors.textTertiary, fontFeatureSettings = "tnum")
    val maxValue = niceCeiling(max(a.maxOrNull() ?: 0, b.maxOrNull() ?: 0).toDouble())
    // Columns grow from the baseline, each period a beat after the one before.
    val grow = rememberEntrance(key = a to b, durationMillis = Motion.LONG + Motion.STAGGER * labels.size, easing = androidx.compose.animation.core.LinearEasing)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description }
            .pointerInput(labels.size) {
                detectTapGestures { pos ->
                    val left = 44.dp.toPx()
                    val band = (size.width - left) / labels.size
                    val i = ((pos.x - left) / band).toInt()
                    if (i in labels.indices) onSelect(i)
                }
            },
    ) {
        val left = 44.dp.toPx()
        val bottom = size.height - 22.dp.toPx()
        val top = 8.dp.toPx()
        val plotH = bottom - top
        // Gridlines + y labels at 0, 50%, 100%.
        listOf(0.0, 0.5, 1.0).forEach { f ->
            val y = bottom - (plotH * f).toFloat()
            drawLine(colors.chartGrid, Offset(left, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            drawAxisLabel(measurer, MoneyFormat.compact(Money((maxValue * f).toLong())), Offset(0f, y), axisStyle, alignRight = left - 6.dp.toPx())
        }
        val band = (size.width - left) / labels.size
        val bar = minOf(24.dp.toPx(), (band - 2.dp.toPx() - 12.dp.toPx()) / 2)
        val gap = 2.dp.toPx()
        labels.forEachIndexed { i, label ->
            val center = left + band * i + band / 2
            if (selected == i) {
                drawRect(colors.cardMuted, Offset(left + band * i, top), Size(band, plotH))
            }
            val total = (Motion.LONG + Motion.STAGGER * labels.size).toFloat()
            val t = ((grow * total - Motion.STAGGER * i) / Motion.LONG).coerceIn(0f, 1f)
            val g = Motion.Spring.transform(t)
            listOf(a[i] to colors.series1, b[i] to colors.series2).forEachIndexed { k, (v, c) ->
                val h = (plotH * (v / maxValue)).toFloat().coerceAtLeast(0f) * g
                val x = if (k == 0) center - gap / 2 - bar else center + gap / 2
                drawColumn(x, bottom, bar, h, c)
            }
            val layout = measurer.measure(label, axisStyle)
            drawText(layout, topLeft = Offset(center - layout.size.width / 2f, bottom + 6.dp.toPx()))
        }
    }
}

/** Single-series line with a 10% area wash; drag or tap to move the readout. */
@Composable
internal fun LineChart(
    labels: List<String>,
    values: List<Long>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    description: String,
) {
    val colors = CentsibleTheme.colors
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontSize = 11.sp, color = colors.textTertiary, fontFeatureSettings = "tnum")
    if (values.isEmpty()) return
    val minV = values.min().toDouble()
    val maxV = values.max().toDouble()
    val pad = max((maxV - minV) * 0.15, 100_00.0)
    val lo = minV - pad
    val hi = maxV + pad
    fun select(x: Float, width: Float, left: Float) {
        val step = (width - left) / (values.size - 1).coerceAtLeast(1)
        onSelect(((x - left) / step).let { kotlin.math.round(it).toInt() }.coerceIn(0, values.lastIndex))
    }
    // The line traces itself left to right.
    val trace = rememberEntrance(key = values, durationMillis = Motion.LONG, easing = Motion.Dial)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description }
            .pointerInput(values.size) { detectTapGestures { select(it.x, size.width.toFloat(), 8.dp.toPx()) } }
            .pointerInput(values.size) { detectDragGestures { change, _ -> select(change.position.x, size.width.toFloat(), 8.dp.toPx()) } },
    ) {
        val left = 8.dp.toPx()
        val right = size.width - 8.dp.toPx()
        val top = 12.dp.toPx()
        val bottom = size.height - 22.dp.toPx()
        val step = (right - left) / (values.size - 1).coerceAtLeast(1)
        fun pt(i: Int) = Offset(left + step * i, (bottom - (bottom - top) * ((values[i] - lo) / (hi - lo))).toFloat())
        drawLine(colors.chartGrid, Offset(left, bottom), Offset(right, bottom), strokeWidth = 1.dp.toPx())
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
        val focus = selected ?: values.lastIndex
        val p = pt(focus)
        if (selected != null) drawLine(colors.border, Offset(p.x, top), Offset(p.x, bottom), strokeWidth = 1.dp.toPx())
        drawCircle(colors.card, radius = 6.dp.toPx(), center = p) // 2dp surface ring
        drawCircle(colors.series1, radius = 4.dp.toPx(), center = p)
        listOf(0, values.lastIndex).distinct().forEach { i ->
            val layout = measurer.measure(labels[i], axisStyle)
            val x = (pt(i).x - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
            drawText(layout, topLeft = Offset(x, bottom + 6.dp.toPx()))
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawColumn(x: Float, baseline: Float, width: Float, height: Float, color: Color) {
    if (height <= 0f) return
    val r = minOf(4.dp.toPx(), height, width / 2)
    val path = Path().apply {
        addRoundRect(
            RoundRect(
                left = x, top = baseline - height, right = x + width, bottom = baseline,
                topLeftCornerRadius = CornerRadius(r), topRightCornerRadius = CornerRadius(r),
                bottomLeftCornerRadius = CornerRadius.Zero, bottomRightCornerRadius = CornerRadius.Zero,
            ),
        )
    }
    drawPath(path, color)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAxisLabel(
    measurer: TextMeasurer,
    text: String,
    at: Offset,
    style: TextStyle,
    alignRight: Float,
) {
    val layout = measurer.measure(text, style)
    drawText(layout, topLeft = Offset(alignRight - layout.size.width, at.y - layout.size.height / 2f))
}

