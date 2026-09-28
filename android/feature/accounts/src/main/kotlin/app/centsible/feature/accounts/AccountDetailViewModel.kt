package app.centsible.feature.accounts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.domain.BudgetChanges
import app.centsible.core.domain.AccountServices
import app.centsible.core.model.ImportOptions
import app.centsible.core.model.ImportPreview
import app.centsible.core.model.JobStatus
import app.centsible.core.model.Money
import app.centsible.core.model.ReconcileResult
import app.centsible.core.model.ReconcileStatus
import kotlinx.coroutines.delay
import app.centsible.core.domain.BudgetEngine
import app.centsible.core.domain.SelectedBudget
import app.centsible.core.domain.SessionStore
import app.centsible.core.domain.TransactionQuery
import app.centsible.core.domain.userMessage
import app.centsible.core.model.Account
import app.centsible.core.model.AccountId
import app.centsible.core.model.Feature
import app.centsible.core.model.Transaction
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
    val loadingMore: Boolean = false,
)

/** A statement file picked for import, previewed before anything is written. */
data class PendingImport(
    val fileName: String,
    val bytes: ByteArray,
    val options: ImportOptions = ImportOptions(),
    val preview: ImportPreview? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

data class ReconcileState(val status: ReconcileStatus? = null, val result: ReconcileResult? = null, val working: Boolean = false)

data class AccountDetailUiState(
    val data: Loadable<AccountDetail> = Loadable.Loading,
    val canWrite: Boolean = false,
    val canSync: Boolean = false,
    val canImport: Boolean = false,
    val canReconcile: Boolean = false,
    val syncing: Boolean = false,
    val importing: PendingImport? = null,
    val reconcile: ReconcileState? = null,
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
    private val services: AccountServices,
    changes: BudgetChanges,
) : ViewModel() {
    private val id = AccountId(checkNotNull(savedState.get<String>(ARG_ID)))
    private val state = MutableStateFlow(AccountDetailUiState())
    val uiState: StateFlow<AccountDetailUiState> = state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch { changes.changes.collect { refresh() } }
    }

    /** Next page of this account's transactions (infinite scroll). */
    fun loadMore() {
        val current = state.value.data.valueOrNull ?: return
        val cursor = current.nextCursor ?: return
        if (current.loadingMore) return
        state.update { it.copy(data = Loadable.Ready(current.copy(loadingMore = true))) }
        viewModelScope.launch {
            runCatching { engine.transactions(selectedBudget(), TransactionQuery(accountId = id, limit = 50), cursor) }
                .onSuccess { p -> state.update { it.copy(data = Loadable.Ready(current.copy(transactions = current.transactions + p.items, nextCursor = p.nextCursor))) } }
                .onFailure { state.update { it.copy(data = Loadable.Ready(current.copy(loadingMore = false))) } }
        }
    }

    fun refresh() = viewModelScope.launch {
        runCatching {
            val budget = selectedBudget()
            val accounts = async { engine.accounts(budget) }
            val txs = async { engine.transactions(budget, TransactionQuery(accountId = id, limit = 50)) }
            val cats = async { engine.categoryGroups(budget).flatMap { it.categories }.associate { it.id.raw to it.name } }
            val role = sessions.current()?.member?.role
            val caps = runCatching { engine.capabilities() }.getOrNull()
            val member = role?.canWrite == true
            val canWrite = member && caps?.has(Feature.AccountsWrite) == true
            state.update {
                it.copy(
                    canSync = member && caps?.has(Feature.BankSync) == true,
                    canImport = member && caps?.has(Feature.ImportFiles) == true,
                    canReconcile = member && caps?.has(Feature.Reconcile) == true,
                )
            }
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

    /** Starts a bank sync job and polls it (the bridge runs it in the background). */
    fun syncNow() {
        if (state.value.syncing) return
        state.update { it.copy(syncing = true) }
        viewModelScope.launch {
            val message = runCatching {
                var job = services.startBankSync(selectedBudget(), id)
                val deadline = System.currentTimeMillis() + 5 * 60_000
                while (job.status == JobStatus.Running && System.currentTimeMillis() < deadline) {
                    delay(1_500)
                    job = services.job(job.id)
                }
                when (job.status) {
                    JobStatus.Succeeded -> when (val n = job.newTransactions ?: 0) {
                        0 -> "Up to date"
                        1 -> "1 new transaction"
                        else -> "$n new transactions"
                    }
                    JobStatus.Running -> "Still syncing; check back in a minute"
                    else -> job.error ?: "Sync failed"
                }
            }.getOrElse { it.userMessage() }
            state.update { it.copy(syncing = false, message = message) }
            refresh()
        }
    }

    fun pickedFile(fileName: String, bytes: ByteArray) {
        state.update { it.copy(importing = PendingImport(fileName, bytes)) }
        preview()
    }

    fun importOptions(options: ImportOptions) {
        state.update { it.copy(importing = it.importing?.copy(options = options)) }
        preview()
    }

    private fun preview() = viewModelScope.launch {
        val imp = state.value.importing ?: return@launch
        state.update { it.copy(importing = imp.copy(loading = true, error = null)) }
        runCatching { services.previewImport(selectedBudget(), id, imp.fileName, imp.bytes, imp.options) }
            .onSuccess { p -> state.update { it.copy(importing = it.importing?.copy(preview = p, loading = false)) } }
            .onFailure { e -> state.update { it.copy(importing = it.importing?.copy(loading = false, error = e.userMessage())) } }
    }

    fun confirmImport() = viewModelScope.launch {
        val imp = state.value.importing ?: return@launch
        state.update { it.copy(importing = imp.copy(loading = true)) }
        runCatching { services.importFile(selectedBudget(), id, imp.fileName, imp.bytes, imp.options) }
            .onSuccess { r ->
                val msg = buildString {
                    append(if (r.added == 1) "Imported 1 transaction" else "Imported ${r.added} transactions")
                    if (r.updated > 0) append(", matched ${r.updated} already there")
                }
                state.update { it.copy(importing = null, message = msg) }
            }
            .onFailure { e -> state.update { it.copy(importing = it.importing?.copy(loading = false, error = e.userMessage())) } }
    }

    fun cancelImport() = state.update { it.copy(importing = null) }

    fun startReconcile() = viewModelScope.launch {
        state.update { it.copy(reconcile = ReconcileState(working = true)) }
        runCatching { services.reconcileStatus(selectedBudget(), id) }
            .onSuccess { s -> state.update { it.copy(reconcile = ReconcileState(status = s)) } }
            .onFailure { e -> state.update { it.copy(reconcile = null, message = e.userMessage()) } }
    }

    /** First call without an adjustment; if it's off, the UI offers to add one. */
    fun reconcile(statementBalance: Money, adjust: Boolean) = viewModelScope.launch {
        state.update { it.copy(reconcile = it.reconcile?.copy(working = true)) }
        runCatching { services.reconcile(selectedBudget(), id, statementBalance, adjust) }
            .onSuccess { r ->
                if (r.reconciled) {
                    state.update { it.copy(reconcile = null, message = "Reconciled · ${r.lockedCount} transactions locked") }
                } else {
                    state.update { it.copy(reconcile = it.reconcile?.copy(result = r, working = false)) }
                }
            }
            .onFailure { e -> state.update { it.copy(reconcile = null, message = e.userMessage()) } }
    }

    fun cancelReconcile() = state.update { it.copy(reconcile = null) }

    private fun act(success: String, block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }
            .onSuccess { state.update { it.copy(message = success) } }
            .onFailure { e -> state.update { it.copy(message = e.userMessage()) } }
    }

    companion object {
        const val ARG_ID = "accountId"
    }
}
