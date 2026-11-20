package app.centsible.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.domain.BudgetEngine
import app.centsible.core.domain.PlaidGateway
import app.centsible.core.domain.SelectedBudget
import app.centsible.core.domain.SessionStore
import app.centsible.core.domain.userMessage
import app.centsible.core.model.Account
import app.centsible.core.model.AccountId
import app.centsible.core.model.PlaidAccount
import app.centsible.core.model.PlaidLinkResult
import app.centsible.core.model.PlaidStatus
import app.centsible.core.model.Role
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The owner's setup form: the keys from the Plaid dashboard (Developers → Keys). */
data class PlaidSetupForm(
    val clientId: String = "",
    val secret: String = "",
    val environment: String = "production",
)

/** Plaid Link, ready to open. [itemId] set means signing in to that bank login again. */
data class PlaidLaunch(val linkToken: String, val itemId: String?)

/** Linking one Plaid account: where it goes. */
data class PlaidLinking(val account: PlaidAccount, val target: AccountId? = null)

/** The guided setup: make the account, paste the keys, allow the app, connect a bank. */
enum class PlaidStep { Account, Keys, AllowApp, ConnectBank }

data class PlaidUiState(
    val status: Loadable<PlaidStatus> = Loadable.Loading,
    /** Null once set up (or skipped): the regular Plaid screen shows instead. */
    val wizard: PlaidStep? = null,
    val isOwner: Boolean = false,
    val canEdit: Boolean = false,
    val editingSetup: Boolean = false,
    val form: PlaidSetupForm = PlaidSetupForm(),
    val accounts: Loadable<List<PlaidAccount>>? = null,
    val localAccounts: List<Account> = emptyList(),
    /** Set while Plaid Link should open; the screen launches it and clears this. */
    val launch: PlaidLaunch? = null,
    val linking: PlaidLinking? = null,
    /** The linked account whose options sheet is open. */
    val options: PlaidAccount? = null,
    val confirmRemove: String? = null,
    val confirmReset: Boolean = false,
    /** Older history to import, in months back from today (Plaid keeps up to 24). */
    val historyMonths: Int = 24,
    val importingHistory: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
) {
    val ready: PlaidStatus? get() = (status as? Loadable.Ready)?.value

    /** Open accounts a Plaid account can feed: not already fed by Plaid (SimpleFIN ones move over). */
    val linkTargets: List<Account> get() = localAccounts.filter { !it.closed && it.syncSource != "plaid" }

    /** Accounts in this budget that sync through Plaid (history import needs at least one). */
    val linkedCount: Int get() = (accounts as? Loadable.Ready)?.value?.count { it.linkedAccountId != null } ?: 0

    companion object {
        val HISTORY_CHOICES = listOf(3, 6, 12, 24)
    }
}

@HiltViewModel
class PlaidViewModel @Inject constructor(
    private val plaid: PlaidGateway,
    private val engine: BudgetEngine,
    private val selectedBudget: SelectedBudget,
    private val sessions: SessionStore,
) : ViewModel() {
    private val state = MutableStateFlow(PlaidUiState())
    val uiState: StateFlow<PlaidUiState> = state.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        val role = sessions.current()?.member?.role
        state.update { it.copy(isOwner = role == Role.Owner, canEdit = role?.canWrite == true) }
        runCatching { plaid.status() }
            .onSuccess { s ->
                state.update {
                    it.copy(
                        status = Loadable.Ready(s),
                        // Not set up: the owner gets the guide. Set up with no bank yet: straight to connecting one.
                        // Only on first opening, so a skipped guide stays skipped.
                        wizard = if (it.status !is Loadable.Loading) it.wizard else when {
                            !s.configured && it.isOwner -> PlaidStep.Account
                            s.configured && s.items.isEmpty() && it.canEdit -> PlaidStep.ConnectBank
                            else -> null
                        },
                        editingSetup = false,
                        form = it.form.copy(clientId = s.clientId ?: it.form.clientId, environment = s.environment ?: it.form.environment),
                    )
                }
                if (s.items.isNotEmpty()) loadAccounts(refresh = true)
            }
            .onFailure { e -> state.update { it.copy(status = Loadable.Failed(e.userMessage())) } }
    }

    fun loadAccounts(refresh: Boolean = true) = viewModelScope.launch {
        state.update { it.copy(accounts = it.accounts?.let { a -> if (a is Loadable.Ready) a.copy(refreshing = true) else a } ?: Loadable.Loading) }
        runCatching {
            val budget = selectedBudget()
            plaid.accounts(budget, refresh) to engine.accounts(budget)
        }
            .onSuccess { (accounts, local) -> state.update { it.copy(accounts = Loadable.Ready(accounts), localAccounts = local) } }
            .onFailure { e -> state.update { it.copy(accounts = Loadable.Failed(e.userMessage())) } }
        refreshStatus()
    }

    // ── Setup (owners) ──

    fun wizardTo(step: PlaidStep?) = state.update { it.copy(wizard = step, message = null) }
    fun editSetup(open: Boolean) = state.update { it.copy(editingSetup = open) }
    fun onForm(form: PlaidSetupForm) = state.update { it.copy(form = form, message = null) }

    /** Saves the keys (Plaid checks them); from the guide, the next step is allowing the app. */
    fun saveSetup(fromWizard: Boolean = false) {
        val f = state.value.form
        act(if (fromWizard) "Plaid accepted your keys" else "Plaid is set up. Connect a bank next.") {
            val s = plaid.configure(f.clientId, f.environment, f.secret.ifBlank { null })
            // The secret stays on the bridge only; drop it from memory here.
            state.update {
                it.copy(status = Loadable.Ready(s), editingSetup = false, form = it.form.copy(secret = ""), wizard = if (fromWizard) PlaidStep.AllowApp else it.wizard)
            }
        }
    }

    fun askReset(ask: Boolean) = state.update { it.copy(confirmReset = ask) }
    fun reset() = act("Plaid removed. Imported transactions stay.") {
        plaid.reset()
        state.update { it.copy(confirmReset = false, accounts = null) }
        load()
    }

    // ── Bank logins ──

    /** Asks the bridge for a Link token, then the screen opens Plaid Link with it. */
    fun connect(itemId: String? = null) = act(null) {
        val token = plaid.linkToken(itemId)
        state.update { it.copy(launch = PlaidLaunch(token, itemId)) }
    }

    fun launched() = state.update { it.copy(launch = null) }

    /** Plaid Link finished. A public token (or a repaired login) goes to the bridge, which checks it with Plaid. */
    fun onLinkResult(result: PlaidLinkResult?, exitMessage: String?) {
        if (result == null) {
            state.update { it.copy(message = exitMessage ?: it.message) }
            return
        }
        state.update { it.copy(wizard = null) }
        val bank = result.institution?.ifBlank { null }
        act(if (result.itemId != null) "${bank ?: "The bank"} is reconnected" else "${bank ?: "Bank"} connected. Link its accounts below.") {
            plaid.addItem(result)
            refreshStatus()
            loadAccounts(refresh = true).join()
        }
    }

    fun askRemove(id: String?) = state.update { it.copy(confirmRemove = id) }
    fun remove() {
        val id = state.value.confirmRemove ?: return
        act("Bank disconnected. Its transactions stay.") {
            plaid.removeItem(id)
            state.update { it.copy(confirmRemove = null) }
            refreshStatus()
            loadAccounts(refresh = false).join()
        }
    }

    // ── Linking ──

    fun startLink(account: PlaidAccount?) = state.update { it.copy(linking = account?.let { a -> PlaidLinking(a) }) }
    fun linkTarget(id: AccountId) = state.update { it.copy(linking = it.linking?.copy(target = id)) }

    fun link() {
        val l = state.value.linking ?: return
        val target = l.target ?: return
        act("Linked ${l.account.label}.") {
            val r = plaid.link(selectedBudget(), l.account.id, target)
            state.update { it.copy(linking = null, message = r.error ?: "Linked ${l.account.label}: " + when (r.newTransactions) { 0 -> "up to date"; 1 -> "1 new transaction"; else -> "${r.newTransactions} new transactions" }) }
            loadAccounts(refresh = false).join()
        }
    }

    fun openOptions(account: PlaidAccount?) = state.update { it.copy(options = account) }

    fun unlink() {
        val a = state.value.options ?: return
        val id = a.linkedAccountId ?: return
        act("${a.linkedAccountName ?: a.name} unlinked. Its transactions stay.") {
            plaid.unlink(selectedBudget(), id)
            state.update { it.copy(options = null) }
            loadAccounts(refresh = false).join()
        }
    }

    // ── Older history ──

    fun historyMonths(months: Int) = state.update { it.copy(historyMonths = months) }

    /**
     * Imports history back [historyMonths] for every Plaid-linked account in this budget. A
     * bank sync job on the bridge; this waits for it (two years can take a few minutes).
     */
    fun importHistory() {
        if (state.value.importingHistory) return
        val since = java.time.LocalDate.now().minusMonths(state.value.historyMonths.toLong()).toString()
        state.update { it.copy(importingHistory = true) }
        viewModelScope.launch {
            runCatching {
                var job = plaid.importHistory(selectedBudget(), since)
                val deadline = System.currentTimeMillis() + 10 * 60_000
                while (job.status == app.centsible.core.model.JobStatus.Running && System.currentTimeMillis() < deadline) {
                    kotlinx.coroutines.delay(2_000)
                    job = plaid.job(job.id)
                }
                job
            }
                .onSuccess { job ->
                    val failed = job.results.filter { it.error != null }
                    val message = when (job.status) {
                        app.centsible.core.model.JobStatus.Succeeded -> when (val n = job.newTransactions ?: 0) {
                            0 -> "No older transactions: everything Plaid has is already here"
                            1 -> "Imported 1 older transaction"
                            else -> "Imported $n older transactions"
                        } + if (failed.isNotEmpty()) ". ${failed.joinToString { it.name }} didn't finish: ${failed.first().error}" else ""
                        app.centsible.core.model.JobStatus.Running -> "Still importing on the bridge; check your accounts in a few minutes"
                        else -> job.error ?: "The history import failed"
                    }
                    state.update { it.copy(importingHistory = false, message = message) }
                }
                .onFailure { e -> state.update { it.copy(importingHistory = false, message = e.userMessage()) } }
            loadAccounts(refresh = false)
        }
    }

    fun messageShown() = state.update { it.copy(message = null) }

    private suspend fun refreshStatus() {
        runCatching { plaid.status() }.onSuccess { s -> state.update { it.copy(status = Loadable.Ready(s)) } }
    }

    private fun act(success: String?, block: suspend () -> Unit) = viewModelScope.launch {
        state.update { it.copy(busy = true) }
        runCatching { block() }
            .onSuccess { state.update { it.copy(busy = false, message = it.message ?: success) } }
            .onFailure { e -> state.update { it.copy(busy = false, message = e.userMessage()) } }
    }
}
