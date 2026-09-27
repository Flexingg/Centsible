package app.canopy.feature.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.canopy.core.designsystem.component.Loadable
import app.canopy.core.domain.BudgetEngine
import app.canopy.core.domain.SelectedBudget
import app.canopy.core.domain.TransactionQuery
import app.canopy.core.domain.userMessage
import app.canopy.core.model.Transaction
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TransactionsData(
    val items: List<Transaction>,
    val categoryNames: Map<String, String>,
    val accountNames: Map<String, String>,
    val nextCursor: String?,
    val loadingMore: Boolean = false,
)

@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val engine: BudgetEngine,
    private val selectedBudget: SelectedBudget,
) : ViewModel() {
    private val state = MutableStateFlow<Loadable<TransactionsData>>(Loadable.Loading)
    val uiState: StateFlow<Loadable<TransactionsData>> = state.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        runCatching {
            val budget = selectedBudget()
            val page = async { engine.transactions(budget, TransactionQuery(limit = 50)) }
            val cats = async { engine.categoryGroups(budget).flatMap { it.categories }.associate { it.id.raw to it.name } }
            val accts = async { engine.accounts(budget).associate { it.id.raw to it.name } }
            val p = page.await()
            TransactionsData(p.items, cats.await(), accts.await(), p.nextCursor)
        }
            .onSuccess { state.value = Loadable.Ready(it) }
            .onFailure { state.value = Loadable.Failed(it.userMessage()) }
    }

    fun loadMore() {
        val current = (state.value as? Loadable.Ready)?.value ?: return
        val cursor = current.nextCursor ?: return
        if (current.loadingMore) return
        state.value = Loadable.Ready(current.copy(loadingMore = true))
        viewModelScope.launch {
            runCatching { engine.transactions(selectedBudget(), TransactionQuery(limit = 50), cursor) }
                .onSuccess { p -> state.update { Loadable.Ready(current.copy(items = current.items + p.items, nextCursor = p.nextCursor)) } }
                .onFailure { state.update { Loadable.Ready(current.copy(loadingMore = false)) } }
        }
    }
}
