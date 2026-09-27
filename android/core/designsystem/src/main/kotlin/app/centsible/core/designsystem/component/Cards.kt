package app.centsible.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.centsible.core.designsystem.theme.CentsibleTheme

val CardShape = RoundedCornerShape(18.dp)

/** The basic white rounded card every section sits on. */
@Composable
fun CentsibleCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CentsibleTheme.colors
    val border = BorderStroke(1.dp, colors.border)
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = CardShape, color = colors.card, border = border) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    } else {
        Surface(modifier = modifier.fillMaxWidth(), shape = CardShape, color = colors.card, border = border) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

/** A card with a title row and optional trailing action ("See all"). */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    CentsibleCard(modifier = modifier, contentPadding = PaddingValues(0.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 10.dp))
            if (action != null && onAction != null) {
                TextButton(onClick = onAction) { Text(action, style = MaterialTheme.typography.labelLarge) }
            }
        }
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/** Small uppercase label above a value, used in summary strips. */
@Composable
fun StatLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = CentsibleTheme.colors.textTertiary,
    )
}
