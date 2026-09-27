package app.centsible.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
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
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), label = "progress")
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
