package app.centsible.core.assistant

import app.centsible.core.domain.BudgetEngine
import app.centsible.core.domain.HouseholdGateway
import app.centsible.core.domain.SessionStore
import app.centsible.core.domain.userMessage
import app.centsible.core.model.BudgetId
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ToolResultBlockParam
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** A turn in progress, for the chat to show while it streams. */
data class LiveTurn(
    val running: Boolean = false,
    /** "Reading your transactions…" */
    val activity: String? = null,
    /** What's streamed so far of the reply being written. */
    val blocks: List<LiveBlock> = emptyList(),
    val error: String? = null,
    val retryable: Boolean = false,
    val badKey: Boolean = false,
)

/** How a turn ended, for a notification when the app is in the background. */
sealed interface TurnEnded {
    val conversationId: String
    val title: String

    data class Replied(override val conversationId: String, override val title: String, val text: String) : TurnEnded
    data class NeedsApproval(override val conversationId: String, override val title: String, val changes: Int) : TurnEnded
    data class Failed(override val conversationId: String, override val title: String, val message: String) : TurnEnded
}

data class LiveBlock(val index: Int, val kind: StreamUpdate.Kind, val text: String, val toolName: String? = null)

/**
 * The assistant: runs a conversation with Claude, its tools, and the approvals in between.
 *
 * The loop is the Messages API's own: send the conversation, stream the reply, run the tools
 * it asked for, send their results, until Claude is done. Reading runs straight away (in
 * parallel). A change becomes a card; the turn waits until every card is approved or
 * declined (approved ones run as they're tapped), then carries on with their results.
 * Everything is saved after each step, so a chat survives the app being closed mid-turn.
 * Turns run in the app's scope, not a screen's, so leaving the chat doesn't stop them.
 */
@Singleton
class AssistantAgent @Inject constructor(
    private val catalog: ToolCatalog,
    private val claude: ClaudeClient,
    private val settings: AssistantSettingsStore,
    private val keys: ApiKeyStore,
    private val memory: MemoryStore,
    private val store: ConversationStore,
    private val sessions: SessionStore,
    private val engine: BudgetEngine,
    private val household: HouseholdGateway,
    private val notifier: ChangeNotifier,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val live = MutableStateFlow<Map<String, LiveTurn>>(emptyMap())
    private val jobs = ConcurrentHashMap<String, Job>()
    private val busyIds = MutableStateFlow<Set<String>>(emptySet())
    private val ended = kotlinx.coroutines.flow.MutableSharedFlow<TurnEnded>(extraBufferCapacity = 8)

    /** Conversations with a turn under way (the app keeps itself running while this isn't empty). */
    val busy: kotlinx.coroutines.flow.StateFlow<Set<String>> = busyIds

    /** Each turn as it ends (not when it's stopped). */
    val turns: kotlinx.coroutines.flow.SharedFlow<TurnEnded> = ended

    /** The chat's title, for the "working on…" notification. */
    suspend fun title(conversationId: String): String? = store.get(conversationId)?.title
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val json = Json { ignoreUnknownKeys = true }

    /** Clock and ids, swappable in tests. */
    internal var now: () -> Long = { System.currentTimeMillis() }
    internal var today: () -> java.time.LocalDate = { java.time.LocalDate.now() }

    fun live(conversationId: String): Flow<LiveTurn> = live.map { it[conversationId] ?: LiveTurn() }.distinctUntilChanged()

    fun isRunning(conversationId: String) = jobs[conversationId]?.isActive == true

    /** Waits for the conversation's turn to finish (tests). */
    internal suspend fun awaitIdle(conversationId: String) {
        jobs[conversationId]?.join()
    }

    private fun setLive(id: String, change: (LiveTurn) -> LiveTurn) = live.update { it + (id to change(it[id] ?: LiveTurn())) }

    private fun lock(id: String) = locks.getOrPut(id) { Mutex() }

    /** Runs [block] for a conversation, one at a time, keeping it on screen as running. */
    private fun launch(id: String, block: suspend () -> Unit): Job {
        busyIds.update { it + id }
        val job = scope.launch {
            lock(id).withLock {
                setLive(id) { LiveTurn(running = true, activity = "Thinking…") }
                try {
                    block()
                    setLive(id) { LiveTurn() }
                    report(id, null)
                } catch (e: CancellationException) {
                    setLive(id) { LiveTurn() }
                    throw e
                } catch (e: ClaudeException) {
                    setLive(id) { LiveTurn(error = e.message, retryable = e.retryable, badKey = e.badKey) }
                    report(id, e.message)
                } catch (e: Exception) {
                    setLive(id) { LiveTurn(error = e.userMessage(), retryable = true) }
                    report(id, e.userMessage())
                }
            }
        }
        jobs[id] = job
        job.invokeOnCompletion {
            jobs.remove(id, job)
            if (jobs[id] == null) busyIds.update { it - id }
        }
        return job
    }

    /** Says how the turn ended: changes to approve, an error, or the reply's first words. */
    private suspend fun report(id: String, error: String?) {
        val conv = runCatching { store.get(id) }.getOrNull() ?: return
        val event = when {
            error != null -> TurnEnded.Failed(id, conv.title, error)
            conv.awaitingApproval -> TurnEnded.NeedsApproval(id, conv.title, conv.changes.values.count { it.status == ChangeStatus.Proposed })
            else -> {
                val reply = Transcript.items(conv).lastOrNull() as? ChatItem.Reply ?: return
                val labels = labels(conv.budgetId)
                TurnEnded.Replied(id, conv.title, Readable.humanize(reply.markdown, labels))
            }
        }
        ended.tryEmit(event)
    }

    // ── What the chat calls ──

    /**
     * Sends a message (starting a new conversation when [conversationId] is null, on [model]
     * or else the default model) and returns the conversation's id.
     */
    fun send(conversationId: String?, text: String, model: AssistantModel? = null): String {
        val id = conversationId ?: UUID.randomUUID().toString()
        if (isRunning(id)) return id
        launch(id) {
            var conv = store.get(id) ?: newConversation(id, text, model)
            conv = addUserMessage(conv, text.trim())
            store.save(conv)
            loop(conv)
        }
        return id
    }

    /** Approves one proposed change: it runs now. The turn carries on once none are left. */
    fun approve(conversationId: String, toolUseId: String) = decide(conversationId, listOf(toolUseId), approve = true)

    fun decline(conversationId: String, toolUseId: String) = decide(conversationId, listOf(toolUseId), approve = false)

    fun approveAll(conversationId: String) = scope.launch {
        val conv = store.get(conversationId) ?: return@launch
        decide(conversationId, conv.changes.values.filter { it.status == ChangeStatus.Proposed }.map { it.toolUseId }, approve = true)
    }

    fun declineAll(conversationId: String) = scope.launch {
        val conv = store.get(conversationId) ?: return@launch
        decide(conversationId, conv.changes.values.filter { it.status == ChangeStatus.Proposed }.map { it.toolUseId }, approve = false)
    }

    /** Takes back a change that ran, with the call it left for that. Claude hears about it with the next message. */
    fun undo(conversationId: String, toolUseId: String) {
        if (isRunning(conversationId)) return
        launch(conversationId) {
            var conv = store.get(conversationId) ?: return@launch
            val change = conv.changes[toolUseId]?.takeIf { it.status == ChangeStatus.Done } ?: return@launch
            val undo = change.undo ?: return@launch
            setLive(conversationId) { it.copy(activity = "Undoing…") }
            val tool = catalog.find(undo.name) ?: throw IllegalStateException("Can't undo ${undo.name}")
            val budget = conv.budgetId?.let(::BudgetId) ?: throw IllegalStateException("No budget")
            tool.run(Args(json.parseToJsonElement(undo.input) as JsonObject), ToolContext(budget, catalog.names(budget), today()))
            notifier.changed()
            conv = conv.copy(
                changes = conv.changes + (toolUseId to change.copy(status = ChangeStatus.Undone, outcome = "Undone")),
                notes = conv.notes + "The person undid a change you made earlier (\"${change.title}\": ${change.details.joinToString("; ")}).",
                updatedAt = now(),
            )
            store.save(conv)
        }
    }

    /** Stops the turn in progress. What's been saved so far stays. */
    fun stop(conversationId: String) {
        jobs[conversationId]?.cancel()
    }

    /** After an error: try the last step again. */
    fun retry(conversationId: String) {
        if (isRunning(conversationId)) return
        launch(conversationId) {
            val conv = store.get(conversationId) ?: return@launch
            if (conv.awaitingApproval) return@launch
            val last = conv.messages.lastOrNull() ?: return@launch
            if (last.role == StoredMessage.Role.Assistant) return@launch // nothing to answer
            loop(conv)
        }
    }

    fun dismissError(conversationId: String) = setLive(conversationId) { LiveTurn() }

    /** Names for the ids of [budgetId]'s accounts, categories and merchants, to show in place of any id in a reply. */
    suspend fun labels(budgetId: String?): Map<String, String> =
        budgetId?.let { b -> runCatching { catalog.names(BudgetId(b)).labels() }.getOrNull() }.orEmpty()

    /**
     * Moves this chat to another model from its next turn. Earlier thinking stays in the
     * history as it is: the API skips, unbilled, any thinking the new model can't read.
     */
    fun setModel(conversationId: String, model: AssistantModel) = scope.launch {
        lock(conversationId).withLock {
            val conv = store.get(conversationId) ?: return@withLock
            if (conv.model == model.id) return@withLock
            store.save(conv.copy(model = model.id, updatedAt = now()))
        }
    }

    // ── The loop ──

    private suspend fun newConversation(id: String, firstText: String, model: AssistantModel?): Conversation {
        val budget = sessions.current()?.selectedBudget?.raw
        val title = firstText.trim().lineSequence().first().let { if (it.length > 60) it.take(57).trimEnd() + "…" else it }
        return Conversation(id = id, title = title, budgetId = budget, createdAt = now(), updatedAt = now(), model = (model ?: settings.settings.first().model).id)
    }

    /**
     * The person's message, with anything waiting folded in: changes still awaiting approval
     * count as declined (they wrote instead), and a fresh context note follows when the date,
     * permissions or memory changed (a system message, so the cached conversation isn't touched).
     */
    private suspend fun addUserMessage(conversation: Conversation, text: String): Conversation {
        var conv = conversation
        val blocks = mutableListOf<ContentBlockParam>()
        if (conv.awaitingApproval || conv.waiting.isNotEmpty()) {
            val declined = conv.changes.values.filter { it.status == ChangeStatus.Proposed }
            conv = conv.copy(
                changes = conv.changes + declined.associate { it.toolUseId to it.copy(status = ChangeStatus.Declined, outcome = "Not approved") },
                waiting = conv.waiting + declined.map { StoredToolResult(it.toolUseId, "The person didn't approve this change; they wrote a new message instead (below).", isError = false) },
            )
            blocks += resultBlocks(conv)
            conv = conv.copy(waiting = emptyList())
        }
        val notes = conv.notes.joinToString("\n") { "[From the app: $it]" }
        blocks += ContentBlockParam.ofText(TextBlockParam.builder().text(if (notes.isEmpty()) text else "$notes\n\n$text").build())
        conv = conv.append(StoredMessage.Role.User, MessageParam.ofUserOfContentBlockParams(blocks)).copy(notes = emptyList())

        val s = settings.settings.first()
        val budget = conv.budgetId?.let(::BudgetId)
        val state = contextState(budget, s)
        if (state != conv.contextState) {
            val first = conv.contextState == null
            val skeleton = if (first && budget != null) runCatching {
                val snap = catalog.names(budget).get()
                "\n\n" + Prompt.skeleton(snap.accounts, snap.groups, snap.offBooks)
            }.getOrDefault("") else ""
            val note = (if (first) "Context for this chat:\n" else "Context update:\n") + state + skeleton
            conv = conv.append(StoredMessage.Role.System, MessageParam.ofSystem(note), context = true).copy(contextState = state)
        }
        return conv
    }

    private suspend fun contextState(budget: BudgetId?, s: AssistantSettings): String {
        val session = sessions.current()
        val budgetName = budget?.let { b -> runCatching { engine.budgets().firstOrNull { it.id == b }?.name }.getOrNull() }
        val currency = budget?.let { b -> runCatching { engine.preferences(b).currencyCode }.getOrNull() }
        return Prompt.state(today(), budgetName, currency, session?.member?.displayName, session?.member?.role?.canWrite != false, s, if (s.memory) memory.all() else emptyList())
    }

    /** Tokens and the estimated cost of one reply, at the model that wrote it. */
    private fun usageOf(message: Message, asked: AssistantModel): TokenUsage {
        val u = message.usage()
        val input = u.inputTokens()
        val output = u.outputTokens()
        val read = u.cacheReadInputTokens().orElse(0L)
        val write = u.cacheCreationInputTokens().orElse(0L)
        val searches = u.serverToolUse().map { it.webSearchRequests() }.orElse(0L)
        val served = AssistantModel.served(runCatching { message.model().asString() }.getOrNull()) ?: asked
        return TokenUsage(input, output, read, write, served.cost(input, output, read, write, searches), searches)
    }

    private fun Conversation.append(role: StoredMessage.Role, param: MessageParam, context: Boolean = false) =
        copy(messages = messages + StoredMessage(role, SdkClaudeClient.paramToJson(param), now(), context), updatedAt = now())

    private suspend fun loop(start: Conversation) {
        var conv = start
        var rounds = 0
        var pauses = 0
        while (true) {
            if (++rounds > MAX_ROUNDS) {
                conv = conv.copy(notes = conv.notes + "Your last turn ran out of steps before finishing; summarize what you found so far.")
                store.save(conv)
                throw ClaudeException("The assistant took too many steps on this one. Ask it to carry on, or narrow the question.", retryable = false)
            }
            val s = settings.settings.first()
            val key = keys.get() ?: throw ClaudeException("Add your Anthropic API key in Assistant settings first.", retryable = false, badKey = true)
            val model = AssistantModel.of(conv.model ?: s.model.id)
            setLive(conv.id) { it.copy(running = true, activity = "Thinking…", blocks = emptyList()) }
            val request = ClaudeRequest(
                model = model,
                effort = s.effort,
                system = Prompt.SYSTEM,
                tools = catalog.tools + memoryTools,
                codeExecution = s.codeExecution,
                webSearch = s.webSearch,
                messages = conv.messages.map { SdkClaudeClient.paramFromJson(it.json) },
                fallbacks = true,
            )
            val message = claude.stream(key, request) { update -> onStream(conv.id, update) }
            conv = conv.append(StoredMessage.Role.Assistant, message.toParam()).copy(usage = conv.usage + usageOf(message, model))
            store.save(conv)
            setLive(conv.id) { it.copy(blocks = emptyList()) }

            when (message.stopReason().orElse(StopReason.END_TURN)) {
                StopReason.TOOL_USE -> {
                    conv = handleTools(conv, message)
                    store.save(conv)
                    if (conv.awaitingApproval) return // the cards wait; approving carries on
                    conv = conv.append(StoredMessage.Role.User, MessageParam.ofUserOfContentBlockParams(resultBlocks(conv))).copy(waiting = emptyList())
                    store.save(conv)
                }
                StopReason.PAUSE_TURN -> {
                    // A server tool (code, web) paused a long turn: sending it back resumes it.
                    if (++pauses > 5) return
                }
                StopReason.MAX_TOKENS -> throw ClaudeException("The reply was cut off for length. Ask for a shorter answer, or for one part at a time.", retryable = false)
                StopReason.REFUSAL -> throw ClaudeException("Claude declined to answer that one.${message.stopDetails().map { d -> d.explanation().map { " $it" }.orElse("") }.orElse("")}", retryable = false)
                StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED -> throw ClaudeException("This chat has grown too long. Start a new one (notes you saved carry over).", retryable = false)
                else -> return
            }
        }
    }

    private fun onStream(id: String, u: StreamUpdate) = setLive(id) { t ->
        when (u) {
            is StreamUpdate.BlockStarted -> t.copy(
                blocks = t.blocks + LiveBlock(u.index, u.kind, "", u.toolName),
                activity = when (u.kind) {
                    StreamUpdate.Kind.ToolUse, StreamUpdate.Kind.ServerTool -> u.toolName?.let(Activity::of) ?: t.activity
                    StreamUpdate.Kind.Thinking -> "Thinking…"
                    else -> null
                },
            )
            is StreamUpdate.TextDelta -> t.copy(blocks = t.blocks.map { if (it.index == u.index) it.copy(text = it.text + u.text) else it }, activity = null)
            is StreamUpdate.ThinkingDelta -> t.copy(blocks = t.blocks.map { if (it.index == u.index) it.copy(text = it.text + u.text) else it })
        }
    }

    /** Tool results waiting for this turn, in the order Claude called them, as one user message's blocks. */
    private fun resultBlocks(conv: Conversation): List<ContentBlockParam> {
        val lastAssistant = conv.messages.lastOrNull { it.role == StoredMessage.Role.Assistant }?.let { Transcript.toolUseIds(it.json) }.orEmpty()
        val byId = conv.waiting.associateBy { it.toolUseId }
        val ordered = lastAssistant.mapNotNull { byId[it] } + conv.waiting.filter { it.toolUseId !in lastAssistant }
        return ordered.map { r -> ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(r.toolUseId).content(r.content).isError(r.isError).build()) }
    }

    /** Runs the reads, turns the writes into cards, and returns the conversation with both recorded. */
    private suspend fun handleTools(conv: Conversation, message: Message): Conversation {
        val uses = message.content().mapNotNull { it.toolUse().orElse(null) }
        val budget = conv.budgetId?.let(::BudgetId)
        val s = settings.settings.first()
        val canWrite = sessions.current()?.member?.role?.canWrite != false
        val names = budget?.let { catalog.names(it) }
        val ctx = budget?.let { ToolContext(it, names!!, today()) }
        setLive(conv.id) { it.copy(activity = uses.firstOrNull()?.name()?.let(Activity::of) ?: "Working…") }

        val outcomes = coroutineScope {
            uses.map { use ->
                async {
                    val id = use.id()
                    val name = use.name()
                    val input = runCatching { json.parseToJsonElement(SdkClaudeClient.valueToJson(use._input())) as JsonObject }.getOrNull()
                        ?: return@async Outcome.Result(StoredToolResult(id, j("INVALID_JSON" to SdkClaudeClient.valueToJson(use._input())).toString(), isError = true))
                    memoryTool(name)?.let { return@async Outcome.Result(runMemory(id, name, Args(input), s)) }
                    val tool = catalog.find(name) ?: return@async Outcome.Result(StoredToolResult(id, "There's no tool called $name.", isError = true))
                    if (ctx == null) return@async Outcome.Result(StoredToolResult(id, "No budget is open in the app, so there's nothing to read yet.", isError = true))
                    if (!tool.writes) return@async Outcome.Result(runRead(id, tool, Args(input), ctx))
                    val group = tool.group!!
                    when {
                        !canWrite -> Outcome.Result(StoredToolResult(id, "This person can view the budget but not change it, so this can't be done.", isError = true))
                        group !in s.writable -> Outcome.Result(
                            StoredToolResult(id, "The \"${group.title}\" permission is off, so this change can't be proposed. Tell the person they can turn it on in the Assistant's settings, under Permissions.", isError = true),
                        )
                        else -> try {
                            val p = tool.preview(Args(input), ctx)
                            // The card is for the person: any id left in it becomes the name of what it points at.
                            val labels = runCatching { ctx.names.labels() }.getOrDefault(emptyMap())
                            Outcome.Proposal(
                                ProposedChange(id, name, group.key, Readable.humanize(p.title, labels), p.details.map { Readable.humanize(it, labels) }, input.toString()),
                            )
                        } catch (e: ToolInputException) {
                            Outcome.Result(StoredToolResult(id, e.message.orEmpty(), isError = true))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Outcome.Result(StoredToolResult(id, "Couldn't prepare this change: ${e.userMessage()}", isError = true))
                        }
                    }
                }
            }.awaitAll()
        }
        return conv.copy(
            waiting = conv.waiting + outcomes.filterIsInstance<Outcome.Result>().map { it.result },
            changes = conv.changes + outcomes.filterIsInstance<Outcome.Proposal>().associate { it.change.toolUseId to it.change },
            updatedAt = now(),
        )
    }

    private sealed interface Outcome {
        data class Result(val result: StoredToolResult) : Outcome
        data class Proposal(val change: ProposedChange) : Outcome
    }

    private suspend fun runRead(id: String, tool: AssistantTool, args: Args, ctx: ToolContext): StoredToolResult = try {
        StoredToolResult(id, tool.run(args, ctx).content.limited().toString(), isError = false)
    } catch (e: ToolInputException) {
        StoredToolResult(id, e.message.orEmpty(), isError = true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        StoredToolResult(id, "That didn't work: ${e.userMessage()}", isError = true)
    }

    private fun decide(conversationId: String, toolUseIds: List<String>, approve: Boolean) {
        if (toolUseIds.isEmpty()) return
        launch(conversationId) {
            var conv = store.get(conversationId) ?: return@launch
            val budget = conv.budgetId?.let(::BudgetId)
            for (toolUseId in toolUseIds) {
                val change = conv.changes[toolUseId]?.takeIf { it.status == ChangeStatus.Proposed } ?: continue
                if (!approve) {
                    conv = conv.copy(
                        changes = conv.changes + (toolUseId to change.copy(status = ChangeStatus.Declined, outcome = "Declined")),
                        waiting = conv.waiting + StoredToolResult(toolUseId, "The person declined this change.", isError = false),
                    )
                    store.save(conv)
                    continue
                }
                setLive(conversationId) { it.copy(activity = "Making the change…") }
                conv = conv.copy(changes = conv.changes + (toolUseId to change.copy(status = ChangeStatus.Approved)))
                store.save(conv)
                val tool = catalog.find(change.tool)
                val result = try {
                    if (tool == null || budget == null) throw IllegalStateException("This change can't run any more.")
                    // Permissions are checked again: they may have been turned off since the card appeared.
                    if (tool.group !in settings.settings.first().writable) throw ToolInputException("The \"${tool.group?.title}\" permission was turned off, so this didn't run.")
                    val out = tool.run(Args(json.parseToJsonElement(change.input) as JsonObject), ToolContext(budget, catalog.names(budget), today()))
                    notifier.changed()
                    conv = conv.copy(changes = conv.changes + (toolUseId to change.copy(status = ChangeStatus.Done, outcome = out.summary, undo = out.undo)))
                    StoredToolResult(toolUseId, out.content.limited().toString(), isError = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val why = (e as? ToolInputException)?.message ?: e.userMessage()
                    conv = conv.copy(changes = conv.changes + (toolUseId to change.copy(status = ChangeStatus.Failed, outcome = why)))
                    StoredToolResult(toolUseId, "The person approved this, but it failed: $why", isError = true)
                }
                conv = conv.copy(waiting = conv.waiting + result, updatedAt = now())
                store.save(conv)
            }
            if (!conv.awaitingApproval) {
                conv = conv.append(StoredMessage.Role.User, MessageParam.ofUserOfContentBlockParams(resultBlocks(conv))).copy(waiting = emptyList())
                store.save(conv)
                loop(conv)
            }
        }
    }

    // ── Memory: the assistant's own notes, kept on the phone ──

    private val memoryTools = listOf(
        AssistantTool(
            "save_memory",
            "Save a short note to remember in future chats: a goal and its date, how and when the person is paid, who's who, how they like advice. One fact per note. Never secrets (passwords, account numbers).",
            schema { string("text", "The note, one or two sentences.", required = true) },
            group = null,
        ) { _, _ -> error("handled by the agent") },
        AssistantTool(
            "update_memory",
            "Replace a saved note when it changes (ids are in the context).",
            schema {
                string("id", "The note's id.", required = true)
                string("text", "The new text.", required = true)
            },
            group = null,
        ) { _, _ -> error("handled by the agent") },
        AssistantTool(
            "delete_memory",
            "Delete a saved note that's wrong or no longer true.",
            schema { string("id", "The note's id.", required = true) },
            group = null,
        ) { _, _ -> error("handled by the agent") },
    )

    private fun memoryTool(name: String) = memoryTools.firstOrNull { it.name == name }

    private suspend fun runMemory(id: String, name: String, a: Args, s: AssistantSettings): StoredToolResult {
        if (!s.memory) return StoredToolResult(id, "Memory is turned off in the Assistant's settings, so nothing was saved.", isError = true)
        return try {
            when (name) {
                "save_memory" -> {
                    val note = MemoryNote("m" + UUID.randomUUID().toString().take(8), a.string("text").take(500), now(), now())
                    memory.put(note)
                    StoredToolResult(id, j("ok" to true, "id" to note.id).toString(), isError = false)
                }
                "update_memory" -> {
                    val old = memory.all().firstOrNull { it.id == a.string("id") } ?: throw ToolInputException("No note ${a.string("id")}")
                    memory.put(old.copy(text = a.string("text").take(500), updatedAt = now()))
                    StoredToolResult(id, j("ok" to true).toString(), isError = false)
                }
                else -> {
                    memory.delete(a.string("id"))
                    StoredToolResult(id, j("ok" to true).toString(), isError = false)
                }
            }
        } catch (e: ToolInputException) {
            StoredToolResult(id, e.message.orEmpty(), isError = true)
        }
    }

    companion object {
        const val MAX_ROUNDS = 30
    }
}

/** Words for what a tool is doing, shown while it runs. */
object Activity {
    fun of(tool: String): String = when {
        tool == "search_transactions" || tool == "get_transaction" -> "Looking through transactions…"
        tool.startsWith("get_budget") || tool == "list_budget_months" -> "Reading the budget…"
        tool == "list_accounts" || tool == "get_net_worth" -> "Checking balances…"
        tool == "get_categories" -> "Reading categories…"
        tool.startsWith("get_cash_flow") || tool.startsWith("get_spending") || tool == "get_money_flow" || tool == "get_daily_spending" -> "Running reports…"
        tool == "get_forecast" -> "Forecasting…"
        tool == "list_recurring" || tool == "get_subscriptions" -> "Checking bills and subscriptions…"
        tool.contains("goal") || tool.contains("target") -> "Checking goals…"
        tool == "get_income" -> "Reading income…"
        tool.contains("mortgage") -> "Reading the mortgage…"
        tool.contains("rule") -> "Reading rules…"
        tool.endsWith("memory") -> "Saving a note…"
        tool == "code_execution" || tool.contains("code") -> "Running the numbers…"
        tool.contains("web") -> "Searching the web…"
        else -> "Working…"
    }
}
