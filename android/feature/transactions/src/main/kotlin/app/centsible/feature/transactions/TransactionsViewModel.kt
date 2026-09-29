package app.centsible.feature.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.domain.BudgetChanges
import app.centsible.core.domain.BudgetEngine
import app.centsible.core.domain.SelectedBudget
import app.centsible.core.domain.TransactionQuery
import app.centsible.core.domain.userMessage
import app.centsible.core.model.Account
import app.centsible.core.model.AccountId
import app.centsible.core.model.Transaction
import app.centsible.core.model.BatchChange
import app.centsible.core.model.CategoryGroupId
import app.centsible.core.model.CategoryId
import app.centsible.core.model.PayeeId
import app.centsible.core.model.TransactionId
import app.centsible.core.domain.TransactionTools
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where a tap-through came from: "Food in September", "Netflix", a dial segment. */
data class TransactionScope(
    val title: String,
    val categoryId: CategoryId? = null,
    val groupId: CategoryGroupId? = null,
    val payeeId: PayeeId? = null,
    val since: String? = null,
    val until: String? = null,
)

data class TransactionFilters(
    val search: String = "",
    val needsCategory: Boolean = false,
    val account: AccountId? = null,
    val scope: TransactionScope? = null,
) {
    val isActive get() = search.isNotBlank() || needsCategory || account != null || scope != null

    fun toQuery(limit: Int) = TransactionQuery(
        accountId = account,
        categoryId = scope?.categoryId,
        groupId = scope?.groupId,
        payeeId = scope?.payeeId,
        since = scope?.since,
        until = scope?.until,
        search = search.trim().takeIf { it.isNotEmpty() },
        uncategorized = needsCategory,
        limit = limit,
    )
}

/** A category to pick from (swipe to categorize, bulk edit). */
data class CategoryChoice(val id: CategoryId, val name: String, val group: String)

/** Deleted rows are hidden first and only really deleted after the undo window. */
data class PendingDelete(val ids: Set<TransactionId>, val label: String)

data class TransactionsData(
    val items: List<Transaction>,
    val categoryNames: Map<String, String>,
    val accountNames: Map<String, String>,
    val nextCursor: String?,
    val loadingMore: Boolean = false,
    val accounts: List<Account> = emptyList(),
    val categories: List<CategoryChoice> = emptyList(),
    /** Rows that arrived since the last load (a transaction just saved), so they can land in. */
    val fresh: Set<String> = emptySet(),
)

data class TransactionsUiState(
    val filters: TransactionFilters = TransactionFilters(),
    val data: Loadable<TransactionsData> = Loadable.Loading,
    /** Multi-select: empty when not selecting. */
    val selected: Set<TransactionId> = emptySet(),
    val pendingDelete: PendingDelete? = null,
    /** Rows waiting for a category from the picker. */
    val categorizing: List<TransactionId>? = null,
    val moving: List<TransactionId>? = null,
    /** How many transactions are waiting in this person's review inbox (null: unknown). */
    val reviewCount: Int? = null,
    val canEdit: Boolean = true,
    val message: String? = null,
) {
    val selecting get() = selected.isNotEmpty()
}

@HiltViewModel
class TransactionsViewModel @Inject constructor(
    savedState: androidx.lifecycle.SavedStateHandle,
    private val engine: BudgetEngine,
    private val selectedBudget: SelectedBudget,
    private val tools: TransactionTools,
    private val sessions: app.centsible.core.domain.SessionStore,
    changes: BudgetChanges,
) : ViewModel() {
    private val state = MutableStateFlow(
        TransactionsUiState(filters = TransactionFilters(search = savedState.get<String>(ARG_QUERY).orEmpty(), scope = scopeFrom(savedState))),
    )
    val uiState: StateFlow<TransactionsUiState> = state.asStateFlow()
    private var loadJob: Job? = null
    private var shownFilters: TransactionFilters? = null

    init {
        refresh()
        refreshReviewCount()
        viewModelScope.launch { changes.changes.collect { refresh(debounceMs = 0); refreshReviewCount() } }
    }

    fun setSearch(text: String) = updateFilters(debounceMs = 300) { it.copy(search = text) }
    fun toggleNeedsCategory() = updateFilters { it.copy(needsCategory = !it.needsCategory) }
    fun setAccount(id: AccountId?) = updateFilters { it.copy(account = id) }
    fun clearFilters() = updateFilters { TransactionFilters() }

    private fun updateFilters(debounceMs: Long = 0, f: (TransactionFilters) -> TransactionFilters) {
        state.update { it.copy(filters = f(it.filters)) }
        refresh(debounceMs)
    }

    fun refresh(debounceMs: Long = 0) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val filters = state.value.filters
            val before = state.value.data.valueOrNull?.takeIf { filters == shownFilters }?.items?.map { it.id.raw }?.toSet()
            runCatching {
                val budget = selectedBudget()
                val page = async { engine.transactions(budget, filters.toQuery(PAGE)) }
                val cats = async { engine.categoryGroups(budget) }
                val accounts = async { engine.accounts(budget) }
                val p = page.await()
                val a = accounts.await()
                val groups = cats.await()
                val fresh = before?.let { seen -> p.items.map { it.id.raw }.filterNot { it in seen }.toSet() }.orEmpty()
                TransactionsData(
                    p.items,
                    groups.flatMap { it.categories }.associate { it.id.raw to it.name },
                    a.associate { it.id.raw to it.name },
                    p.nextCursor,
                    accounts = a,
                    categories = groups.filter { !it.hidden }.flatMap { g -> g.categories.filter { !it.hidden }.map { CategoryChoice(it.id, it.name, g.name) } },
                    fresh = fresh,
                )
            }
                .onSuccess { d ->
                    shownFilters = filters
                    val present = d.items.flatMap { listOf(it.id) + it.subtransactions.map { s -> s.id } }.toSet()
                    state.update { it.copy(data = Loadable.Ready(d), selected = it.selected.filter { id -> id in present }.toSet()) }
                }
                .onFailure { e -> if (e !is kotlinx.coroutines.CancellationException) state.update { it.copy(data = Loadable.Failed(e.userMessage())) } }
        }
    }

    private fun refreshReviewCount() = viewModelScope.launch {
        val role = sessions.current()?.member?.role
        val count = runCatching { tools.inbox(selectedBudget(), limit = 1).total }.getOrNull()
        state.update { it.copy(reviewCount = count, canEdit = role != app.centsible.core.model.Role.Viewer) }
    }

    fun clearScope() = updateFilters { it.copy(scope = null) }

    // ── Multi-select ──

    fun toggleSelected(id: TransactionId) = state.update { s ->
        s.copy(selected = if (id in s.selected) s.selected - id else s.selected + id)
    }

    fun selectAll() = state.update { s -> s.copy(selected = s.data.valueOrNull?.items?.map { it.id }?.toSet().orEmpty()) }
    fun clearSelection() = state.update { it.copy(selected = emptySet()) }

    // ── Categorize / move (one row from a swipe, or the selection) ──

    fun startCategorizing(ids: List<TransactionId>) = state.update { it.copy(categorizing = ids) }
    fun startMoving(ids: List<TransactionId>) = state.update { it.copy(moving = ids) }
    fun dismissPicker() = state.update { it.copy(categorizing = null, moving = null) }

    fun categorize(category: CategoryId?) {
        val ids = state.value.categorizing ?: return
        state.update { it.copy(categorizing = null, selected = emptySet()) }
        apply(ids, BatchChange.Category(category)) { n -> if (n == 1) "Category set" else "Category set on $n transactions" }
    }

    fun move(account: AccountId) {
        val ids = state.value.moving ?: return
        state.update { it.copy(moving = null, selected = emptySet()) }
        apply(ids, BatchChange.Account(account)) { n -> if (n == 1) "Moved" else "Moved $n transactions" }
    }

    fun setCleared(cleared: Boolean) {
        val ids = state.value.selected.toList()
        state.update { it.copy(selected = emptySet()) }
        apply(ids, BatchChange.Cleared(cleared)) { n -> "${if (cleared) "Cleared" else "Uncleared"} $n" }
    }

    private fun apply(ids: List<TransactionId>, change: BatchChange, done: (Int) -> String) = viewModelScope.launch {
        runCatching { tools.batch(selectedBudget(), ids, change) }
            .onSuccess { r ->
                val skipped = r.skipped.size
                state.update {
                    it.copy(message = done(r.updated) + if (skipped > 0) " · $skipped skipped (splits and transfers can't take a category)" else "")
                }
                refreshReviewCount()
            }
            .onFailure { e -> state.update { it.copy(message = e.userMessage()) } }
    }

    // ── Delete with undo ──

    private var deleteJob: Job? = null

    fun delete(ids: List<TransactionId>) {
        commitDelete() // one undo at a time: the previous one goes through now
        val label = if (ids.size == 1) "Transaction deleted" else "${ids.size} transactions deleted"
        state.update { it.copy(pendingDelete = PendingDelete(ids.toSet(), label), selected = emptySet()) }
        deleteJob = viewModelScope.launch {
            delay(UNDO_MS)
            commitDelete()
        }
    }

    fun undoDelete() {
        deleteJob?.cancel()
        deleteJob = null
        state.update { it.copy(pendingDelete = null) }
    }

    private fun commitDelete() {
        val pending = state.value.pendingDelete ?: return
        deleteJob?.cancel()
        deleteJob = null
        state.update { it.copy(pendingDelete = null) }
        // Survives leaving the screen: the app scope isn't needed, the view model outlives the undo bar.
        viewModelScope.launch {
            runCatching { tools.batch(selectedBudget(), pending.ids.toList(), BatchChange.Delete) }
                .onFailure { e -> state.update { it.copy(message = e.userMessage()) } }
        }
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    override fun onCleared() {
        // Leaving with an undo still showing: the delete goes ahead.
        val pending = state.value.pendingDelete ?: return
        kotlinx.coroutines.GlobalScope.launch {
            runCatching { tools.batch(selectedBudget(), pending.ids.toList(), BatchChange.Delete) }
        }
    }

    fun messageShown() = state.update { it.copy(message = null) }

    fun loadMore() {
        val current = state.value.data.valueOrNull ?: return
        val cursor = current.nextCursor ?: return
        if (current.loadingMore) return
        state.update { it.copy(data = Loadable.Ready(current.copy(loadingMore = true))) }
        viewModelScope.launch {
            runCatching { engine.transactions(selectedBudget(), state.value.filters.toQuery(PAGE), cursor) }
                .onSuccess { p -> state.update { it.copy(data = Loadable.Ready(current.copy(items = current.items + p.items, nextCursor = p.nextCursor))) } }
                .onFailure { state.update { it.copy(data = Loadable.Ready(current.copy(loadingMore = false))) } }
        }
    }

    companion object {
        private const val PAGE = 50
        private const val UNDO_MS = 4_000L
        /** Optional starting search, e.g. "#vacation" from the Tags screen. */
        const val ARG_QUERY = "q"
        // A tap-through's scope (see TransactionScope).
        const val ARG_TITLE = "title"
        const val ARG_CATEGORY = "category"
        const val ARG_GROUP = "group"
        const val ARG_PAYEE = "payee"
        const val ARG_SINCE = "since"
        const val ARG_UNTIL = "until"

        fun scopeFrom(saved: androidx.lifecycle.SavedStateHandle): TransactionScope? {
            fun arg(k: String) = saved.get<String>(k)?.takeIf { it.isNotBlank() }
            val title = arg(ARG_TITLE) ?: return null
            return TransactionScope(
                title,
                arg(ARG_CATEGORY)?.let(::CategoryId),
                arg(ARG_GROUP)?.let(::CategoryGroupId),
                arg(ARG_PAYEE)?.let(::PayeeId),
                arg(ARG_SINCE),
                arg(ARG_UNTIL),
            )
        }
    }
}
