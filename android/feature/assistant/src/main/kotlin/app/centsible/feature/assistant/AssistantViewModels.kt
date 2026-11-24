package app.centsible.feature.assistant

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.centsible.core.assistant.ApiKeyStore
import app.centsible.core.assistant.AssistantAgent
import app.centsible.core.assistant.AssistantEffort
import app.centsible.core.assistant.AssistantModel
import app.centsible.core.assistant.AssistantSettings
import app.centsible.core.assistant.AssistantSettingsStore
import app.centsible.core.assistant.ChatItem
import app.centsible.core.assistant.ClaudeClient
import app.centsible.core.assistant.ClaudeException
import app.centsible.core.assistant.ConversationStore
import app.centsible.core.assistant.ConversationSummary
import app.centsible.core.assistant.LiveTurn
import app.centsible.core.assistant.MemoryNote
import app.centsible.core.assistant.MemoryStore
import app.centsible.core.assistant.TokenUsage
import app.centsible.core.assistant.ToolGroup
import app.centsible.core.assistant.Transcript
import app.centsible.core.domain.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val conversationId: String? = null,
    val title: String? = null,
    val items: List<ChatItem> = emptyList(),
    val live: LiveTurn = LiveTurn(),
    /** Null until known. */
    val hasKey: Boolean? = null,
    val settings: AssistantSettings = AssistantSettings(),
    /** The model this chat runs on (a new chat: the default, unless picked here). */
    val model: AssistantModel = settings.model,
    val canWrite: Boolean = true,
    val usage: TokenUsage = TokenUsage(),
    /** The last turn never got its answer (the app closed mid-turn): offer to carry on. */
    val unanswered: Boolean = false,
) {
    val proposed get() = items.filterIsInstance<ChatItem.Change>().count { it.change.status == app.centsible.core.assistant.ChangeStatus.Proposed }
}

/** What the chat screen does (defaults are no-ops, for previews and screenshot tests). */
data class ChatActions(
    val send: (String) -> Unit = {},
    val approve: (String) -> Unit = {},
    val decline: (String) -> Unit = {},
    val approveAll: () -> Unit = {},
    val declineAll: () -> Unit = {},
    val undo: (String) -> Unit = {},
    val stop: () -> Unit = {},
    val retry: () -> Unit = {},
    val dismissError: () -> Unit = {},
    val newChat: () -> Unit = {},
    val openChats: () -> Unit = {},
    val openSettings: () -> Unit = {},
    val back: () -> Unit = {},
    /** This chat's model (the default for new chats is in settings). */
    val model: (AssistantModel) -> Unit = {},
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AssistantChatViewModel @Inject constructor(
    private val agent: AssistantAgent,
    private val store: ConversationStore,
    keys: ApiKeyStore,
    settings: AssistantSettingsStore,
    sessions: SessionStore,
    saved: SavedStateHandle,
) : ViewModel() {
    private val conversationId = MutableStateFlow(saved.get<String>(ARG_CHAT)?.takeIf { it.isNotBlank() })

    private val conversation = conversationId.flatMapLatest { id ->
        // Every save updates the chats list; reload this chat when it does.
        if (id == null) flowOf(null) else store.summaries.map { store.get(id) }
    }
    /** Picked in the header before the first message of a new chat. */
    private val pendingModel = MutableStateFlow<AssistantModel?>(null)

    private val live = conversationId.flatMapLatest { id -> if (id == null) flowOf(LiveTurn()) else agent.live(id) }

    private val base = combine(conversation, live, keys.hasKey, settings.settings, sessions.session) { conv, live, hasKey, s, session ->
        ChatUiState(
            conversationId = conv?.id,
            title = conv?.title,
            items = conv?.let(Transcript::items).orEmpty(),
            live = live,
            hasKey = hasKey,
            settings = s,
            canWrite = session?.member?.role?.canWrite != false,
            usage = conv?.usage ?: TokenUsage(),
            unanswered = conv != null && !live.running && live.error == null && !conv.awaitingApproval &&
                conv.messages.lastOrNull { !it.context }?.role == app.centsible.core.assistant.StoredMessage.Role.User,
            model = conv?.model?.let(AssistantModel::of) ?: s.model,
        )
    }

    val uiState: StateFlow<ChatUiState> = combine(base, pendingModel) { st, pending ->
        if (st.conversationId == null && pending != null) st.copy(model = pending) else st
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    fun send(text: String) {
        if (text.isBlank()) return
        conversationId.value = agent.send(conversationId.value, text, pendingModel.value)
        pendingModel.value = null
    }

    /** Switches this chat's model; before the first message, picks it for the new chat. */
    fun model(m: AssistantModel) {
        val id = conversationId.value
        if (id == null) pendingModel.value = m else agent.setModel(id, m)
    }

    private fun withId(block: (String) -> Unit) = conversationId.value?.let(block)

    fun approve(toolUseId: String) = withId { agent.approve(it, toolUseId) }
    fun decline(toolUseId: String) = withId { agent.decline(it, toolUseId) }
    fun approveAll() = withId { agent.approveAll(it) }
    fun declineAll() = withId { agent.declineAll(it) }
    fun undo(toolUseId: String) = withId { agent.undo(it, toolUseId) }
    fun stop() = withId { agent.stop(it) }
    fun retry() = withId { agent.retry(it) }
    fun dismissError() = withId { agent.dismissError(it) }
    fun newChat() { conversationId.value = null; pendingModel.value = null }
    fun open(id: String) { conversationId.value = id }

    companion object {
        const val ARG_CHAT = "chat"
    }
}

@HiltViewModel
class ChatsViewModel @Inject constructor(private val store: ConversationStore) : ViewModel() {
    val chats: StateFlow<List<ConversationSummary>?> = store.summaries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun delete(id: String) = viewModelScope.launch { store.delete(id) }
    fun deleteAll() = viewModelScope.launch { store.clear() }
}

data class AssistantSettingsUiState(
    val settings: AssistantSettings = AssistantSettings(),
    val hasKey: Boolean = false,
    val checking: Boolean = false,
    val keyMessage: String? = null,
    val keyOk: Boolean = false,
    val notes: List<MemoryNote> = emptyList(),
    val canWrite: Boolean = true,
)

data class AssistantSettingsActions(
    val back: () -> Unit = {},
    val saveKey: (String) -> Unit = {},
    val removeKey: () -> Unit = {},
    val model: (AssistantModel) -> Unit = {},
    val effort: (AssistantEffort) -> Unit = {},
    val permission: (ToolGroup, Boolean) -> Unit = { _, _ -> },
    val codeExecution: (Boolean) -> Unit = {},
    val webSearch: (Boolean) -> Unit = {},
    val memory: (Boolean) -> Unit = {},
    val deleteNote: (String) -> Unit = {},
    val clearNotes: () -> Unit = {},
    val clearChats: () -> Unit = {},
)

@HiltViewModel
class AssistantSettingsViewModel @Inject constructor(
    private val settings: AssistantSettingsStore,
    private val keys: ApiKeyStore,
    private val memory: MemoryStore,
    private val chats: ConversationStore,
    private val claude: ClaudeClient,
    sessions: SessionStore,
) : ViewModel() {
    private val keyState = MutableStateFlow(Triple(false, null as String?, false))

    val uiState: StateFlow<AssistantSettingsUiState> = combine(settings.settings, keys.hasKey, memory.notes, keyState, sessions.session) { s, has, notes, k, session ->
        AssistantSettingsUiState(s, has, k.first, k.second, k.third, notes.sortedBy { it.createdAt }, session?.member?.role?.canWrite != false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AssistantSettingsUiState())

    /** Checks the key with Anthropic before keeping it. */
    fun saveKey(raw: String) {
        val key = raw.trim()
        if (!key.startsWith("sk-ant-")) {
            keyState.value = Triple(false, "That doesn't look like an Anthropic API key (they start with sk-ant-).", false)
            return
        }
        keyState.value = Triple(true, null, false)
        viewModelScope.launch {
            val model = uiState.value.settings.model
            try {
                claude.verify(key, model)
                keys.set(key)
                keyState.value = Triple(false, "Key saved. It works with ${model.label}.", true)
            } catch (e: ClaudeException) {
                if (e.badKey) {
                    keyState.value = Triple(false, e.message, false)
                } else {
                    // Couldn't check (offline, or the model isn't on this key): keep it, and say so.
                    keys.set(key)
                    keyState.value = Triple(false, "Saved, but couldn't confirm it: ${e.message}", true)
                }
            }
        }
    }

    fun removeKey() = viewModelScope.launch {
        keys.clear()
        keyState.value = Triple(false, "Key removed from this phone.", false)
    }

    private fun change(block: (AssistantSettings) -> AssistantSettings) = viewModelScope.launch { settings.update(block) }

    fun model(m: AssistantModel) = change { it.copy(model = m) }
    fun effort(e: AssistantEffort) = change { it.copy(effort = e) }
    fun permission(g: ToolGroup, on: Boolean) = change { it.copy(writable = if (on) it.writable + g else it.writable - g) }
    fun codeExecution(on: Boolean) = change { it.copy(codeExecution = on) }
    fun webSearch(on: Boolean) = change { it.copy(webSearch = on) }
    fun memoryOn(on: Boolean) = change { it.copy(memory = on) }
    fun deleteNote(id: String) = viewModelScope.launch { memory.delete(id) }
    fun clearNotes() = viewModelScope.launch { memory.clear() }
    fun clearChats() = viewModelScope.launch { chats.clear() }
    fun clearMessage() = keyState.update { it.copy(second = null) }
}
