package app.canopy.core.designsystem.component

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.canopy.core.model.YearMonth
import java.time.format.TextStyle
import java.util.Locale

fun YearMonth.displayName(locale: Locale = Locale.getDefault()): String =
    java.time.Month.of(month).getDisplayName(TextStyle.FULL, locale) + " " + year

@Composable
fun MonthSwitcher(
    month: YearMonth,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onPrevious?.invoke() }, enabled = onPrevious != null) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Previous month")
        }
        Text(month.displayName(), style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = { onNext?.invoke() }, enabled = onNext != null) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Next month")
        }
    }
}
