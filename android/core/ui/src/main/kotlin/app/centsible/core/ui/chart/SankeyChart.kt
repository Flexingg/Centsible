package app.centsible.core.ui.chart

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.centsible.core.designsystem.motion.Motion
import app.centsible.core.designsystem.motion.reducedMotion
import app.centsible.core.designsystem.theme.CentsibleColors
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.SankeyGraph
import app.centsible.core.model.SankeyLayout
import app.centsible.core.model.SankeyNode
import app.centsible.core.model.layout

/**
 * The colors a Sankey's nodes take: income green, the hub neutral, each group its own
 * color (the household's if it has one, else the palette in order) carried down through
 * its categories, lighter at each level; "Saved" green, "From savings" amber.
 */
object SankeyColors {
    fun of(graph: SankeyGraph, colors: CentsibleColors, palette: List<Color>, chosen: (String) -> Color? = { null }): Map<String, Color> {
        val groups = graph.nodes.filter { it.kind == SankeyNode.Kind.Group }
        val usable = palette.dropLast(1).ifEmpty { palette }
        val groupColor = groups.mapIndexed { i, g -> g.key to (g.groupId?.raw?.let(chosen) ?: usable[i % usable.size]) }.toMap()
        return graph.nodes.associate { n ->
            n.key to when (n.kind) {
                SankeyNode.Kind.Income -> colors.positive
                SankeyNode.Kind.FromSavings -> colors.warning
                SankeyNode.Kind.Hub -> colors.textSecondary
                SankeyNode.Kind.Saved -> colors.positive
                SankeyNode.Kind.Uncategorized -> colors.textTertiary
                SankeyNode.Kind.Group -> groupColor[n.key] ?: colors.accent
                SankeyNode.Kind.Category, SankeyNode.Kind.Other -> {
                    val base = n.categoryId?.raw?.let(chosen) ?: groupColor[n.colorKey] ?: colors.accent
                    if (n.kind == SankeyNode.Kind.Other) base.copy(alpha = 0.45f) else base.copy(alpha = (1f - (n.column - 3) * 0.14f).coerceAtLeast(0.5f))
                }
            }
        }
    }
}

/**
 * Where the money went, as a Sankey: income on the left, through the month's money, out to
 * each group and down its categories. Bands sweep in from the left. Tapping a box calls
 * [onNodeTap]; [selected] brightens that box's bands and dims the rest. Columns are at
 * least [minColumnWidth] apart; on a narrow screen the chart scrolls sideways.
 */
@Composable
fun SankeyChart(
    graph: SankeyGraph,
    modifier: Modifier = Modifier,
    height: Dp = 360.dp,
    money: (Long) -> String,
    colorOf: Map<String, Color>,
    selected: String? = null,
    onNodeTap: ((SankeyNode) -> Unit)? = null,
    minColumnWidth: Dp = 104.dp,
    labels: Boolean = true,
    key: Any? = graph,
) {
    val colors = CentsibleTheme.colors
    val reduced = reducedMotion
    val reveal = remember(key) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(key) { if (!reduced) reveal.animateTo(1f, tween(900, easing = Motion.EaseOut)) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val needed = minColumnWidth * (graph.columns.coerceAtLeast(2) - 1) + 120.dp
        val width = if (maxWidth > needed) maxWidth else needed
        val scroll = rememberScrollState()
        val wPx = with(density) { width.toPx() }
        val hPx = with(density) { height.toPx() }
        val labelRoom = if (labels) with(density) { 96.dp.toPx() } else 0f
        val layout = remember(graph, wPx, hPx, labelRoom) {
            graph.layout(wPx - labelRoom, hPx, nodeWidth = with(density) { 10.dp.toPx() }, gap = with(density) { 8.dp.toPx() })
        }
        val related = remember(selected, graph) { selected?.let { s -> graph.links.filter { it.from == s || it.to == s }.toSet() } }
        val description = remember(graph) {
            graph.nodes.filter { it.kind != SankeyNode.Kind.Hub }.sortedBy { it.column }.joinToString("; ") { "${it.name} ${money(it.value)}" }
        }
        Canvas(
            Modifier
                .then(if (width > maxWidth) Modifier.horizontalScroll(scroll) else Modifier)
                .width(width)
                .height(height)
                .semantics { contentDescription = "Money flow: $description" }
                .then(
                    if (onNodeTap == null) {
                        Modifier
                    } else {
                        Modifier.pointerInput(layout) {
                            detectTapGestures { p -> layout.hit(p.x, p.y, slop = 10.dp.toPx())?.let { onNodeTap(it.node) } }
                        }
                    },
                ),
        ) {
            clipRect(right = size.width * reveal.value) {
                for (band in layout.links) {
                    val on = related == null || band.link in related
                    val color = colorOf[bandColorKey(band)] ?: colors.accent
                    drawBand(band, color.copy(alpha = color.alpha * if (on) 0.38f else 0.10f))
                }
                for (box in layout.nodes) {
                    val dim = selected != null && box.node.key != selected && related?.none { it.from == box.node.key || it.to == box.node.key } == true
                    val c = colorOf[box.node.key] ?: colors.accent
                    drawRoundRect(
                        c.copy(alpha = c.alpha * if (dim) 0.35f else 1f),
                        topLeft = Offset(box.x0, box.y0),
                        size = Size(box.x1 - box.x0, (box.y1 - box.y0).coerceAtLeast(2f)),
                        cornerRadius = CornerRadius(3.dp.toPx()),
                    )
                }
            }
            if (labels && reveal.value > 0.6f) drawLabels(layout, measurer, colors, money, selected, labelRoom)
        }
    }
}

/** A band takes the color of where it goes, except into the hub, where it keeps its income's. */
internal fun bandColorKey(band: SankeyLayout.Band): String =
    if (band.to.node.kind == SankeyNode.Kind.Hub) band.from.node.key else band.to.node.key

private fun DrawScope.drawBand(band: SankeyLayout.Band, color: Color) {
    val mid = (band.x0 + band.x1) / 2
    val path = Path().apply {
        moveTo(band.x0, band.y0)
        cubicTo(mid, band.y0, mid, band.y1, band.x1, band.y1)
        lineTo(band.x1, band.y1 + band.thickness)
        cubicTo(mid, band.y1 + band.thickness, mid, band.y0 + band.thickness, band.x0, band.y0 + band.thickness)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawLabels(layout: SankeyLayout, measurer: TextMeasurer, colors: CentsibleColors, money: (Long) -> String, selected: String?, room: Float) {
    val columns = layout.nodes.maxOfOrNull { it.node.column } ?: 0
    val spacing = if (columns > 0) (layout.width - 10.dp.toPx()) / columns else layout.width
    val nameStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
    val valueStyle = TextStyle(fontSize = 10.sp, color = colors.textSecondary)
    val minHeight = 14.dp.toPx()
    for (box in layout.nodes) {
        val isLast = box.node.column == columns
        if (box.y1 - box.y0 < minHeight && box.node.key != selected) continue
        val maxW = (if (isLast) room else spacing - 18.dp.toPx()).coerceAtLeast(40f).toInt()
        val name = measurer.measure(box.node.name, nameStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = maxW))
        val value = measurer.measure(money(box.node.value), valueStyle, maxLines = 1, constraints = Constraints(maxWidth = maxW))
        val x = box.x1 + 6.dp.toPx()
        val total = name.size.height + value.size.height
        val y = (box.midY - total / 2f).coerceIn(0f, size.height - total)
        // A soft backing keeps text readable where it crosses bands.
        val w = maxOf(name.size.width, value.size.width).toFloat()
        drawRoundRect(colors.card.copy(alpha = 0.78f), Offset(x - 3.dp.toPx(), y - 1.dp.toPx()), Size(w + 6.dp.toPx(), total + 2.dp.toPx()), CornerRadius(4.dp.toPx()))
        drawText(name, topLeft = Offset(x, y))
        drawText(value, topLeft = Offset(x, y + name.size.height))
    }
}
