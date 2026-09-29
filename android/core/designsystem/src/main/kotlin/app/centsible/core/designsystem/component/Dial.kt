package app.centsible.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.centsible.core.designsystem.motion.Motion
import app.centsible.core.designsystem.motion.reducedMotion
import app.centsible.core.designsystem.theme.CentsibleTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** One colored stretch of the dial, as a share (0..1) of the whole arc. */
data class DialSegment(val fraction: Float, val color: Color)

// The icon's geometry, in its 1024-unit artwork: ring radius 300, stroke 116, an arc of
// 270 degrees starting at 45 (bottom right) and running clockwise, gaps of 24 units.
private const val R = 300f
private const val STROKE = 116f
private const val START = 45f
private const val SWEEP = 270f
private const val GAP_DEGREES = 24f / (2 * Math.PI.toFloat() * R) * 360f

/**
 * The Centsible dial (the app icon, as a chart): colored segments around a C-shaped
 * ring, with a gauge needle. It turns in the way the splash does: from -150 degrees
 * with a small overshoot, segments sweeping in one after another, then the needle
 * springs to its place. [key] replays the entrance when the data is new.
 */
@Composable
fun BrandDial(
    segments: List<DialSegment>,
    needle: Float?,
    modifier: Modifier = Modifier,
    size: Dp = 180.dp,
    track: Color = Color.White.copy(alpha = 0.10f),
    needleColor: Color = Motion.Cream,
    halo: Color = Motion.Forest,
    key: Any? = Unit,
    description: String,
) {
    val reduced = reducedMotion
    val turn = remember(key) { Animatable(if (reduced) 0f else -150f) }
    val sweep = remember(key) { Animatable(if (reduced) 1f else 0f) }
    val needleAt = remember(key) { Animatable(if (reduced) (needle ?: 0f) else 0f) }
    val haptics = app.centsible.core.designsystem.motion.rememberHaptics()
    LaunchedEffect(key) {
        if (reduced) return@LaunchedEffect
        coroutineScope {
            launch {
                turn.animateTo(
                    0f,
                    keyframes {
                        durationMillis = 1300
                        -150f at 0 using Motion.Dial
                        9f at 850 using Motion.Dial
                        -3f at 1050 using Motion.Dial
                        0f at 1300
                    },
                )
            }
            launch { sweep.animateTo(1f, tween(1100, delayMillis = 80, easing = LinearEasing)) }
            launch {
                needleAt.animateTo(needle ?: 0f, tween(600, delayMillis = 950, easing = Motion.Spring))
                if (needle != null) haptics.tick() // the needle settles
            }
        }
    }
    // A new needle position after the entrance (the month moves on) glides there.
    LaunchedEffect(needle) {
        if (needle != null && !needleAt.isRunning && sweep.value >= 1f) needleAt.animateTo(needle, tween(Motion.MEDIUM, easing = Motion.Spring))
    }
    Canvas(modifier.size(size).clearAndSetSemantics { contentDescription = description }) {
        val unit = this.size.minDimension / 1024f
        scale(unit, pivot = Offset.Zero) {
            rotate(turn.value, Offset(512f, 512f)) {
                drawRing(track, 0f, 1f)
                // Segments sweep in one after another, like the splash's four.
                var start = 0f
                val n = segments.size.coerceAtLeast(1)
                segments.forEachIndexed { i, s ->
                    val window = 1f / n
                    val grown = ((sweep.value - i * window * 0.6f) / (window * 1.6f)).coerceIn(0f, 1f)
                    drawRing(s.color, start, s.fraction * Motion.EaseOut.transform(grown))
                    start += s.fraction
                }
            }
            if (needle != null && sweep.value > 0.6f) drawNeedle(needleAt.value, needleColor, halo)
        }
    }
}

/** A stretch of the ring from [from] to [from] + [length] (shares of the 270-degree arc). */
private fun DrawScope.drawRing(color: Color, from: Float, length: Float) {
    if (length <= 0f) return
    val gap = if (length < 1f) GAP_DEGREES else 0f
    val sweep = (SWEEP * length - gap).coerceAtLeast(0.5f)
    drawArc(
        color = color,
        startAngle = START + SWEEP * from + (if (length < 1f) gap / 2 else 0f),
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(512f - R, 512f - R),
        size = Size(2 * R, 2 * R),
        style = Stroke(STROKE, cap = StrokeCap.Butt),
    )
}

/** The icon's cent bar, as a gauge needle: from the hub out past the ring, with a halo that cuts the ring. */
private fun DrawScope.drawNeedle(at: Float, color: Color, halo: Color) {
    val angle = START + SWEEP * at.coerceIn(0f, 1.05f)
    rotate(angle - 90f, Offset(512f, 512f)) {
        // Pointing "down" (+y) before the rotation, so angle 90 is straight down.
        drawRoundRect(halo, Offset(512f - 48f, 512f - 48f), Size(96f, 48f + R + STROKE / 2 + 62f), CornerRadius(48f, 48f))
        drawRoundRect(color, Offset(512f - 34f, 512f - 34f), Size(68f, 34f + R + STROKE / 2 + 48f), CornerRadius(34f, 34f))
    }
    drawCircle(halo, 70f, Offset(512f, 512f))
    drawCircle(color, 56f, Offset(512f, 512f))
}

/** Loading: the icon's ring, turning. */
@Composable
fun DialSpinner(modifier: Modifier = Modifier, size: Dp = 44.dp, track: Color = CentsibleTheme.colors.track, turn: Float? = null) {
    val reduced = reducedMotion
    val angle = if (turn != null) {
        turn
    } else if (reduced) {
        0f
    } else {
        val t = rememberInfiniteTransition(label = "spinner")
        val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = Motion.EaseInOut), RepeatMode.Restart), label = "spin")
        a
    }
    Canvas(modifier.size(size).clearAndSetSemantics { contentDescription = "Loading" }) {
        val unit = this.size.minDimension / 1024f
        scale(unit, pivot = Offset.Zero) {
            rotate(angle, Offset(512f, 512f)) {
                drawRing(track, 0f, 1f)
                val shares = listOf(0.3679f, 0.2689f, 0.1910f, 0.1215f) // the icon's own proportions
                var from = 0f
                shares.forEachIndexed { i, s ->
                    drawRing(Motion.Segments[i], from, s)
                    from += s + 0.0127f
                }
            }
        }
    }
}

/**
 * Progress as a ring (goals). Fills with the springy overshoot; reaching 100% gives a
 * single pulse.
 */
@Composable
fun ProgressRing(
    progress: Float,
    size: Dp,
    stroke: Dp,
    color: Color = CentsibleTheme.colors.accent,
    label: String = "${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
) {
    val track = CentsibleTheme.colors.border
    val reduced = reducedMotion
    val fill = remember { Animatable(if (reduced) progress else 0f) }
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(progress) {
        if (reduced) { fill.snapTo(progress); return@LaunchedEffect }
        fill.animateTo(progress.coerceIn(0f, 1f), tween(Motion.LONG, easing = Motion.Spring))
        if (progress >= 1f) {
            pulse.animateTo(1.12f, tween(160, easing = Motion.EaseOut))
            pulse.animateTo(1f, tween(260, easing = Motion.EaseInOut))
        }
    }
    val pct = (progress.coerceIn(0f, 1f) * 100).toInt()
    Box(
        Modifier.size(size).graphicsLayer { scaleX = pulse.value; scaleY = pulse.value }.clearAndSetSemantics { contentDescription = "$pct percent" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val w = stroke.toPx()
            val arc = Size(this.size.width - w, this.size.height - w)
            val topLeft = Offset(w / 2, w / 2)
            drawArc(track, 0f, 360f, false, topLeft, arc, style = Stroke(w))
            drawArc(color, -90f, 360f * fill.value.coerceIn(0f, 1.04f), false, topLeft, arc, style = Stroke(w, cap = StrokeCap.Round))
        }
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * Pull-to-refresh, as the dial: it winds round as you pull, then spins while the
 * refresh runs.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DialPullIndicator(state: androidx.compose.material3.pulltorefresh.PullToRefreshState, refreshing: Boolean, modifier: Modifier = Modifier) {
    val fraction = state.distanceFraction
    val haptics = app.centsible.core.designsystem.motion.rememberHaptics()
    val armed = fraction >= 1f
    LaunchedEffect(armed) { if (armed && !refreshing) haptics.threshold() }
    if (!refreshing && fraction <= 0f) return
    val shown = if (refreshing) 1f else fraction.coerceIn(0f, 1f)
    androidx.compose.material3.Surface(
        modifier = modifier
            .graphicsLayer {
                translationY = shown * 64.dp.toPx() - 40.dp.toPx()
                alpha = shown
                val s = 0.6f + 0.4f * shown
                scaleX = s
                scaleY = s
            },
        shape = androidx.compose.foundation.shape.CircleShape,
        color = CentsibleTheme.colors.card,
        shadowElevation = 4.dp,
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            DialSpinner(size = 30.dp, turn = if (refreshing) null else fraction * 300f)
        }
    }
}
