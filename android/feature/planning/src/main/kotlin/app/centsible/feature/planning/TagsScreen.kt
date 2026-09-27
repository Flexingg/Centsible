package app.centsible.feature.planning

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.centsible.core.designsystem.component.CentsibleCard
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.component.LoadingState
import app.centsible.core.designsystem.component.MessageState
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.domain.PlanningGateway
import app.centsible.core.domain.SelectedBudget
import app.centsible.core.domain.userMessage
import app.centsible.core.model.Tag
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class TagsViewModel @Inject constructor(private val planning: PlanningGateway, private val selectedBudget: SelectedBudget) : ViewModel() {
    private val state = MutableStateFlow<Loadable<List<Tag>>>(Loadable.Loading)
    val uiState: StateFlow<Loadable<List<Tag>>> = state.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        state.value = runCatching { planning.tags(selectedBudget()) }.fold({ Loadable.Ready(it.sortedBy { t -> t.tag }) }, { Loadable.Failed(it.userMessage()) })
    }

    fun add(tag: String) = viewModelScope.launch { runCatching { planning.createTag(selectedBudget(), tag.trim().removePrefix("#"), null) }; refresh() }
    fun delete(tag: Tag) = viewModelScope.launch { runCatching { planning.deleteTag(selectedBudget(), tag.id) }; refresh() }
}

/** Tags are #words in transaction notes; Actual keeps a list so they can have colors. */
@Composable
fun TagsRoute(onBack: () -> Unit, onSearch: (String) -> Unit, viewModel: TagsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = CentsibleTheme.colors
    var adding by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = colors.canvas,
        topBar = {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("Tags", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { adding = true }) { Text("Add") }
            }
        },
    ) { padding ->
        when (val d = state) {
            Loadable.Loading -> LoadingState(Modifier.padding(padding))
            is Loadable.Failed -> MessageState("Couldn't load tags", d.message, actionLabel = "Try again", onAction = { viewModel.refresh() }, modifier = Modifier.padding(padding))
            is Loadable.Ready -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
                item {
                    Text("Add #tags to transaction notes. Tap a tag to see its transactions.", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, modifier = Modifier.padding(bottom = 12.dp))
                    CentsibleCard(contentPadding = PaddingValues(0.dp)) {
                        if (d.value.isEmpty()) Text("No tags yet", color = colors.textTertiary, modifier = Modifier.padding(16.dp))
                        d.value.forEachIndexed { i, t ->
                            if (i > 0) HorizontalDivider(color = colors.border)
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).background(t.color?.let(::parseHex) ?: colors.textTertiary, CircleShape))
                                Spacer(Modifier.width(12.dp))
                                TextButton(onClick = { onSearch("#${t.tag}") }, modifier = Modifier.weight(1f)) {
                                    Text("#${t.tag}", style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary, modifier = Modifier.fillMaxWidth())
                                }
                                IconButton(onClick = { viewModel.delete(t) }) { Icon(Icons.Rounded.Close, contentDescription = "Delete tag") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("New tag") },
            text = { OutlinedTextField(name, { name = it.replace(" ", "") }, prefix = { Text("#") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { viewModel.add(name); adding = false }, enabled = name.isNotBlank()) { Text("Add") } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}

private fun parseHex(hex: String): Color? = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()
