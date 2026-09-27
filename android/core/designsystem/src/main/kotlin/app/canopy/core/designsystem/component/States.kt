package app.canopy.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.canopy.core.designsystem.theme.CanopyTheme

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = CanopyTheme.colors.accent)
    }
}

@Composable
fun MessageState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    emoji: String = "🌿",
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(emoji, style = MaterialTheme.typography.displaySmall)
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = CanopyTheme.colors.textSecondary, textAlign = TextAlign.Center)
            if (actionLabel != null && onAction != null) {
                OutlinedButton(onClick = onAction, modifier = Modifier.padding(top = 8.dp)) { Text(actionLabel) }
            }
        }
    }
}
