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

data class AppearanceUiState(
    val data: Loadable<List<CategoryGroup>> = Loadable.Loading,
    val looks: Map<String, Appearance> = emptyMap(),
    val canEdit: Boolean = false,
    val editing: Lookable? = null,
    val message: String? = null,
)

@HiltViewModel
class AppearanceViewModel @Inject constructor(
    private val personal: PersonalGateway,
    private val engine: BudgetEngine,
    private val selectedBudget: SelectedBudget,
    private val sessions: SessionStore,
) : ViewModel() {
    private val state = MutableStateFlow(AppearanceUiState())
    val uiState: StateFlow<AppearanceUiState> = state.asStateFlow()

    init {
        load()
    }

    fun load() = viewModelScope.launch {
        runCatching {
            val b = selectedBudget()
            val groups = async { engine.categoryGroups(b) }
            val looks = async { personal.appearance(b) }
            groups.await().filter { !it.hidden } to looks.await()
        }
            .onSuccess { (g, l) -> state.update { it.copy(data = Loadable.Ready(g), looks = l, canEdit = sessions.current()?.member?.role != MemberRole.Viewer) } }
            .onFailure { e -> state.update { it.copy(data = Loadable.Failed(e.userMessage())) } }
    }

    fun edit(item: Lookable?) = state.update { it.copy(editing = item) }

    fun save(item: Lookable, appearance: Appearance) {
        state.update { it.copy(editing = null, looks = if (appearance.color == null && appearance.emoji == null) it.looks - item.id else it.looks + (item.id to appearance)) }
        viewModelScope.launch {
            runCatching { personal.setAppearance(selectedBudget(), item.id, appearance) }
                .onFailure { e -> state.update { it.copy(message = e.userMessage()) }; load() }
        }
    }
}

/** The palette: the icon's segment colors first, then a few more. */
internal val PALETTE: List<Long> = Motion.Segments.map { it.toArgb().toLong() and 0xFFFFFFFFL } +
    listOf(0xFF5B7CFA, 0xFF2FB39A, 0xFFE2557A, 0xFF8C6A4F)

@Composable
fun AppearanceRoute(onBack: () -> Unit, viewModel: AppearanceViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AppearanceScreen(state, onBack, onEdit = viewModel::edit, onSave = viewModel::save, onRetry = { viewModel.load() })
}

@Composable
fun AppearanceScreen(state: AppearanceUiState, onBack: () -> Unit, onEdit: (Lookable?) -> Unit = {}, onSave: (Lookable, Appearance) -> Unit = { _, _ -> }, onRetry: () -> Unit = {}) {
    val colors = CentsibleTheme.colors
    Scaffold(
        containerColor = colors.canvas,
        topBar = {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("Colors & emoji", style = MaterialTheme.typography.headlineSmall)
            }
        },
    ) { padding ->
        when (val d = state.data) {
            Loadable.Loading -> LoadingState(Modifier.padding(padding))
            is Loadable.Failed -> MessageState("Couldn't load categories", d.message, emoji = "🎨", actionLabel = "Try again", onAction = onRetry, modifier = Modifier.padding(padding))
            is Loadable.Ready -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        "Everyone in the household sees these: on Home's dial (groups), in reports, and next to every transaction.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                d.value.forEach { g ->
                    item(key = g.id.raw) {
                        CentsibleCard(contentPadding = PaddingValues(0.dp)) {
                            LookRow(Lookable(g.id.raw, g.name, isGroup = true), state.looks[g.id.raw], state.canEdit, onEdit)
                            g.categories.filter { !it.hidden }.forEach { c ->
                                HorizontalDivider(Modifier.padding(start = 60.dp), color = colors.border)
                                LookRow(Lookable(c.id.raw, c.name, isGroup = false), state.looks[c.id.raw], state.canEdit, onEdit)
                            }
                        }
                    }
                }
            }
        }
    }
    state.editing?.let { item -> LookDialog(item, state.looks[item.id], onDismiss = { onEdit(null) }, onSave = { onSave(item, it) }) }
}

@Composable
private fun LookRow(item: Lookable, look: Appearance?, canEdit: Boolean, onEdit: (Lookable) -> Unit) {
    val colors = CentsibleTheme.colors
    val color = look?.color?.let { Color(it) }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = canEdit) { onEdit(item) }.padding(horizontal = 16.dp, vertical = if (item.isGroup) 14.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(32.dp).background(color?.copy(alpha = 0.22f) ?: colors.cardMuted, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (item.isGroup && look?.emoji == null) Box(Modifier.size(12.dp).background(color ?: colors.textTertiary, CircleShape))
            else Text(look?.emoji ?: CategoryEmoji.forName(item.name), fontSize = 16.sp)
        }
        Spacer(Modifier.width(12.dp))
        Text(item.name, style = if (item.isGroup) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (look != null) Text("Custom", style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LookDialog(item: Lookable, current: Appearance?, onDismiss: () -> Unit, onSave: (Appearance) -> Unit) {
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
