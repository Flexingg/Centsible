package app.centsible.core.assistant

import app.centsible.core.domain.Session
import app.centsible.core.model.DeviceId
import app.centsible.core.model.Member
import app.centsible.core.model.MemberId
import app.centsible.core.model.Role
import app.centsible.core.testing.FakeAccountServices
import app.centsible.core.testing.FakeBudgetEngine
import app.centsible.core.testing.FakePlanningGateway
import app.centsible.core.testing.FakeReports
import app.centsible.core.testing.InMemorySessionStore
import app.centsible.core.testing.SampleHousehold
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** An interface whose every call fails: for gateways a test doesn't reach. */
inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, _ ->
    if (m.name == "toString") "unused ${T::class.simpleName}" else throw UnsupportedOperationException("${T::class.simpleName}.${m.name} isn't faked")
} as T

class MemorySettings(initial: AssistantSettings = AssistantSettings()) : AssistantSettingsStore {
    val state = MutableStateFlow(initial)
    override val settings: Flow<AssistantSettings> = state
    override suspend fun update(change: (AssistantSettings) -> AssistantSettings) { state.value = change(state.value) }
}

class MemoryKeys(var key: String? = "sk-ant-test") : ApiKeyStore {
    override val hasKey = MutableStateFlow(key != null)
    override suspend fun get() = key
    override suspend fun set(key: String) { this.key = key }
    override suspend fun clear() { key = null }
}

class MemoryNotes : MemoryStore {
    val state = MutableStateFlow<List<MemoryNote>>(emptyList())
    override val notes: Flow<List<MemoryNote>> = state
    override suspend fun all() = state.value
    override suspend fun put(note: MemoryNote) { state.value = state.value.filter { it.id != note.id } + note }
    override suspend fun delete(id: String) { state.value = state.value.filter { it.id != id } }
    override suspend fun clear() { state.value = emptyList() }
}

class MemoryConversations : ConversationStore {
    val state = MutableStateFlow<Map<String, Conversation>>(emptyMap())
    override val summaries = state.map { m -> m.values.map { ConversationSummary(it.id, it.title, it.updatedAt, it.awaitingApproval) } }
    override suspend fun get(id: String) = state.value[id]
    override suspend fun save(conversation: Conversation) { state.value = state.value + (conversation.id to conversation) }
    override suspend fun delete(id: String) { state.value = state.value - id }
    override suspend fun clear() { state.value = emptyMap() }
}

fun session(role: Role = Role.Owner) = Session(
    bridgeUrl = "https://bridge.test",
    accessToken = "a",
    refreshToken = "r",
    cfAccessClientId = null,
    cfAccessClientSecret = null,
    member = Member(MemberId("m1"), "Jo", role, false, listOf(SampleHousehold.budget.id)),
    deviceId = DeviceId("d1"),
    selectedBudget = SampleHousehold.budget.id,
)

/** The app's gateways over the sample household: the faked ones real, the rest failing if touched. */
fun gateways(engine: FakeBudgetEngine = FakeBudgetEngine()) = Gateways(
    engine = engine,
    planning = FakePlanningGateway(),
    reports = FakeReports(),
    insights = unused(),
    planAhead = unused(),
    automations = unused(),
    rules = unused(),
    transactions = unused(),
    household = unused(),
    server = unused(),
    accounts = FakeAccountServices(),
)

// ── The Messages API's streaming format, for a stand-in server ──

sealed interface Block {
    data class Thinking(val text: String, val signature: String = "sig-" + text.hashCode()) : Block
    data class Text(val text: String) : Block
    data class ToolUse(val id: String, val name: String, val input: JsonObject) : Block
}

/** One streamed reply, as server-sent events. */
fun sse(vararg blocks: Block, stop: String = if (blocks.any { it is Block.ToolUse }) "tool_use" else "end_turn", model: String = "claude-opus-5-5"): String {
    val out = StringBuilder()
    fun event(type: String, data: JsonObject) = out.append("event: $type\ndata: $data\n\n")
    fun o(vararg p: Pair<String, Any?>) = j(*p)
    event("message_start", o("type" to "message_start", "message" to o("id" to "msg_${blocks.hashCode()}", "type" to "message", "role" to "assistant", "model" to model, "content" to JsonArray(emptyList()), "stop_reason" to ExplicitNull, "stop_sequence" to ExplicitNull, "usage" to o("input_tokens" to 100, "output_tokens" to 1, "cache_read_input_tokens" to 80, "cache_creation_input_tokens" to 0))))
    blocks.forEachIndexed { i, b ->
        when (b) {
            is Block.Thinking -> {
                event("content_block_start", o("type" to "content_block_start", "index" to i, "content_block" to o("type" to "thinking", "thinking" to "", "signature" to "")))
                event("content_block_delta", o("type" to "content_block_delta", "index" to i, "delta" to o("type" to "thinking_delta", "thinking" to b.text)))
                event("content_block_delta", o("type" to "content_block_delta", "index" to i, "delta" to o("type" to "signature_delta", "signature" to b.signature)))
            }
            is Block.Text -> {
                event("content_block_start", o("type" to "content_block_start", "index" to i, "content_block" to o("type" to "text", "text" to "")))
                b.text.chunked(7).forEach { event("content_block_delta", o("type" to "content_block_delta", "index" to i, "delta" to o("type" to "text_delta", "text" to it))) }
            }
            is Block.ToolUse -> {
                event("content_block_start", o("type" to "content_block_start", "index" to i, "content_block" to o("type" to "tool_use", "id" to b.id, "name" to b.name, "input" to JsonObject(emptyMap()))))
                b.input.toString().chunked(9).forEach { event("content_block_delta", o("type" to "content_block_delta", "index" to i, "delta" to o("type" to "input_json_delta", "partial_json" to it))) }
            }
        }
        event("content_block_stop", o("type" to "content_block_stop", "index" to i))
    }
    event("message_delta", o("type" to "message_delta", "delta" to o("stop_reason" to stop, "stop_sequence" to ExplicitNull), "usage" to o("output_tokens" to 42)))
    event("message_stop", o("type" to "message_stop"))
    return out.toString()
}

fun input(vararg p: Pair<String, Any?>) = j(*p)
fun str(v: String) = JsonPrimitive(v)

fun sessionStore(role: Role = Role.Owner) = InMemorySessionStore(session(role))
