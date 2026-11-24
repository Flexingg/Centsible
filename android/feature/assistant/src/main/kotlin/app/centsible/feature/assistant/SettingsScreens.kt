package app.centsible.feature.assistant

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.centsible.core.assistant.AssistantEffort
import app.centsible.core.assistant.AssistantModel
import app.centsible.core.assistant.ConversationSummary
import app.centsible.core.assistant.ToolGroup
import app.centsible.core.designsystem.component.CentsibleCard
import app.centsible.core.designsystem.component.LoadingState
import app.centsible.core.designsystem.component.MessageState
import app.centsible.core.designsystem.component.StatLabel
import app.centsible.core.designsystem.component.toggleRow
import app.centsible.core.designsystem.theme.CentsibleTheme

@Composable
private fun TopBar(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        trailing()
    }
}

// ── Past chats ──

@Composable
fun AssistantChatsRoute(onBack: () -> Unit, onOpen: (String) -> Unit, viewModel: ChatsViewModel = hiltViewModel()) {
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    AssistantChatsScreen(chats, onBack, onOpen, viewModel::delete, viewModel::deleteAll)
}

@Composable
fun AssistantChatsScreen(chats: List<ConversationSummary>?, onBack: () -> Unit, onOpen: (String) -> Unit, onDelete: (String) -> Unit, onDeleteAll: () -> Unit) {
    val colors = CentsibleTheme.colors
    var confirmAll by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = colors.canvas,
        topBar = { TopBar("Past chats", onBack) { if (!chats.isNullOrEmpty()) TextButton(onClick = { confirmAll = true }) { Text("Delete all") } } },
    ) { padding ->
        when {
            chats == null -> LoadingState(Modifier.padding(padding))
            chats.isEmpty() -> MessageState("No chats yet", "Your conversations with the assistant are kept here, on this phone.", emoji = "💬", modifier = Modifier.padding(padding))
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(chats, key = { it.id }) { c ->
                    CentsibleCard(onClick = { onOpen(c.id) }, contentPadding = PaddingValues(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                Text(c.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    relative(c.updatedAt) + if (c.awaitingApproval) " · changes waiting for you" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (c.awaitingApproval) colors.accent else colors.textTertiary,
                                )
                            }
                            IconButton(onClick = { onDelete(c.id) }) { Icon(Icons.Rounded.Delete, contentDescription = "Delete ${c.title}", tint = colors.textTertiary) }
                        }
                    }
                }
            }
        }
    }
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Delete every chat?") },
            text = { Text("They're only on this phone, so they can't be brought back. Saved notes stay.") },
            confirmButton = { TextButton(onClick = { onDeleteAll(); confirmAll = false }) { Text("Delete", color = colors.negative) } },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel") } },
        )
    }
}

internal fun relative(at: Long, now: Long = System.currentTimeMillis()): String {
    val mins = (now - at) / 60_000
    return when {
        mins < 1 -> "Just now"
        mins < 60 -> "$mins min ago"
        mins < 24 * 60 -> "${mins / 60} h ago"
        mins < 7 * 24 * 60 -> "${mins / (24 * 60)} d ago"
        else -> java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
    }
}

// ── Settings ──

@Composable
fun AssistantSettingsRoute(onBack: () -> Unit, viewModel: AssistantSettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AssistantSettingsScreen(
        state,
        AssistantSettingsActions(
            back = onBack,
            saveKey = viewModel::saveKey,
            removeKey = { viewModel.removeKey() },
            model = { viewModel.model(it) },
            effort = { viewModel.effort(it) },
            permission = { g, on -> viewModel.permission(g, on) },
            codeExecution = { viewModel.codeExecution(it) },
            webSearch = { viewModel.webSearch(it) },
            memory = { viewModel.memoryOn(it) },
            deleteNote = { viewModel.deleteNote(it) },
            clearNotes = { viewModel.clearNotes() },
            clearChats = { viewModel.clearChats() },
        ),
    )
}

@Composable
fun AssistantSettingsScreen(state: AssistantSettingsUiState, actions: AssistantSettingsActions) {
    val colors = CentsibleTheme.colors
    var confirm by remember { mutableStateOf<String?>(null) }
    Scaffold(containerColor = colors.canvas, topBar = { TopBar("Assistant", actions.back) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "key") { KeySection(state, actions) }

            item(key = "model") {
                Section("Default model") {
                    AssistantModel.entries.forEach { m ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = state.settings.model == m, role = Role.RadioButton) { actions.model(m) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = state.settings.model == m, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(m.label, style = MaterialTheme.typography.bodyLarge)
                                Text(m.blurb, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                            }
                        }
                    }
                    Text("New chats start on this one. To change the model of a chat, tap its name at the top of the chat.", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary, modifier = Modifier.padding(top = 4.dp))
                }
            }

            item(key = "effort") {
                Section("How hard it thinks") {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(AssistantEffort.Low, AssistantEffort.Medium, AssistantEffort.High, AssistantEffort.Max).forEach { e ->
                            FilterChip(selected = state.settings.effort == e, onClick = { actions.effort(e) }, label = { Text(e.label) })
                        }
                    }
                    Text("Deeper thinking gives more careful plans, but takes longer and uses more of your API credit.", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, modifier = Modifier.padding(top = 6.dp))
                }
            }

            item(key = "permissions") {
                Section("Permissions") {
                    Text(
                        "The assistant can always read everything in your budget except bank sync. Turn on what it may change. Each change is shown to you as a card first, and only happens when you tap Approve.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    if (!state.canWrite) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                            Icon(Icons.Rounded.Lock, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("You're a viewer in this household, so the assistant can't change anything for you.", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                        }
                    }
                    ToolGroup.entries.forEachIndexed { i, g ->
                        if (i > 0) HorizontalDivider(color = colors.border)
                        Toggle(g.title, g.description, checked = g in state.settings.writable, enabled = state.canWrite) { actions.permission(g, it) }
                    }
                }
            }

            item(key = "tools") {
                Section("Tools") {
                    Toggle("Calculations", "Runs projections, amortization and other math in Anthropic's sandbox, for exact answers.", state.settings.codeExecution, enabled = !state.settings.webSearch) { actions.codeExecution(it) }
                    HorizontalDivider(color = colors.border)
                    Toggle("Web search", "Looks up things like current interest rates or tax brackets. Its searches go to a search engine, so it's off by default. Includes calculations.", state.settings.webSearch) { actions.webSearch(it) }
                }
            }

            item(key = "memory") {
                Section("Memory") {
                    Toggle("Remember things between chats", "Short notes like your goals or when you're paid, kept on this phone.", state.settings.memory) { actions.memory(it) }
                    if (state.notes.isEmpty()) {
                        Text("No notes yet.", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary, modifier = Modifier.padding(top = 6.dp))
                    } else {
                        StatLabel("Notes", Modifier.padding(top = 10.dp, bottom = 2.dp))
                        state.notes.forEach { n ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(n.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
                                IconButton(onClick = { actions.deleteNote(n.id) }) { Icon(Icons.Rounded.Close, contentDescription = "Forget: ${n.text}", tint = colors.textTertiary) }
                            }
                        }
                        TextButton(onClick = { confirm = "notes" }) { Text("Forget everything", color = colors.negative) }
                    }
                }
            }

            item(key = "privacy") {
                Section("Privacy") {
                    Text(
                        "When you chat, what you ask and the budget data the assistant reads are sent to Anthropic to answer you, under your own API account's terms. Chats and notes stay encrypted on this phone, and are removed when you sign out.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                    TextButton(onClick = { confirm = "chats" }, contentPadding = PaddingValues(0.dp)) { Text("Delete all chats", color = colors.negative) }
                }
            }
        }
    }
    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (what == "notes") "Forget every note?" else "Delete every chat?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    if (what == "notes") actions.clearNotes() else actions.clearChats()
                    confirm = null
                }) { Text(if (what == "notes") "Forget" else "Delete", color = colors.negative) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun KeySection(state: AssistantSettingsUiState, actions: AssistantSettingsActions) {
    val colors = CentsibleTheme.colors
    var key by remember { mutableStateOf("") }
    Section("Your Anthropic API key") {
        if (state.hasKey) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = colors.positive, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("A key is saved on this phone.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = actions.removeKey) { Text("Remove") }
            }
        } else {
            Text(
                "The assistant uses Claude with your own key, so you pay Anthropic directly for what it uses. Create one at console.anthropic.com → API keys. It's stored encrypted on this phone and only ever sent to Anthropic.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
        Spacer(Modifier.size(8.dp))
        OutlinedTextField(
            key,
            { key = it },
            label = { Text(if (state.hasKey) "Replace with a new key" else "API key") },
            placeholder = { Text("sk-ant-…") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            state.keyMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (state.keyOk) colors.positive else colors.negative, modifier = Modifier.weight(1f)) }
                ?: Spacer(Modifier.weight(1f))
            Button(onClick = { actions.saveKey(key); key = "" }, enabled = key.isNotBlank() && !state.checking) { Text(if (state.checking) "Checking…" else "Check and save") }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        StatLabel(title, Modifier.padding(start = 4.dp, bottom = 6.dp))
        CentsibleCard { content() }
    }
}

@Composable
private fun Toggle(title: String, description: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val colors = CentsibleTheme.colors
    Row(Modifier.fillMaxWidth().toggleRow(checked, enabled) { onChange(it) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) colors.textPrimary else colors.textTertiary)
            Text(description, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
