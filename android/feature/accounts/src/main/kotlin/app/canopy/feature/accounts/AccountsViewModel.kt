package app.canopy.feature.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.canopy.core.designsystem.component.Loadable
import app.canopy.core.domain.BudgetEngine
import app.canopy.core.domain.SelectedBudget
import app.canopy.core.domain.userMessage
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

data class AccountsSummary(val sections: List<AccountSection>) {
    private val all get() = sections.flatMap { it.accounts }
    val assets: Money get() = all.filter { !it.balance.isNegative }.map { it.balance }.sum()
    val liabilities: Money get() = all.filter { it.balance.isNegative }.map { it.balance }.sum()
    val netWorth: Money get() = assets + liabilities

    companion object {
        fun from(accounts: List<Account>) = AccountsSummary(
            accounts.filter { !it.closed }.groupBy { it.kind() }
                .map { (kind, list) -> AccountSection(kind, list.sortedByDescending { it.balance.abs() }) }
                .sortedBy { it.kind.ordinal },
        )
    }
}

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val engine: BudgetEngine,
    private val selectedBudget: SelectedBudget,
) : ViewModel() {
    private val state = MutableStateFlow<Loadable<AccountsSummary>>(Loadable.Loading)
    val uiState: StateFlow<Loadable<AccountsSummary>> = state.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        runCatching { engine.accounts(selectedBudget()) }
            .onSuccess { state.value = Loadable.Ready(AccountsSummary.from(it)) }
            .onFailure { state.value = Loadable.Failed(it.userMessage()) }
    }
}
