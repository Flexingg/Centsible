package app.canopy.feature.accounts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.canopy.core.designsystem.component.Loadable
import app.canopy.core.domain.BudgetChanges
import app.canopy.core.domain.BudgetEngine
import app.canopy.core.domain.SelectedBudget
import app.canopy.core.domain.SessionStore
import app.canopy.core.domain.TransactionQuery
import app.canopy.core.domain.userMessage
import app.canopy.core.model.Account
import app.canopy.core.model.AccountId
import app.canopy.core.model.Feature
import app.canopy.core.model.Transaction
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AccountDetail(
    val account: Account,
    val transactions: List<Transaction>,
    val nextCursor: String?,
    val otherAccounts: List<Account>,
    val categoryNames: Map<String, String>,
)

data class AccountDetailUiState(
    val data: Loadable<AccountDetail> = Loadable.Loading,
    val canWrite: Boolean = false,
    val message: String? = null,
    /** Set when the account no longer exists (Actual deletes empty accounts on close). */
    val gone: Boolean = false,
)

@HiltViewModel
class AccountDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val engine: BudgetEngine,
    private val selectedBudget: SelectedBudget,
    private val sessions: SessionStore,
    changes: BudgetChanges,
) : ViewModel() {
    private val id = AccountId(checkNotNull(savedState.get<String>(ARG_ID)))
    private val state = MutableStateFlow(AccountDetailUiState())
    val uiState: StateFlow<AccountDetailUiState> = state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch { changes.changes.collect { refresh() } }
    }

    fun refresh() = viewModelScope.launch {
        runCatching {
            val budget = selectedBudget()
            val accounts = async { engine.accounts(budget) }
            val txs = async { engine.transactions(budget, TransactionQuery(accountId = id, limit = 50)) }
            val cats = async { engine.categoryGroups(budget).flatMap { it.categories }.associate { it.id.raw to it.name } }
            val canWrite = sessions.current()?.member?.role?.canWrite == true && runCatching { engine.capabilities().has(Feature.AccountsWrite) }.getOrDefault(false)
            val all = accounts.await()
            val account = all.firstOrNull { it.id == id } ?: return@runCatching null
            val page = txs.await()
            AccountDetail(account, page.items, page.nextCursor, all.filter { it.id != id && !it.closed }, cats.await()) to canWrite
        }
            .onSuccess { r ->
                if (r == null) state.update { it.copy(gone = true) }
                else state.update { it.copy(data = Loadable.Ready(r.first), canWrite = r.second) }
            }
            .onFailure { e -> state.update { it.copy(data = Loadable.Failed(e.userMessage())) } }
    }

    fun rename(name: String) = act("Renamed") { engine.renameAccount(selectedBudget(), id, name.trim()) }

    fun close(moveBalanceTo: AccountId?) = act("Account closed") {
        if (engine.closeAccount(selectedBudget(), id, moveBalanceTo, null) == null) state.update { it.copy(gone = true) }
    }

    fun reopen() = act("Account reopened") { engine.reopenAccount(selectedBudget(), id) }

    fun messageShown() = state.update { it.copy(message = null) }

    private fun act(success: String, block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }
            .onSuccess { state.update { it.copy(message = success) } }
            .onFailure { e -> state.update { it.copy(message = e.userMessage()) } }
    }

    companion object {
        const val ARG_ID = "accountId"
    }
}
