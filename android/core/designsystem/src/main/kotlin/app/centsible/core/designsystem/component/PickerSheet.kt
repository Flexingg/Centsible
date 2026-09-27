package app.centsible.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.centsible.core.designsystem.theme.CentsibleTheme

data class PickerItem(
    val key: String,
    val label: String,
    val section: String? = null,
    val supporting: String? = null,
    /** Show a category emoji avatar for this label. */
    val emoji: Boolean = false,
)

/** Bottom sheet with a search box and a sectioned list: categories, accounts, payees. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerSheet(
    title: String,
    items: List<PickerItem>,
    selectedKey: String?,
    onPick: (PickerItem) -> Unit,
    onDismiss: () -> Unit,
    searchable: Boolean = items.size > 8,
) {
    val colors = CentsibleTheme.colors
    var query by remember { mutableStateOf("") }
    val filtered = items.filter { query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.card) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp))
            if (searchable) {
                OutlinedTextField(
                    query,
                    { query = it },
                    placeholder = { Text("Search") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
            }
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                var lastSection: String? = null
                filtered.forEach { item ->
                    if (item.section != null && item.section != lastSection) {
                        val section = item.section
                        item(key = "section-$section") {
                            StatLabel(section, Modifier.padding(top = 14.dp, bottom = 4.dp))
                        }
                        lastSection = section
                    }
                    item(key = item.key) {
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(item) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (item.emoji) {
                                CategoryAvatar(item.label, size = 32.dp)
                                Spacer(Modifier.width(12.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(item.label, style = MaterialTheme.typography.bodyLarge)
                                item.supporting?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.textTertiary) }
                            }
                            if (item.key == selectedKey) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = colors.accent)
                        }
                    }
                }
                if (filtered.isEmpty()) item(key = "empty") {
                    Text("No matches", style = MaterialTheme.typography.bodyMedium, color = colors.textTertiary, modifier = Modifier.padding(vertical = 16.dp))
                }
            }
            Spacer(Modifier.padding(bottom = 12.dp))
        }
    }
}
