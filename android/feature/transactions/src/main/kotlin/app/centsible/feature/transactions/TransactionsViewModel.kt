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

data class TransactionFilters(
    val search: String = "",
    val needsCategory: Boolean = false,
    val account: AccountId? = null,
) {
    val isActive get() = search.isNotBlank() || needsCategory || account != null

    fun toQuery(limit: Int) = TransactionQuery(
        accountId = account,
        search = search.trim().takeIf { it.isNotEmpty() },
        uncategorized = needsCategory,
        limit = limit,
    )
}

data class TransactionsData(
    val items: List<Transaction>,
    val categoryNames: Map<String, String>,
    val accountNames: Map<String, String>,
    val nextCursor: String?,
    val loadingMore: Boolean = false,
    val accounts: List<Account> = emptyList(),
    /** Rows that arrived since the last load (a transaction just saved), so they can land in. */
    val fresh: Set<String> = emptySet(),
)

data class TransactionsUiState(
    val filters: TransactionFilters = TransactionFilters(),
    val data: Loadable<TransactionsData> = Loadable.Loading,
)

@HiltViewModel
class TransactionsViewModel @Inject constructor(
    savedState: androidx.lifecycle.SavedStateHandle,
    private val engine: BudgetEngine,
    private val selectedBudget: SelectedBudget,
    changes: BudgetChanges,
) : ViewModel() {
    private val state = MutableStateFlow(TransactionsUiState(filters = TransactionFilters(search = savedState.get<String>(ARG_QUERY).orEmpty())))
    val uiState: StateFlow<TransactionsUiState> = state.asStateFlow()
    private var loadJob: Job? = null
    private var shownFilters: TransactionFilters? = null

    init {
        refresh()
        viewModelScope.launch { changes.changes.collect { refresh(debounceMs = 0) } }
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
                val cats = async { engine.categoryGroups(budget).flatMap { it.categories }.associate { it.id.raw to it.name } }
                val accounts = async { engine.accounts(budget) }
                val p = page.await()
                val a = accounts.await()
                val fresh = before?.let { seen -> p.items.map { it.id.raw }.filterNot { it in seen }.toSet() }.orEmpty()
                TransactionsData(p.items, cats.await(), a.associate { it.id.raw to it.name }, p.nextCursor, accounts = a, fresh = fresh)
            }
                .onSuccess { d -> shownFilters = filters; state.update { it.copy(data = Loadable.Ready(d)) } }
                .onFailure { e -> if (e !is kotlinx.coroutines.CancellationException) state.update { it.copy(data = Loadable.Failed(e.userMessage())) } }
        }
    }

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
        /** Optional starting search, e.g. "#vacation" from the Tags screen. */
        const val ARG_QUERY = "q"
    }
}
