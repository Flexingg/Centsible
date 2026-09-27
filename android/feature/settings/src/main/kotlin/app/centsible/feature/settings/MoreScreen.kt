package app.centsible.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.centsible.core.designsystem.component.CentsibleCard
import app.centsible.core.designsystem.theme.CentsibleTheme

enum class MoreItem(val emoji: String, val title: String, val subtitle: String) {
    Recurring("🔁", "Recurring", "Bills, subscriptions and paychecks"),
    Reports("📊", "Reports", "Cash flow, spending and net worth"),
    Merchants("🏪", "Merchants", "Rename, merge and tidy up"),
    Rules("⚙️", "Rules", "Categorize automatically"),
    Tags("🏷️", "Tags", "#tags from your notes"),
    Settings("👥", "Household & settings", "People, devices and connection"),
}

@Composable
fun MoreScreen(onOpen: (MoreItem) -> Unit, items: List<MoreItem> = MoreItem.entries) {
    val colors = CentsibleTheme.colors
    LazyColumn(Modifier.fillMaxSize().background(colors.canvas), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp)) {
        item { Text("More", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 12.dp)) }
        item {
            CentsibleCard(contentPadding = PaddingValues(0.dp)) {
                items.forEachIndexed { i, item ->
                    if (i > 0) HorizontalDivider(Modifier.padding(start = 68.dp), color = colors.border)
                    Row(Modifier.fillMaxWidth().clickable { onOpen(item) }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).background(colors.cardMuted, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) { Text(item.emoji, fontSize = 18.sp) }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.bodyLarge)
                            Text(item.subtitle, style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
                        }
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.textTertiary)
                    }
                }
            }
        }
    }
}
