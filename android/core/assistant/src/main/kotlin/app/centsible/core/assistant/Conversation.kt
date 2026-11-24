package app.centsible.core.assistant

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * A chat with the assistant, kept on the phone. [messages] are the API's own messages, each
 * stored as the exact JSON sent and received, so thinking blocks and tool calls go back to
 * Claude unchanged (they must, turn after turn). [changes] are the changes the assistant
 * proposed, by tool call, with what became of each.
 */
@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val budgetId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<StoredMessage> = emptyList(),
    val changes: Map<String, ProposedChange> = emptyMap(),
    /** Results already worked out for this turn's tool calls while changes wait for approval. */
    val waiting: List<StoredToolResult> = emptyList(),
    val usage: TokenUsage = TokenUsage(),
    /** The model the conversation runs on (thinking blocks belong to it). */
    val model: String? = null,
    /** The last date/permissions/memory note sent, to send a new one only when it changes. */
    val contextState: String? = null,
    /** Things that happened outside the chat (an undo), told to Claude with the next message. */
    val notes: List<String> = emptyList(),
) {
    val awaitingApproval get() = changes.values.any { it.status == ChangeStatus.Proposed }
}

@Serializable
data class StoredMessage(
    val role: Role,
    /** The message as the Messages API takes it (a MessageParam), as JSON. */
    val json: String,
    val at: Long,
    /** A context note the app added (date, permissions, memory): not shown in the chat. */
    val context: Boolean = false,
) {
    @Serializable
    enum class Role { User, Assistant, System }
}

@Serializable
data class StoredToolResult(val toolUseId: String, val content: String, val isError: Boolean)

@Serializable
enum class ChangeStatus { Proposed, Approved, Declined, Done, Failed, Undone }

/** A change the assistant asked to make, shown as a card until it's approved or declined. */
@Serializable
data class ProposedChange(
    val toolUseId: String,
    val tool: String,
    val group: String,
    val title: String,
    /** What will change, line by line ("Groceries · November: $500 → $600"). */
    val details: List<String>,
    val input: String,
    val status: ChangeStatus = ChangeStatus.Proposed,
    /** What happened, once it ran (or why it failed). */
    val outcome: String? = null,
    /** How to take it back: another tool call, run directly when the person taps Undo. */
    val undo: ToolCall? = null,
)

@Serializable
data class ToolCall(val name: String, val input: String)

@Serializable
data class TokenUsage(
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    /** Estimated dollars, priced per request at the model that answered it. */
    val cost: Double = 0.0,
    val webSearches: Long = 0,
) {
    operator fun plus(o: TokenUsage) =
        TokenUsage(input + o.input, output + o.output, cacheRead + o.cacheRead, cacheWrite + o.cacheWrite, cost + o.cost, webSearches + o.webSearches)

    val tokens get() = input + output + cacheRead + cacheWrite

    /** The estimate; a chat saved before costs were kept is priced at [model] from its token counts. */
    fun estimate(model: AssistantModel): Double =
        if (cost > 0.0 || tokens == 0L) cost else model.cost(input, output, cacheRead, cacheWrite, webSearches)
}

/** One line in the chats list. */
data class ConversationSummary(val id: String, val title: String, val updatedAt: Long, val awaitingApproval: Boolean)

/** Lets the rest of the app know the assistant changed something, so open screens reload. */
fun interface ChangeNotifier {
    fun changed()
}

interface ConversationStore {
    val summaries: Flow<List<ConversationSummary>>
    suspend fun get(id: String): Conversation?
    suspend fun save(conversation: Conversation)
    suspend fun delete(id: String)
    suspend fun clear()
}
