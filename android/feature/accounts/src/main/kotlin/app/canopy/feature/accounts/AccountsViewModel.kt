package app.canopy.feature.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.canopy.core.designsystem.component.Loadable
import app.canopy.core.domain.BudgetEngine
import app.canopy.core.domain.SelectedBudget
import app.canopy.core.domain.userMessage
import app.canopy.core.domain.BudgetChanges
import app.canopy.core.domain.SessionStore
import app.canopy.core.model.Feature
import kotlinx.coroutines.flow.update
import app.canopy.core.model.Account
import app.canopy.core.model.Money
import app.canopy.core.model.sum
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Actual has no account types, so group by a heuristic until the planned
 * "account types" extension lets people set them explicitly.
 */
enum class AccountKind(val title: String) { Cash("Cash"), Credit("Credit cards"), Investments("Investments"), Loans("Loans") }

private val creditWords = listOf("card", "visa", "amex", "mastercard", "discover", "credit")
private val loanWords = listOf("loan", "mortgage", "heloc", "auto loan", "student")

fun Account.kind(): AccountKind {
    val n = name.lowercase()
    return when {
        loanWords.any { it in n } -> AccountKind.Loans
        creditWords.any { it in n } -> AccountKind.Credit
        offBudget && balance.isNegative -> AccountKind.Loans
        offBudget -> AccountKind.Investments
        balance.isNegative -> AccountKind.Credit
        else -> AccountKind.Cash
    }
}

data class AccountSection(val kind: AccountKind, val accounts: List<Account>) {
    val total: Money get() = accounts.map { it.balance }.sum()
}

data class AccountsSummary(val sections: List<AccountSection>, val closed: List<Account> = emptyList()) {
    private val all get() = sections.flatMap { it.accounts }
    val assets: Money get() = all.filter { !it.balance.isNegative }.map { it.balance }.sum()
    val liabilities: Money get() = all.filter { it.balance.isNegative }.map { it.balance }.sum()
    val netWorth: Money get() = assets + liabilities

    companion object {
        fun from(accounts: List<Account>) = AccountsSummary(
            accounts.filter { !it.closed }.groupBy { it.kind() }
                .map { (kind, list) -> AccountSection(kind, list.sortedByDescending { it.balance.abs() }) }
                .sortedBy { it.kind.ordinal },
            closed = accounts.filter { it.closed }.sortedBy { it.name },
        )
    }
}

data class AccountsUiState(
    val data: Loadable<AccountsSummary> = Loadable.Loading,
    val canWrite: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val engine: BudgetEngine,
    private val selectedBudget: SelectedBudget,
    private val sessions: SessionStore,
    changes: BudgetChanges,
) : ViewModel() {
    private val state = MutableStateFlow(AccountsUiState())
    val uiState: StateFlow<AccountsUiState> = state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch { changes.changes.collect { refresh() } }
    }

    fun refresh() = viewModelScope.launch {
        val budget = selectedBudget()
        val canWrite = sessions.current()?.member?.role?.canWrite == true &&
            runCatching { engine.capabilities().has(Feature.AccountsWrite) }.getOrDefault(false)
        runCatching { engine.accounts(budget) }
            .onSuccess { a -> state.update { it.copy(data = Loadable.Ready(AccountsSummary.from(a)), canWrite = canWrite) } }
            .onFailure { e -> state.update { it.copy(data = Loadable.Failed(e.userMessage())) } }
    }

    fun addAccount(name: String, offBudget: Boolean, startingBalance: Money) = viewModelScope.launch {
        runCatching { engine.createAccount(selectedBudget(), name, offBudget, startingBalance) }
            .onSuccess { a -> state.update { it.copy(message = "Added ${a.name}") } }
            .onFailure { e -> state.update { it.copy(message = e.userMessage()) } }
    }

    fun messageShown() = state.update { it.copy(message = null) }
}
