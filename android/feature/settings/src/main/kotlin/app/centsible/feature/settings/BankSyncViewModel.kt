package app.centsible.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.domain.AccountServices
import app.centsible.core.domain.BankSyncGateway
import app.centsible.core.domain.BudgetEngine
import app.centsible.core.domain.SelectedBudget
import app.centsible.core.domain.SessionStore
import app.centsible.core.domain.userMessage
import app.centsible.core.model.Account
import app.centsible.core.model.AccountId
import app.centsible.core.model.AccountSyncResult
import app.centsible.core.model.BankSyncOverview
import app.centsible.core.model.BankSyncSettings
import app.centsible.core.model.ExternalAccount
import app.centsible.core.model.JobStatus
import app.centsible.core.model.Role
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BankSyncUiState(
    val overview: Loadable<BankSyncOverview> = Loadable.Loading,
    val isOwner: Boolean = false,
    val canEdit: Boolean = false,
    val setupToken: String = "",
    val connecting: Boolean = false,
    /** Null until SimpleFIN is connected; listing costs a SimpleFIN request, so it's explicit. */
    val external: Loadable<List<ExternalAccount>>? = null,
    val localAccounts: List<Account> = emptyList(),
    val syncing: Boolean = false,
    val syncResults: List<AccountSyncResult>? = null,
    val linking: ExternalAccount? = null,
    val options: AccountOptions? = null,
    val confirmDisconnect: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
) {
    /** Open, not-yet-linked accounts an external account can be linked into. */
    val linkTargets: List<Account> get() = localAccounts.filter { !it.closed && it.syncSource == null }
}

/** The options sheet for one linked account. */
data class AccountOptions(val external: ExternalAccount, val settings: BankSyncSettings?)

@HiltViewModel
class BankSyncViewModel @Inject constructor(
    private val bankSync: BankSyncGateway,
    private val services: AccountServices,
    private val engine: BudgetEngine,
    private val selectedBudget: SelectedBudget,
    private val sessions: SessionStore,
) : ViewModel() {
    private val state = MutableStateFlow(BankSyncUiState())
    val uiState: StateFlow<BankSyncUiState> = state.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        val role = sessions.current()?.member?.role
        state.update { it.copy(isOwner = role == Role.Owner, canEdit = role?.canWrite == true) }
        runCatching { bankSync.overview() }
            .onSuccess { o ->
                state.update { it.copy(overview = Loadable.Ready(o)) }
                if (o.simplefinConfigured && state.value.external == null) loadAccounts(refresh = true)
            }
            .onFailure { e -> state.update { it.copy(overview = Loadable.Failed(e.userMessage())) } }
    }

    fun onToken(v: String) = state.update { it.copy(setupToken = v, message = null) }

    fun connect() {
        val token = state.value.setupToken.trim()
        if (token.isEmpty() || state.value.connecting) return
        state.update { it.copy(connecting = true) }
        viewModelScope.launch {
            runCatching { bankSync.connect(token) }
                .onSuccess { o ->
                    state.update { it.copy(connecting = false, setupToken = "", overview = Loadable.Ready(o), message = "SimpleFIN connected") }
                    loadAccounts(refresh = false)
                }
                .onFailure { e -> state.update { it.copy(connecting = false, message = e.userMessage()) } }
        }
    }

    fun askDisconnect(ask: Boolean) = state.update { it.copy(confirmDisconnect = ask) }

    fun disconnect() = act("SimpleFIN disconnected") {
        bankSync.disconnect()
        state.update { it.copy(confirmDisconnect = false, external = null) }
        load()
    }

    /** [refresh] false reuses the bridge's recent listing (no SimpleFIN request). */
    fun loadAccounts(refresh: Boolean = true) = viewModelScope.launch {
        state.update { it.copy(external = it.external?.let { e -> if (e is Loadable.Ready) e.copy(refreshing = true) else e } ?: Loadable.Loading) }
        runCatching {
            val budget = selectedBudget()
            val external = bankSync.externalAccounts(budget, refresh)
            external to engine.accounts(budget)
        }
            .onSuccess { (external, local) -> state.update { it.copy(external = Loadable.Ready(external), localAccounts = local) } }
            .onFailure { e -> state.update { it.copy(external = Loadable.Failed(e.userMessage())) } }
        refreshOverview()
    }

    fun startLink(external: ExternalAccount?) = state.update { it.copy(linking = external) }

    /** [existing] null creates a new account. */
    fun link(existing: AccountId?, offBudget: Boolean) {
        val external = state.value.linking ?: return
        act("Linked ${external.name}. Recent transactions are coming in.") {
            bankSync.link(selectedBudget(), external.id, existing, offBudget)
            state.update { it.copy(linking = null) }
            loadAccounts(refresh = false)
        }
    }

    fun openOptions(external: ExternalAccount?) {
        state.update { it.copy(options = external?.let { e -> AccountOptions(e, null) }) }
        val id = external?.linkedAccountId ?: return
        viewModelScope.launch {
            runCatching { bankSync.settings(selectedBudget(), id) }
                .onSuccess { s -> state.update { st -> st.copy(options = st.options?.copy(settings = s)) } }
                .onFailure { e -> state.update { it.copy(options = null, message = e.userMessage()) } }
        }
    }

    fun saveOptions(settings: BankSyncSettings) {
        val id = state.value.options?.external?.linkedAccountId ?: return
        act("Sync options saved") {
            val saved = bankSync.updateSettings(selectedBudget(), id, settings)
            state.update { st -> st.copy(options = st.options?.copy(settings = saved)) }
        }
    }

    fun unlink() {
        val external = state.value.options?.external ?: return
        val id = external.linkedAccountId ?: return
        act("${external.linkedAccountName ?: external.name} unlinked. Its transactions stay.") {
            bankSync.unlink(selectedBudget(), id)
            state.update { it.copy(options = null) }
            loadAccounts(refresh = false)
        }
    }

    fun setSchedule(hours: Int) = act(if (hours == 0) "Background sync off" else "Syncing every $hours hours") {
        val schedule = bankSync.setSchedule(hours)
        state.update { st -> st.copy(overview = (st.overview as? Loadable.Ready)?.let { Loadable.Ready(it.value.copy(schedule = schedule)) } ?: st.overview) }
    }

    /** Every linked account; SimpleFIN accounts go in one request. Shows each account's outcome. */
    fun syncAll() {
        if (state.value.syncing) return
        state.update { it.copy(syncing = true, syncResults = null) }
        viewModelScope.launch {
            runCatching {
                var job = services.startBankSync(selectedBudget(), null)
                val deadline = System.currentTimeMillis() + 5 * 60_000
                while (job.status == JobStatus.Running && System.currentTimeMillis() < deadline) {
                    delay(1_500)
                    job = services.job(job.id)
                }
                job
            }
                .onSuccess { job ->
                    val message = when (job.status) {
                        JobStatus.Succeeded -> when (val n = job.newTransactions ?: 0) { 0 -> "Up to date"; 1 -> "1 new transaction"; else -> "$n new transactions" }
                        JobStatus.Running -> "Still syncing; check back in a minute"
                        else -> job.error ?: "Sync failed"
                    }
                    state.update { it.copy(syncing = false, syncResults = job.results.takeIf { r -> r.isNotEmpty() }, message = message) }
                }
                .onFailure { e -> state.update { it.copy(syncing = false, message = e.userMessage()) } }
            refreshOverview()
            loadAccounts(refresh = false)
        }
    }

    fun messageShown() = state.update { it.copy(message = null) }

    private suspend fun refreshOverview() {
        runCatching { bankSync.overview() }.onSuccess { o -> state.update { it.copy(overview = Loadable.Ready(o)) } }
    }

    private fun act(success: String, block: suspend () -> Unit) = viewModelScope.launch {
        state.update { it.copy(busy = true) }
        runCatching { block() }
            .onSuccess { state.update { it.copy(busy = false, message = success) } }
            .onFailure { e -> state.update { it.copy(busy = false, message = e.userMessage()) } }
    }
}
