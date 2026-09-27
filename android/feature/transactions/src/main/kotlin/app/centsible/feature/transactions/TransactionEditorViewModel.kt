package app.centsible.feature.transactions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.centsible.core.domain.BridgeException
import app.centsible.core.domain.BudgetEngine
import app.centsible.core.domain.SelectedBudget
import app.centsible.core.domain.SessionStore
import app.centsible.core.domain.userMessage
import app.centsible.core.model.Account
import app.centsible.core.model.AccountId
import app.centsible.core.model.BudgetId
import app.centsible.core.model.Capabilities
import app.centsible.core.model.CategoryGroup
import app.centsible.core.model.Feature
import app.centsible.core.model.Payee
import app.centsible.core.model.Transaction
import app.centsible.core.model.TransactionId
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditorUiState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val original: Transaction? = null,
    val form: TransactionForm = TransactionForm(),
    val accounts: List<Account> = emptyList(),
    val groups: List<CategoryGroup> = emptyList(),
    val payees: List<Payee> = emptyList(),
    val canEdit: Boolean = false,
    val canDelete: Boolean = false,
    val canSplit: Boolean = false,
    val canTransfer: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
    val canCreateRules: Boolean = false,
    /** "Always use this category for {payee}": saved as an Actual rule after the transaction. */
    val rememberCategory: Boolean = false,
) {
    /** Offered for a named, non-transfer payee with one category. */
    val canRememberCategory: Boolean
        get() = canCreateRules && canEdit && form.kind != TxKind.Transfer && !form.isSplit &&
            form.categoryId != null && form.payee.isNotBlank() &&
            (original == null || original.categoryId != form.categoryId)

    val isNew get() = original == null
    val title get() = if (isNew) "New transaction" else if (canEdit) "Edit transaction" else "Transaction"

    /** Payees that match what's typed, most relevant first. Transfer payees excluded. */
    val payeeSuggestions: List<String>
        get() {
            val typed = form.payee.trim()
            if (typed.isEmpty()) return emptyList()
            return payees.asSequence()
                .filter { it.transferAccountId == null && it.name.contains(typed, ignoreCase = true) && !it.name.equals(typed, ignoreCase = true) }
                .sortedBy { if (it.name.startsWith(typed, ignoreCase = true)) 0 else 1 }
                .map { it.name }
                .take(4)
                .toList()
        }
}

@HiltViewModel
class TransactionEditorViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val engine: BudgetEngine,
    private val selectedBudget: SelectedBudget,
    private val sessions: SessionStore,
    private val planning: app.centsible.core.domain.PlanningGateway,
) : ViewModel() {
    private val transactionId: String? = savedState.get<String>(ARG_ID)?.takeIf { it.isNotBlank() }
    private val presetAccount: String? = savedState.get<String>(ARG_ACCOUNT)?.takeIf { it.isNotBlank() }
    private val newId = TransactionId(UUID.randomUUID().toString()) // stable across retries → idempotent create

    private val state = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = state.asStateFlow()
    private var budget: BudgetId? = null

    init { load() }

    fun load() = viewModelScope.launch {
        state.update { it.copy(loading = true, loadError = null) }
        runCatching {
            val b = selectedBudget().also { budget = it }
            val caps = async { runCatching { engine.capabilities() }.getOrDefault(Capabilities.None) }
            val accounts = async { engine.accounts(b) }
            val groups = async { engine.categoryGroups(b) }
            val payees = async { engine.payees(b) }
            val original = transactionId?.let { async { engine.transaction(b, TransactionId(it)) } }
            val canWrite = sessions.current()?.member?.role?.canWrite == true
            val c = caps.await()
            val tx = original?.await()
            val open = accounts.await().filter { !it.closed || it.id == tx?.accountId }
            val p = payees.await()
            val form = tx?.let { TransactionForm.from(it, p) } ?: TransactionForm(
                accountId = presetAccount?.let(::AccountId) ?: open.firstOrNull { !it.offBudget }?.id ?: open.firstOrNull()?.id,
            )
            EditorUiState(
                loading = false,
                original = tx,
                form = form,
                accounts = open,
                groups = groups.await(),
                payees = p,
                canEdit = canWrite && c.has(if (tx == null) Feature.TransactionsCreate else Feature.TransactionsUpdate) && tx?.reconciled != true,
                canDelete = canWrite && tx != null && c.has(Feature.TransactionsDelete),
                canSplit = c.has(Feature.TransactionsSplits),
                canTransfer = c.has(Feature.TransactionsTransfers),
                canCreateRules = canWrite && c.has(Feature.RulesWrite),
            )
        }
            .onSuccess { s -> state.value = s }
            .onFailure { e -> state.update { it.copy(loading = false, loadError = e.userMessage()) } }
    }

    fun edit(f: (TransactionForm) -> TransactionForm) = state.update { it.copy(form = f(it.form), error = null) }

    fun addSplit() = edit { form ->
        val nextKey = (form.splits.maxOfOrNull { it.key } ?: 0) + 1
        if (form.splits.isEmpty()) {
            // Start with the current category carrying the whole amount, plus an empty row.
            form.copy(splits = listOf(SplitRow(nextKey, amount = form.amount, categoryId = form.categoryId), SplitRow(nextKey + 1)), categoryId = null)
        } else {
            form.copy(splits = form.splits + SplitRow(nextKey, amount = form.splitRemaining?.takeIf { it.minor > 0 }?.let(app.centsible.core.designsystem.component.MoneyInput::toInput) ?: ""))
        }
    }

    fun updateSplit(key: Long, f: (SplitRow) -> SplitRow) = edit { form -> form.copy(splits = form.splits.map { if (it.key == key) f(it) else it }) }

    fun removeSplit(key: Long) = edit { form ->
        val rest = form.splits.filter { it.key != key }
        // One split left means no split: fold its category back into the transaction.
        if (rest.size == 1) form.copy(splits = emptyList(), categoryId = rest.single().categoryId) else form.copy(splits = rest)
    }

    fun save() {
        val s = state.value
        val b = budget ?: return
        s.form.problem()?.let { p -> state.update { it.copy(error = p) }; return }
        state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            runCatching {
                val original = s.original
                if (original == null) engine.createTransaction(b, s.form.toNew(newId, s.payees))
                else engine.updateTransaction(b, original.id, s.form.toPatch(original, s.payees))
            }
                .onSuccess {
                    if (s.rememberCategory && s.canRememberCategory) rememberCategory(b, s.form)
                    state.update { it.copy(saving = false, done = true) }
                }
                .onFailure { e ->
                    if (e is BridgeException.QueuedOffline) state.update { it.copy(saving = false, done = true) }
                    else state.update { it.copy(saving = false, error = e.userMessage()) }
                }
        }
    }

    fun setRememberCategory(on: Boolean) = state.update { it.copy(rememberCategory = on) }

    /**
     * Best effort: the transaction is already saved, so a failed rule never blocks closing.
     * A new payee only has an id after the save, so it's looked up again by name.
     */
    private suspend fun rememberCategory(b: BudgetId, form: TransactionForm) {
        val category = form.categoryId ?: return
        val name = form.payee.trim()
        runCatching {
            val payee = (state.value.payees.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: engine.payees(b).firstOrNull { it.name.equals(name, ignoreCase = true) }) ?: return
            planning.createRule(
                b,
                app.centsible.core.model.RuleDraft(
                    conditions = listOf(app.centsible.core.model.RuleClause("payee", "is", app.centsible.core.model.RuleValue.Text(payee.id.raw), "id")),
                    actions = listOf(app.centsible.core.model.RuleClause("category", "set", app.centsible.core.model.RuleValue.Text(category.raw), "id")),
                ),
            )
        }
    }

    fun delete() {
        val b = budget ?: return
        val id = state.value.original?.id ?: return
        state.update { it.copy(saving = true) }
        viewModelScope.launch {
            runCatching { engine.deleteTransaction(b, id) }
                .recoverCatching { if (it !is BridgeException.QueuedOffline) throw it }
                .onSuccess { state.update { it.copy(saving = false, done = true) } }
                .onFailure { e -> state.update { it.copy(saving = false, error = e.userMessage()) } }
        }
    }

    companion object {
        const val ARG_ID = "id"
        const val ARG_ACCOUNT = "account"
    }
}
