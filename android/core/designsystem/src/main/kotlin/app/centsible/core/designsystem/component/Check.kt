package app.centsible.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.centsible.core.designsystem.motion.Motion
import app.centsible.core.designsystem.motion.rememberEntrance
import app.centsible.core.designsystem.theme.CentsibleTheme

/**
 * A checkmark that draws itself: the circle pops in with the spring, then the tick
 * strokes from left to right. [key] replays it (a new backup, say).
 */
@Composable
fun AnimatedCheck(
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    color: Color = CentsibleTheme.colors.positive,
    tick: Color = Color.White,
    key: Any? = Unit,
    delayMillis: Int = 0,
    description: String? = "Done",
    /** A confirm buzz as it lands (for one-off confirmations, not lists of checks). */
    haptic: Boolean = false,
) {
    val haptics = app.centsible.core.designsystem.motion.rememberHaptics()
    if (haptic) androidx.compose.runtime.LaunchedEffect(key) { haptics.confirm() }
    val pop = rememberEntrance(key, delayMillis = delayMillis, durationMillis = Motion.MEDIUM, easing = Motion.Spring)
    val stroke = rememberEntrance(key, delayMillis = delayMillis + Motion.SHORT, durationMillis = Motion.MEDIUM, easing = Motion.EaseOut)
    Canvas(
        modifier.size(size)
            .graphicsLayer { scaleX = pop; scaleY = pop }
            .clearAndSetSemantics { if (description != null) contentDescription = description },
    ) {
        drawCircle(color)
        val w = this.size.width
        val path = Path().apply {
            moveTo(w * 0.28f, w * 0.52f)
            lineTo(w * 0.44f, w * 0.67f)
            lineTo(w * 0.73f, w * 0.36f)
        }
        val measure = PathMeasure().apply { setPath(path, false) }
        val part = Path()
        measure.getSegment(0f, measure.length * stroke, part, true)
        drawPath(part, tick, style = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

