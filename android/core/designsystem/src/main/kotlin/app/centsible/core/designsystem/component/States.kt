package app.centsible.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.centsible.core.designsystem.theme.CentsibleTheme

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    // The icon's ring turning over a few shimmering cards shaped like what's coming.
    val colors = CentsibleTheme.colors
    val shimmer = app.centsible.core.designsystem.motion.rememberShimmer()
    val placeholder = androidx.compose.ui.graphics.lerp(colors.track, colors.card, shimmer * 0.6f)
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(132, 92, 92).forEach { h ->
            Box(
                Modifier.fillMaxWidth().height(h.dp)
                    .background(placeholder, androidx.compose.foundation.shape.RoundedCornerShape(20.dp)),
            )
        }
        Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) { DialSpinner() }
    }
}

@Composable
fun MessageState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    emoji: String = "💰",
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(emoji, style = MaterialTheme.typography.displaySmall)
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = CentsibleTheme.colors.textSecondary, textAlign = TextAlign.Center)
            if (actionLabel != null && onAction != null) {
                OutlinedButton(onClick = onAction, modifier = Modifier.padding(top = 8.dp)) { Text(actionLabel) }
            }
        }
    }
}
