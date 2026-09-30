package app.centsible.feature.budget

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.centsible.core.designsystem.component.CategoryEmoji
import app.centsible.core.designsystem.component.CentsibleCard
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.component.LoadingState
import app.centsible.core.designsystem.component.MessageState
import app.centsible.core.designsystem.component.StatLabel
import app.centsible.core.designsystem.motion.Motion
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.domain.BudgetEngine
import app.centsible.core.domain.PersonalGateway
import app.centsible.core.domain.SelectedBudget
import app.centsible.core.domain.SessionStore
import app.centsible.core.domain.userMessage
import app.centsible.core.model.Appearance
import app.centsible.core.model.CategoryGroup
import app.centsible.core.model.Role as MemberRole
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Something that can have a color and emoji: a group or a category. */
data class Lookable(val id: String, val name: String, val isGroup: Boolean)

/** The palette: the icon's segment colors first, then a few more. */
internal val PALETTE: List<Long> = Motion.Segments.map { it.toArgb().toLong() and 0xFFFFFFFFL } +
    listOf(0xFF5B7CFA, 0xFF2FB39A, 0xFFE2557A, 0xFF8C6A4F)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LookDialog(item: Lookable, current: Appearance?, onDismiss: () -> Unit, onSave: (Appearance) -> Unit) {
    val colors = CentsibleTheme.colors
    var emoji by remember { mutableStateOf(current?.emoji.orEmpty()) }
    var color by remember { mutableStateOf(current?.color) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    emoji,
                    { t -> emoji = firstGrapheme(t) },
                    label = { Text("Emoji") },
                    placeholder = { Text(CategoryEmoji.forName(item.name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                StatLabel("Color")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PALETTE.forEachIndexed { i, c ->
                        val on = color == c
                        Box(
                            Modifier.size(36.dp)
                                .background(Color(c), CircleShape)
                                .then(if (on) Modifier.border(3.dp, colors.textPrimary, CircleShape) else Modifier)
                                .clickable(role = Role.RadioButton) { color = if (on) null else c }
                                .semantics { contentDescription = "Color ${i + 1}"; selected = on },
                            contentAlignment = Alignment.Center,
                        ) { if (on) Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(Appearance(color, emoji.ifBlank { null })) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onSave(Appearance()) }) { Text("Use default") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Keeps one emoji (with its joiners and skin tones), whatever gets pasted or typed. */
private fun firstGrapheme(s: String): String {
    if (s.isEmpty()) return s
    val it = java.text.BreakIterator.getCharacterInstance()
    it.setText(s)
    // Typing a second emoji replaces the first.
    val last = it.last()
    val start = it.previous().coerceAtLeast(0)
    return s.substring(start, last)
}
