package app.centsible.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.centsible.core.designsystem.theme.CentsibleTheme

/**
 * Envelope progress: green while there's room, amber from 90% until fully used, red
 * when overspent.
 */
@Composable
fun BudgetProgressBar(
    progress: Float,
    overspent: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
    color: Color? = null,
) {
    val colors = CentsibleTheme.colors
    // Fills from empty when it first appears, with the brand's springy overshoot; later changes glide.
    val reduced = app.centsible.core.designsystem.motion.reducedMotion
    val fillAnim = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(if (reduced) progress.coerceIn(0f, 1f) else 0f) }
    androidx.compose.runtime.LaunchedEffect(progress) {
        val target = progress.coerceIn(0f, 1f)
        if (reduced) fillAnim.snapTo(target)
        else fillAnim.animateTo(target, androidx.compose.animation.core.tween(app.centsible.core.designsystem.motion.Motion.LONG, easing = app.centsible.core.designsystem.motion.Motion.Spring))
    }
    val animated = fillAnim.value.coerceIn(0f, 1f)
    val fill = color ?: when {
        overspent -> colors.negative
        // Nearly used up is worth a glance; exactly used up (a paid bill) is fine.
        progress >= 0.9f && progress < 0.9999f -> colors.warning
        else -> colors.positive
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height))
            .background(colors.track),
    ) {
        Box(
            Modifier
                .fillMaxWidth(if (overspent) 1f else animated)
                .fillMaxHeight()
                .clip(RoundedCornerShape(height))
                .background(fill),
        )
    }
}
