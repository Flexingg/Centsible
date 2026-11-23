package app.centsible.core.ui.chart

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.centsible.core.designsystem.motion.Motion
import app.centsible.core.designsystem.motion.rememberHaptics
import app.centsible.core.designsystem.motion.reducedMotion
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.DrillNode
import app.centsible.core.model.topSlices
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlinx.coroutines.launch

/**
 * A donut you can zoom into: each slice is a group (then a category, then a sub-category),
 * and tapping one (on the ring or in the legend) grows it round the whole ring while the
 * rest fall away, then its own slices sweep in. Back, or a breadcrumb, zooms out. A slice
 * with nothing under it calls [onLeaf] (to open its transactions).
 */
@Composable
fun DrillRing(
    roots: List<DrillNode>,
    money: (Long) -> String,
    modifier: Modifier = Modifier,
    size: Dp = 132.dp,
    maxSlices: Int = 6,
    palette: List<Color>,
    colorFor: (DrillNode) -> Color? = { null },
    caption: String = "spent",
    rootLabel: String = "All",
    legendRows: Int = maxSlices,
    onLeaf: (DrillNode) -> Unit = {},
) {
    val colors = CentsibleTheme.colors
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val reduced = reducedMotion
    var path by remember(roots) { mutableStateOf(listOf<DrillNode>()) }
    val level = (path.lastOrNull()?.children ?: roots).topSlices(maxSlices)
    val total = level.sumOf { it.amount }.coerceAtLeast(1)
    val sliceColors = level.mapIndexed { i, n -> if (n.isOther) colors.textTertiary else colorFor(n) ?: palette[i % palette.size] }
    // The zoom: [zoomIndex] grows to the whole ring while the rest shrink away.
    val zoom = remember { Animatable(0f) }
    var zoomIndex by remember { mutableStateOf<Int?>(null) }
    var entrance by remember { mutableIntStateOf(0) }
    val sweep = remember(entrance, roots) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(entrance, roots) { if (!reduced) sweep.animateTo(1f, tween(if (entrance == 0) 900 else 600, easing = Motion.EaseOut)) }

    fun open(i: Int) {
        val n = level.getOrNull(i) ?: return
        if (zoomIndex != null) return
        if (!n.canDrill) {
            onLeaf(n)
            return
        }
        scope.launch {
            haptics.tap()
            zoomIndex = i
            zoom.snapTo(0f)
            if (!reduced) zoom.animateTo(1f, tween(Motion.MEDIUM, easing = Motion.EaseInOut))
            path = path + n
            entrance++
            zoomIndex = null
        }
    }
    fun back(to: Int = path.size - 1) {
        if (path.isEmpty()) return
        haptics.tap()
        path = path.take(to.coerceAtLeast(0))
        entrance++
    }
    BackHandler(enabled = path.isNotEmpty()) { back() }

    // Each slice's (start, length) as shares of the ring, mid-zoom included.
    val shares: List<Pair<Float, Float>> = run {
        val base = level.map { it.amount.toFloat() / total }
        val z = zoomIndex
        val lengths = if (z == null) base else base.mapIndexed { j, f -> if (j == z) f + (1f - f) * zoom.value else f * (1f - zoom.value) }
        // The zoomed slice slides to start at the top as it grows.
        var at = if (z == null) 0f else base.take(z).sum() * (1f - zoom.value)
        val starts = MutableList(lengths.size) { 0f }
        if (z == null) {
            var a = 0f
            lengths.forEachIndexed { j, l -> starts[j] = a; a += l }
        } else {
            starts[z] = at
            var before = at
            for (j in z - 1 downTo 0) { before -= lengths[j]; starts[j] = before }
            at += lengths[z]
            for (j in z + 1 until lengths.size) { starts[j] = at; at += lengths[j] }
        }
        starts.zip(lengths)
    }

    Column(modifier.animateContentSize()) {
        if (path.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                IconButton(onClick = { back() }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Zoom out", tint = colors.textPrimary)
                }
                Spacer(Modifier.width(4.dp))
                val crumbs = listOf(rootLabel) + path.map { it.name }
                crumbs.forEachIndexed { i, name ->
                    val last = i == crumbs.lastIndex
                    Text(
                        name,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (last) colors.textPrimary else colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).then(if (last) Modifier else Modifier.clickable(onClickLabel = "Zoom out to $name") { back(i) }),
                    )
                    if (!last) Text("  ›  ", style = MaterialTheme.typography.labelLarge, color = colors.textTertiary)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(
                    Modifier.size(size)
                        .semantics { contentDescription = (path.lastOrNull()?.name ?: rootLabel) + ": " + level.joinToString("; ") { "${it.name} ${money(it.amount)}" } }
                        .pointerInput(level) {
                            detectTapGestures { p ->
                                val c = this.size.width / 2f
                                val dx = p.x - c
                                val dy = p.y - c
                                val r = sqrt(dx * dx + dy * dy)
                                if (r < c * 0.45f || r > c * 1.1f) return@detectTapGestures
                                // 0 at 12 o'clock, clockwise.
                                val at = (((Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 90f) + 360f) % 360f) / 360f
                                var from = 0f
                                level.forEachIndexed { i, n ->
                                    val f = n.amount.toFloat() / total
                                    if (at >= from && at < from + f) return@detectTapGestures open(i)
                                    from += f
                                }
                            }
                        },
                ) {
                    val thickness = this.size.minDimension * 0.15f
                    val inset = thickness / 2 + 2.dp.toPx()
                    val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
                    drawArc(colors.track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(thickness))
                    val gap = if (level.size > 1 && zoomIndex == null) 3f else 0f
                    shares.forEachIndexed { i, (start, length) ->
                        // Slices sweep in one after another.
                        val grown = ((sweep.value * level.size - i * 0.6f) / 1.6f).coerceIn(0f, 1f)
                        val sweepDeg = 360f * length * Motion.EaseOut.transform(grown) - gap
                        if (sweepDeg > 0.3f) {
                            val alpha = if (zoomIndex != null && i != zoomIndex) 1f - zoom.value else 1f
                            drawArc(
                                sliceColors[i].copy(alpha = sliceColors[i].alpha * alpha),
                                -90f + 360f * start + gap / 2, sweepDeg, false, Offset(inset, inset), arcSize,
                                style = Stroke(thickness, cap = StrokeCap.Butt),
                            )
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(money(path.lastOrNull()?.amount ?: roots.sumOf { it.amount }), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary, maxLines = 1)
                    Text(caption, style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                level.take(legendRows).forEachIndexed { i, n ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(onClickLabel = if (n.canDrill) "Zoom into ${n.name}" else "See ${n.name} transactions") { open(i) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(9.dp).background(sliceColors[i], CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(n.name, style = MaterialTheme.typography.bodySmall, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(money(n.amount), style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"), color = colors.textSecondary)
                        if (n.canDrill) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}
