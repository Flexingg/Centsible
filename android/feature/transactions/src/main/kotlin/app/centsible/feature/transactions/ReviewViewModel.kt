package app.centsible.feature.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.domain.BudgetEngine
import app.centsible.core.domain.SelectedBudget
import app.centsible.core.domain.TransactionTools
import app.centsible.core.domain.userMessage
import app.centsible.core.model.BatchChange
import app.centsible.core.model.CategoryId
import app.centsible.core.model.PayeeId
import app.centsible.core.model.Transaction
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReviewData(
    /** What's left to go through, top card first. */
    val queue: List<Transaction>,
    /** How many need review on the bridge (the queue is the first page of them). */
    val total: Int,
    val more: Boolean,
    val since: String,
    val categoryNames: Map<String, String>,
    val accountNames: Map<String, String>,
    val categories: List<CategoryChoice>,
    /** Reviewed on this visit, for the progress line. */
    val done: Int = 0,
)

data class ReviewUiState(
    val data: Loadable<ReviewData> = Loadable.Loading,
    /** The card waiting for a category from the picker. */
    val categorizing: Transaction? = null,
    val confirmAll: Boolean = false,
    val message: String? = null,
    /** Just categorized a merchant's transaction: offer a rule so the next ones are done too. */
    val rulePrompt: RulePrompt? = null,
) {
    val left get() = (data as? Loadable.Ready)?.value?.let { (it.total - it.done).coerceAtLeast(it.queue.size) } ?: 0
}

data class RulePrompt(val payeeId: PayeeId, val payeeName: String, val categoryId: CategoryId, val categoryName: String?)

/** The review inbox: new and uncategorized transactions as a stack of cards. */
@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val engine: BudgetEngine,
    private val tools: TransactionTools,
    private val selectedBudget: SelectedBudget,
) : ViewModel() {
    private val state = MutableStateFlow(ReviewUiState())
    val uiState: StateFlow<ReviewUiState> = state.asStateFlow()

    init {
        load()
    }

    fun load() = viewModelScope.launch {
        runCatching { fetch() }
            .onSuccess { d -> state.update { it.copy(data = Loadable.Ready(d)) } }
            .onFailure { e -> state.update { it.copy(data = Loadable.Failed(e.userMessage())) } }
    }

    private suspend fun fetch(): ReviewData = coroutineScope {
        val budget = selectedBudget()
        val inbox = async { tools.inbox(budget, PAGE) }
        val groups = async { engine.categoryGroups(budget) }
        val accounts = async { engine.accounts(budget) }
        val g = groups.await()
        val i = inbox.await()
        ReviewData(
            queue = i.items,
            total = i.total,
            more = i.more,
            since = i.since,
            categoryNames = g.flatMap { it.categories }.associate { it.id.raw to it.name },
            accountNames = accounts.await().associate { it.id.raw to it.name },
            categories = g.filter { !it.hidden }.flatMap { grp -> grp.categories.filter { !it.hidden }.map { CategoryChoice(it.id, it.name, grp.name) } },
        )
    }

    /** Off to the rule editor for this merchant; see [resumed]. */
    private var ruleFor: Pair<PayeeId, String?>? = null
    /** Off to the transaction editor. */
    private var away = false

    fun makingRule(payee: PayeeId) {
        val name = state.value.rulePrompt?.takeIf { it.payeeId == payee }?.payeeName
            ?: state.value.data.valueOrNull?.queue?.firstOrNull { it.payeeId == payee }?.payeeName
        ruleFor = payee to name
        rulePromptShown()
    }

    fun leaving() {
        away = true
    }

    /**
     * Back from the rule editor: pick up what the rule just filed, and count those as
     * reviewed (you just decided where that merchant goes), so they don't need a second look.
     */
    fun resumed() {
        val before = state.value.data.valueOrNull ?: return
        val rule = ruleFor
        ruleFor = null
        if (rule == null) {
            if (away) {
                away = false
                viewModelScope.launch {
                    // Your edits count as reviewed on the bridge, so an edited card drops out.
                    runCatching { fetch() }.onSuccess { d ->
                        updateData { d.copy(done = before.done, total = d.total + before.done) }
                    }
                }
            }
            return
        }
        away = false
        val (payee, name) = rule
        viewModelScope.launch {
            runCatching {
                val fresh = fetch()
                val was = before.queue.associate { it.id to it.categoryId }
                val filed = fresh.queue.filter { t ->
                    t.payeeId == payee && t.categoryId != null && (t.id !in was || was[t.id] != t.categoryId)
                }
                if (filed.isNotEmpty()) tools.markReviewed(selectedBudget(), filed.map { it.id })
                val ids = filed.map { it.id }.toSet()
                fresh.copy(queue = fresh.queue.filter { it.id !in ids }, done = before.done + filed.size, total = fresh.total + before.done) to filed.size
            }
                .onSuccess { (d, n) ->
                    val who = name ?: "that merchant"
                    state.update { it.copy(data = Loadable.Ready(d), message = if (n > 0) "Your rule filed $n more from $who" else null) }
                }
                .onFailure { e -> state.update { it.copy(message = e.userMessage()) } }
        }
    }

    /** Needs a category before it can be approved (on-budget, not a transfer or split). */
    fun needsCategory(t: Transaction) = t.categoryId == null && !t.isTransfer && !t.isParent

    /** "Looks right": reviewed as it is. Asks for a category first when it has none. */
    fun approve(t: Transaction) {
        if (needsCategory(t)) {
            state.update { it.copy(categorizing = t) }
            return
        }
        pop(t)
        viewModelScope.launch {
            runCatching { tools.markReviewed(selectedBudget(), listOf(t.id)) }.onFailure { e -> restore(t, e) }
        }
    }

    /** Not now: to the back of the stack (still unreviewed). */
    fun later(t: Transaction) = updateData { d -> d.copy(queue = d.queue - t + t) }

    fun changeCategory(t: Transaction) = state.update { it.copy(categorizing = t) }
    fun dismissPicker() = state.update { it.copy(categorizing = null) }

    /** Setting the category reviews it too (the bridge counts your edits as reviewed). */
    fun setCategory(category: CategoryId?) {
        val t = state.value.categorizing ?: return
        val prompt = if (category != null && t.payeeId != null && t.payeeName != null) {
            RulePrompt(t.payeeId!!, t.payeeName!!, category, state.value.data.valueOrNull?.categoryNames?.get(category.raw))
        } else {
            null
        }
        state.update { it.copy(categorizing = null, rulePrompt = prompt) }
        pop(t)
        viewModelScope.launch {
            runCatching {
                tools.batch(selectedBudget(), listOf(t.id), BatchChange.Category(category))
                tools.markReviewed(selectedBudget(), listOf(t.id))
            }.onFailure { e -> restore(t, e) }
        }
    }

    fun askReviewAll(ask: Boolean) = state.update { it.copy(confirmAll = ask) }

    fun reviewAll() {
        state.update { it.copy(confirmAll = false) }
        viewModelScope.launch {
            runCatching { tools.reviewAll(selectedBudget()) }
                .onSuccess { updateData { d -> d.copy(queue = emptyList(), done = d.total, more = false) } }
                .onFailure { e -> state.update { it.copy(message = e.userMessage()) } }
        }
    }

    fun messageShown() = state.update { it.copy(message = null) }
    fun rulePromptShown() = state.update { it.copy(rulePrompt = null) }

    private fun pop(t: Transaction) {
        updateData { d -> d.copy(queue = d.queue - t, done = d.done + 1) }
        // The first page is done but more are waiting: fetch the next ones.
        val d = state.value.data.valueOrNull ?: return
        if (d.queue.isEmpty() && d.total > d.done) load()
    }

    private fun restore(t: Transaction, e: Throwable) {
        updateData { d -> d.copy(queue = listOf(t) + d.queue, done = (d.done - 1).coerceAtLeast(0)) }
        state.update { it.copy(message = e.userMessage()) }
    }

    private fun updateData(f: (ReviewData) -> ReviewData) = state.update { s ->
        val d = s.data.valueOrNull ?: return@update s
        s.copy(data = Loadable.Ready(f(d)))
    }

    private companion object {
        const val PAGE = 50
    }
}
