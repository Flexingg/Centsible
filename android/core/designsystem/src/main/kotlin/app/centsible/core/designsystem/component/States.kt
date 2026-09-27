package app.centsible.core.designsystem.component

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
import app.centsible.core.designsystem.theme.CentsibleTheme

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = CentsibleTheme.colors.accent)
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
