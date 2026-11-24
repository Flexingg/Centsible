package app.centsible.core.assistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** One thing in the chat, worked out from the stored API messages. */
sealed interface ChatItem {
    val key: String

    data class User(override val key: String, val text: String) : ChatItem
    data class Reply(override val key: String, val markdown: String) : ChatItem
    /** Claude's summarized reasoning (collapsed by default). */
    data class Thinking(override val key: String, val summary: String) : ChatItem
    /** A tool it read with: [failed] when the tool said something was wrong. */
    data class Lookup(override val key: String, val tool: String, val label: String, val failed: Boolean, val detail: String?) : ChatItem
    /** Code it ran or a web search, on Anthropic's side. */
    data class ServerWork(override val key: String, val label: String, val detail: String?) : ChatItem
    /** A change it proposed, as a card. */
    data class Change(override val key: String, val change: ProposedChange) : ChatItem
}

object Transcript {
    private val json = Json { ignoreUnknownKeys = true }

    /** The tool calls in one assistant message, in order. */
    fun toolUseIds(messageJson: String): List<String> = blocks(messageJson)
        .filter { it.str("type") == "tool_use" }
        .mapNotNull { it.str("id") }

    private fun blocks(messageJson: String): List<JsonObject> {
        val o = runCatching { json.parseToJsonElement(messageJson) as JsonObject }.getOrNull() ?: return emptyList()
        return when (val c = o["content"]) {
            is JsonArray -> c.mapNotNull { it as? JsonObject }
            is JsonPrimitive -> listOf(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to c)))
            else -> emptyList()
        }
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    fun items(conversation: Conversation): List<ChatItem> {
        // Tool results, by call, to mark lookups that failed.
        val results = conversation.messages.filter { it.role == StoredMessage.Role.User }.flatMap { blocks(it.json) }
            .filter { it.str("type") == "tool_result" }
            .associate { it.str("tool_use_id").orEmpty() to (it["is_error"]?.jsonPrimitive?.booleanOrNull == true) }
        val out = mutableListOf<ChatItem>()
        conversation.messages.forEachIndexed { mi, m ->
            if (m.context || m.role == StoredMessage.Role.System) return@forEachIndexed
            blocks(m.json).forEachIndexed { bi, b ->
                val key = "$mi.$bi"
                when (b.str("type")) {
                    "text" -> {
                        val text = b.str("text").orEmpty()
                        if (m.role == StoredMessage.Role.User) {
                            // Notes the app folded in ("[From the app: ...]") aren't the person's words.
                            val own = text.lines().dropWhile { it.startsWith("[From the app:") || it.isBlank() }.joinToString("\n").trim()
                            if (own.isNotEmpty()) out += ChatItem.User(key, own)
                        } else if (text.isNotBlank()) {
                            out += ChatItem.Reply(key, text.trim())
                        }
                    }
                    "thinking" -> b.str("thinking")?.takeIf { it.isNotBlank() }?.let { out += ChatItem.Thinking(key, it.trim()) }
                    "tool_use" -> {
                        val id = b.str("id").orEmpty()
                        val name = b.str("name").orEmpty()
                        val change = conversation.changes[id]
                        out += if (change != null) ChatItem.Change(key, change) else ChatItem.Lookup(key, name, Activity.of(name).removeSuffix("…"), results[id] == true, (b["input"] as? JsonObject)?.takeIf { it.isNotEmpty() }?.toString())
                    }
                    "server_tool_use" -> {
                        val name = b.str("name").orEmpty()
                        val input = b["input"] as? JsonObject
                        out += ChatItem.ServerWork(
                            key,
                            if (name.contains("search")) "Searched the web" else "Ran a calculation",
                            input?.let { i -> i.str("query") ?: i.str("command") ?: i.str("code") },
                        )
                    }
                }
            }
        }
        return out
    }
}
