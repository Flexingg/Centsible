package app.centsible.feature.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.domain.BudgetEngine
import app.centsible.core.domain.SelectedBudget
import app.centsible.core.domain.userMessage
import app.centsible.core.domain.BudgetChanges
import app.centsible.core.domain.SessionStore
import app.centsible.core.model.Feature
import kotlinx.coroutines.flow.update
import app.centsible.core.model.Account
import app.centsible.core.model.AccountId
import app.centsible.core.model.AccountType
import app.centsible.core.model.Money
import app.centsible.core.model.sum
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val mortgageWords = listOf("mortgage", "heloc", "home equity")
private val loanWords = listOf("loan", "auto", "student", "car note")
private val creditWords = listOf("card", "visa", "amex", "mastercard", "discover", "credit")
private val savingsWords = listOf("saving", "save", "reserve", "emergency", "money market", "rainy")
private val investmentWords = listOf("brokerage", "invest", "401k", "401(k)", "ira", "roth", "hsa", "retirement", "stocks")

/**
 * The account's type: the one set in Centsible, or (when nothing is set yet) a guess from
 * the name and balance. Actual itself has no account types.
 */
fun Account.kind(): AccountType = type ?: guessType()

fun Account.guessType(): AccountType {
    val n = name.lowercase()
    return when {
        mortgageWords.any { it in n } -> AccountType.Mortgage
        loanWords.any { it in n } -> AccountType.Loan
        creditWords.any { it in n } -> AccountType.Credit
        investmentWords.any { it in n } -> AccountType.Investment
        savingsWords.any { it in n } -> AccountType.Savings
        offBudget && balance.isNegative -> AccountType.Loan
        offBudget -> AccountType.Investment
        balance.isNegative -> AccountType.Credit
        else -> AccountType.Cash
    }
}

data class AccountSection(val kind: AccountType, val accounts: List<Account>) {
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

    fun addAccount(name: String, offBudget: Boolean, startingBalance: Money, type: AccountType? = null) = viewModelScope.launch {
        runCatching { engine.createAccount(selectedBudget(), name, offBudget, startingBalance, type) }
            .onSuccess { a -> state.update { it.copy(message = "Added ${a.name}") } }
            .onFailure { e -> state.update { it.copy(message = e.userMessage()) } }
    }

    /** Sets (or, with null, clears) an account's type; the list regroups when the change arrives. */
    fun setType(id: AccountId, type: AccountType?) = viewModelScope.launch {
        runCatching { engine.setAccountType(selectedBudget(), id, type) }
            .onSuccess { a -> state.update { it.copy(message = if (type == null) "${a.name}: type cleared (guessed from the name)" else "${a.name} is now ${type.singular.lowercase()}") } }
            .onFailure { e -> state.update { it.copy(message = e.userMessage()) } }
    }

    fun messageShown() = state.update { it.copy(message = null) }
}
