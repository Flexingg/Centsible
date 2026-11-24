package app.centsible.core.assistant

import kotlinx.coroutines.flow.Flow

/**
 * The Claude models the assistant can run on. [fallbacks]: server-side refusal fallback is
 * available. Prices are Anthropic's list prices in dollars per million tokens, for the cost
 * estimate in the chat (the bill is whatever Anthropic charges).
 */
enum class AssistantModel(
    val id: String,
    val label: String,
    val blurb: String,
    val fallbacks: Boolean,
    val inputPerM: Double,
    val outputPerM: Double,
    val cacheReadPerM: Double,
    /** Prompts longer than this many tokens cost [longMultiplier] times as much (Haiku). */
    val longPromptOver: Long = Long.MAX_VALUE,
    val longMultiplier: Double = 1.0,
) {
    Opus("claude-opus-5-5", "Claude Opus 5.5", "Deep planning and careful multi-step work. The default.", fallbacks = true, 4.0, 20.0, 0.20),
    Sonnet("claude-sonnet-5-5", "Claude Sonnet 5.5", "Quick and capable, for everyday questions. Costs about half as much.", fallbacks = true, 2.0, 10.0, 0.10),
    Haiku("claude-haiku-5-5", "Claude Haiku 5.5", "Fastest and cheapest, for simple lookups.", fallbacks = false, 0.10, 0.50, 0.01, longPromptOver = 100_000, longMultiplier = 5.0),
    Fable("claude-fable-5-2", "Claude Fable 5.2", "Anthropic's most capable model, for the hardest planning. Costs about twice Opus.", fallbacks = true, 8.0, 40.0, 0.20),
    ;

    /**
     * What one request cost, in dollars: writing the cache costs 1.25 times input (five-minute
     * cache), reading it a fraction; a web search is a cent.
     */
    fun cost(input: Long, output: Long, cacheRead: Long, cacheWrite: Long, webSearches: Long = 0): Double {
        val m = if (input + cacheRead + cacheWrite > longPromptOver) longMultiplier else 1.0
        val tokens = input * inputPerM + output * outputPerM + cacheRead * cacheReadPerM + cacheWrite * inputPerM * 1.25
        return tokens * m / 1_000_000.0 + webSearches * 0.01
    }

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Opus
        /** The model that answered (a refusal fallback can answer on another one); null when it's none of these. */
        fun served(id: String?) = entries.firstOrNull { id != null && (id == it.id || id.startsWith(it.id + "-")) }
    }
}

/** How hard Claude thinks before answering: deeper costs more and takes longer. */
enum class AssistantEffort(val wire: String, val label: String) {
    Low("low", "Quick"),
    Medium("medium", "Balanced"),
    High("high", "Thorough"),
    XHigh("xhigh", "Very thorough"),
    Max("max", "Maximum"),
    ;

    companion object {
        fun of(wire: String?) = entries.firstOrNull { it.wire == wire } ?: High
    }
}

/**
 * What the assistant may change, a group at a time. Reading is always allowed; with a
 * group on, each change is still shown as a card and happens only when approved.
 */
enum class ToolGroup(val key: String, val title: String, val description: String) {
    Transactions("transactions", "Edit transactions", "Add, edit, split, recategorize and delete transactions; mark reviewed; link transfers; merge duplicates."),
    Budget("budget", "Edit budget amounts", "Budget categories, move money, hold for next month, rollover, autopilot and covering overspending."),
    Categories("categories", "Edit categories", "Create, rename, hide, nest, move and delete categories and groups; notes; transfer (off the books)."),
    Rules("rules", "Rules, merchants & tags", "Create, edit, run and delete rules; rename, merge and delete merchants; tags."),
    Recurring("recurring", "Recurring & bills", "Create, edit, skip, post and delete scheduled bills and income; dismiss subscriptions."),
    Plans("plans", "Goals & plans", "Savings goals, targets, yearly budgets, budget automations, income settings, pay stubs and mortgages."),
    Accounts("accounts", "Accounts", "Add, rename, retype, close and reopen accounts; move them on or off budget; reconcile."),
    ;

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key }
    }
}

data class AssistantSettings(
    val model: AssistantModel = AssistantModel.Opus,
    val effort: AssistantEffort = AssistantEffort.High,
    /** Groups the assistant may propose changes in. Empty: read only. */
    val writable: Set<ToolGroup> = emptySet(),
    /** Run calculations (projections, amortization) in Anthropic's code sandbox. */
    val codeExecution: Boolean = true,
    /** Look things up on the web (rates, tax brackets). Off by default: queries leave the app. */
    val webSearch: Boolean = false,
    /** Keep notes between chats. */
    val memory: Boolean = true,
)

interface AssistantSettingsStore {
    val settings: Flow<AssistantSettings>
    suspend fun update(change: (AssistantSettings) -> AssistantSettings)
}

/** The person's own Anthropic API key, kept encrypted on this phone. */
interface ApiKeyStore {
    val hasKey: Flow<Boolean>
    suspend fun get(): String?
    suspend fun set(key: String)
    suspend fun clear()
}

/** A note the assistant keeps between chats ("saving for a house by 2028"). */
@kotlinx.serialization.Serializable
data class MemoryNote(val id: String, val text: String, val createdAt: Long, val updatedAt: Long)

interface MemoryStore {
    val notes: Flow<List<MemoryNote>>
    suspend fun all(): List<MemoryNote>
    suspend fun put(note: MemoryNote)
    suspend fun delete(id: String)
    suspend fun clear()
}
