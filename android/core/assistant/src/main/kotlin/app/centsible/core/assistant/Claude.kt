package app.centsible.core.assistant

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.OverloadedException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.CodeExecutionTool20260521
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolUnion
import com.anthropic.models.messages.WebSearchTool20260209
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Streaming text as it arrives, for the chat to show before the turn is done. */
sealed interface StreamUpdate {
    data class BlockStarted(val index: Int, val kind: Kind, val toolName: String? = null) : StreamUpdate
    data class TextDelta(val index: Int, val text: String) : StreamUpdate
    data class ThinkingDelta(val index: Int, val text: String) : StreamUpdate

    enum class Kind { Text, Thinking, ToolUse, ServerTool, Other }
}

/** One request to Claude: the whole conversation so far, and what it may use. */
data class ClaudeRequest(
    val model: AssistantModel,
    val effort: AssistantEffort,
    val system: String,
    val tools: List<AssistantTool>,
    val codeExecution: Boolean,
    val webSearch: Boolean,
    val messages: List<MessageParam>,
    val fallbacks: Boolean,
)

/** What went wrong talking to Claude, worded for the person. */
class ClaudeException(message: String, val retryable: Boolean, val badKey: Boolean = false, cause: Throwable? = null) : Exception(message, cause)

/** Sends a request and streams the reply. An interface so tests can stand in a fake server. */
interface ClaudeClient {
    suspend fun stream(apiKey: String, request: ClaudeRequest, onUpdate: (StreamUpdate) -> Unit): Message

    /** Checks a key can use [model] (looks the model up: costs nothing). Throws [ClaudeException] if not. */
    suspend fun verify(apiKey: String, model: AssistantModel)
}

/**
 * Claude through Anthropic's Java SDK. One client per key (each holds an HTTP pool); the
 * SDK retries rate limits, overloads and dropped connections itself, twice.
 */
@Singleton
class SdkClaudeClient @Inject constructor() : ClaudeClient {
    /** For tests: a stand-in server. */
    var baseUrl: String? = null

    private var client: Pair<String, AnthropicClient>? = null
    /** Set once the API turns down the refusal-fallback option, so it isn't sent again. */
    @Volatile private var fallbacksRejected = false

    @Synchronized
    private fun clientFor(key: String): AnthropicClient {
        client?.takeIf { it.first == key }?.let { return it.second }
        client?.second?.close()
        val c = AnthropicOkHttpClient.builder()
            .apiKey(key)
            // API keys go in X-Api-Key (Authorization: Bearer is for OAuth tokens); with it set, the SDK adds no other auth.
            .putHeader("X-Api-Key", key)
            .apply { baseUrl?.let { baseUrl(it) } }
            .maxRetries(2)
            // Long turns stream for minutes; the stream itself keeps the connection busy.
            .timeout(Duration.ofMinutes(10))
            .build()
        client = key to c
        return c
    }

    override suspend fun stream(apiKey: String, request: ClaudeRequest, onUpdate: (StreamUpdate) -> Unit): Message = withContext(Dispatchers.IO) {
        try {
            run(clientFor(apiKey), request, request.fallbacks && !fallbacksRejected, onUpdate)
        } catch (e: BadRequestException) {
            // An account or platform without server-side fallbacks: try once without them.
            if (request.fallbacks && !fallbacksRejected && e.message.orEmpty().contains("fallback", ignoreCase = true)) {
                fallbacksRejected = true
                try {
                    run(clientFor(apiKey), request, false, onUpdate)
                } catch (e2: Exception) {
                    throw describe(e2)
                }
            } else {
                throw describe(e)
            }
        } catch (e: ClaudeException) {
            throw e
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw describe(e)
        }
    }

    override suspend fun verify(apiKey: String, model: AssistantModel) = withContext(Dispatchers.IO) {
        try {
            clientFor(apiKey).models().retrieve(model.id)
            Unit
        } catch (e: Exception) {
            throw describe(e)
        }
    }

    private suspend fun run(client: AnthropicClient, request: ClaudeRequest, fallbacks: Boolean, onUpdate: (StreamUpdate) -> Unit): Message {
        val params = buildParams(request, fallbacks)
        val accumulator = MessageAccumulator.create()
        val ctx = currentCoroutineContext()
        client.messages().createStreaming(params).use { response ->
            // Cancelling the turn (Stop) closes the stream mid-flight.
            val handle = ctx.job.invokeOnCompletion { runCatching { response.close() } }
            try {
                response.stream().forEach { event ->
                    ctx.ensureActive()
                    accumulator.accumulate(event)
                    event.contentBlockStart().ifPresent { start ->
                        val block = start.contentBlock()
                        val kind = when {
                            block.text().isPresent -> StreamUpdate.Kind.Text
                            block.thinking().isPresent || block.redactedThinking().isPresent -> StreamUpdate.Kind.Thinking
                            block.toolUse().isPresent -> StreamUpdate.Kind.ToolUse
                            block.serverToolUse().isPresent -> StreamUpdate.Kind.ServerTool
                            else -> StreamUpdate.Kind.Other
                        }
                        val name = block.toolUse().map { it.name() }.orElse(null) ?: block.serverToolUse().map { it.name().toString() }.orElse(null)
                        onUpdate(StreamUpdate.BlockStarted(start.index().toInt(), kind, name))
                    }
                    event.contentBlockDelta().ifPresent { d ->
                        d.delta().text().ifPresent { onUpdate(StreamUpdate.TextDelta(d.index().toInt(), it.text())) }
                        d.delta().thinking().ifPresent { onUpdate(StreamUpdate.ThinkingDelta(d.index().toInt(), it.thinking())) }
                    }
                }
            } finally {
                handle.dispose()
            }
        }
        return accumulator.message()
    }

    companion object {
        /** The request as the API takes it: tools and system prompt first and unchanging, so they stay cached. */
        fun buildParams(request: ClaudeRequest, fallbacks: Boolean): MessageCreateParams {
            val b = MessageCreateParams.builder()
                .model(request.model.id)
                .maxTokens(64_000L)
                .systemOfTextBlockParams(
                    listOf(TextBlockParam.builder().text(request.system).cacheControl(CacheControlEphemeral.builder().build()).build()),
                )
                .thinking(ThinkingConfigAdaptive.builder().display(ThinkingConfigAdaptive.Display.SUMMARIZED).build())
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(request.effort.wire)).build())
                // Caches the conversation so far, so each round only pays full price for what's new.
                .cacheControl(CacheControlEphemeral.builder().build())
                .messages(request.messages)
            request.tools.forEach { b.addTool(it.toSdk()) }
            // Web search filters its results with code it runs itself: declaring code execution too would give it two sandboxes.
            if (request.webSearch) b.addTool(WebSearchTool20260209.builder().maxUses(5L).build())
            else if (request.codeExecution) b.addTool(CodeExecutionTool20260521.builder().build())
            if (fallbacks && request.model.fallbacks) {
                b.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            }
            return b.build()
        }

        fun AssistantTool.toSdk(): ToolUnion {
            val props = (schema["properties"] as? JsonObject).orEmpty()
            val required = (schema["required"] as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty()
            val input = Tool.InputSchema.builder()
                .properties(Tool.InputSchema.Properties.builder().apply { props.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v.toPlain())) } }.build())
                .apply { if (required.isNotEmpty()) required(required) }
                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                .build()
            return ToolUnion.ofTool(
                Tool.builder()
                    .name(name)
                    .description(description)
                    .inputSchema(input)
                    // Streamed requests send tool input as it's generated; the agent checks it before running anything.
                    .eagerInputStreaming(true)
                    .build(),
            )
        }

        /** kotlinx JSON → plain Kotlin values the SDK's JsonValue takes. */
        fun JsonElement.toPlain(): Any? = when (this) {
            JsonNull -> null
            is JsonObject -> mapValues { it.value.toPlain() }
            is JsonArray -> map { it.toPlain() }
            is JsonPrimitive -> when {
                isString -> content
                booleanOrNull != null -> booleanOrNull
                longOrNull != null -> longOrNull
                doubleOrNull != null -> doubleOrNull
                else -> content
            }
        }

        /**
         * The SDK's own Jackson mapper (it knows every SDK type's wire shape). It's internal to
         * the SDK's Kotlin, but public on the JVM; reached once by reflection.
         */
        private val mapper: com.fasterxml.jackson.databind.ObjectMapper by lazy {
            Class.forName("com.anthropic.core.ObjectMappers").getMethod("jsonMapper").invoke(null) as com.fasterxml.jackson.databind.ObjectMapper
        }

        fun paramToJson(param: MessageParam): String = mapper.writeValueAsString(param)
        fun paramFromJson(json: String): MessageParam = mapper.readValue(json, MessageParam::class.java)
        fun valueToJson(value: JsonValue): String = mapper.writeValueAsString(value)

        fun describe(e: Throwable): ClaudeException = when (e) {
            is ClaudeException -> e
            is UnauthorizedException -> ClaudeException("Your Anthropic API key was turned down. Check it in Assistant settings.", retryable = false, badKey = true, cause = e)
            is PermissionDeniedException -> ClaudeException("Your API key can't use this model. Try another in Assistant settings. (${e.apiMessage().orElse("")})", retryable = false, cause = e)
            is NotFoundException -> ClaudeException("That model isn't available to your API key. Pick another in Assistant settings.", retryable = false, cause = e)
            is RateLimitException -> ClaudeException("Your API key hit its rate limit. Wait a minute and try again.", retryable = true, cause = e)
            is OverloadedException -> ClaudeException("Claude is overloaded right now. Try again in a moment.", retryable = true, cause = e)
            is InternalServerException -> ClaudeException("Anthropic's servers had a problem. Try again in a moment.", retryable = true, cause = e)
            is BadRequestException -> ClaudeException(
                e.apiMessage().orElse(null)?.let { m ->
                    when {
                        m.contains("credit balance", ignoreCase = true) -> "Your Anthropic account is out of credit. Add some at console.anthropic.com."
                        m.contains("too long", ignoreCase = true) || m.contains("context", ignoreCase = true) -> "This chat has grown too long. Start a new one."
                        else -> "Claude couldn't take that request: $m"
                    }
                } ?: "Claude couldn't take that request.",
                retryable = false,
                cause = e,
            )
            is AnthropicServiceException -> ClaudeException("Claude answered with an error (${e.statusCode()}). Try again.", retryable = true, cause = e)
            is AnthropicIoException -> ClaudeException("Couldn't reach Anthropic. Check your connection and try again.", retryable = true, cause = e)
            else -> ClaudeException("Something went wrong talking to Claude: ${e.message ?: e::class.simpleName}", retryable = true, cause = e)
        }
    }
}
