package app.centsible.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.domain.BudgetEngine
import app.centsible.core.domain.SelectedBudget
import app.centsible.core.domain.SessionStore
import app.centsible.core.domain.TellerGateway
import app.centsible.core.domain.userMessage
import app.centsible.core.model.Account
import app.centsible.core.model.AccountId
import app.centsible.core.model.Role
import app.centsible.core.model.TellerAccount
import app.centsible.core.model.TellerEnrollmentResult
import app.centsible.core.model.TellerPreviewTransaction
import app.centsible.core.model.TellerStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The owner's setup form: application id, environment, and the certificate pair from the Teller dashboard. */
data class TellerSetupForm(
    val applicationId: String = "",
    val environment: String = "development",
    val certificate: String = "",
    val privateKey: String = "",
)

/** Teller Connect, open in a WebView. [enrollmentId] set means repairing that bank login. */
data class TellerConnectRequest(val url: String, val headers: Map<String, String>, val enrollmentId: String?)

/** Linking one Teller account: where it goes, and a preview to check the sign. */
data class TellerLinking(
    val account: TellerAccount,
    val target: AccountId? = null,
    val flip: Boolean = false,
    val preview: Loadable<List<TellerPreviewTransaction>> = Loadable.Loading,
)

/** The guided setup: make an account, copy the app id, pick the certificate, connect a bank. */
enum class TellerStep { Account, ApplicationId, Certificate, ConnectBank }

data class TellerUiState(
    val status: Loadable<TellerStatus> = Loadable.Loading,
    /** Null once set up (or skipped): the regular Teller screen shows instead. */
    val wizard: TellerStep? = null,
    /** What the certificate picker found, in words ("Certificate ✓ · Private key ✓"), or why not. */
    val certificateNote: String? = null,
    val isOwner: Boolean = false,
    val canEdit: Boolean = false,
    val editingSetup: Boolean = false,
    val form: TellerSetupForm = TellerSetupForm(),
    val accounts: Loadable<List<TellerAccount>>? = null,
    val localAccounts: List<Account> = emptyList(),
    val connect: TellerConnectRequest? = null,
    val linking: TellerLinking? = null,
    /** The linked account whose options sheet is open. */
    val options: TellerAccount? = null,
    val confirmRemove: String? = null,
    val confirmReset: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
) {
    val ready: TellerStatus? get() = (status as? Loadable.Ready)?.value

    /** Open accounts a Teller account can feed: not already fed by Teller (SimpleFIN ones move over). */
    val linkTargets: List<Account> get() = localAccounts.filter { !it.closed && it.syncSource != "teller" }
}

@HiltViewModel
class TellerViewModel @Inject constructor(
    private val teller: TellerGateway,
    private val engine: BudgetEngine,
    private val selectedBudget: SelectedBudget,
    private val sessions: SessionStore,
) : ViewModel() {
    private val state = MutableStateFlow(TellerUiState())
    val uiState: StateFlow<TellerUiState> = state.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        val role = sessions.current()?.member?.role
        state.update { it.copy(isOwner = role == Role.Owner, canEdit = role?.canWrite == true) }
        runCatching { teller.status() }
            .onSuccess { s ->
                state.update {
                    it.copy(
                        status = Loadable.Ready(s),
                        // Not set up: the owner gets the guide. Set up with no bank yet: straight to connecting one.
                        // Only on first opening, so a skipped guide stays skipped.
                        wizard = if (it.status !is Loadable.Loading) it.wizard else when {
                            !s.configured && it.isOwner -> TellerStep.Account
                            s.configured && s.enrollments.isEmpty() && it.canEdit -> TellerStep.ConnectBank
                            else -> null
                        },
                        editingSetup = false,
                        form = it.form.copy(applicationId = s.applicationId ?: it.form.applicationId, environment = s.environment ?: it.form.environment),
                    )
                }
                if (s.enrollments.isNotEmpty()) loadAccounts(refresh = true)
            }
            .onFailure { e -> state.update { it.copy(status = Loadable.Failed(e.userMessage())) } }
    }

    fun loadAccounts(refresh: Boolean = true) = viewModelScope.launch {
        state.update { it.copy(accounts = it.accounts?.let { a -> if (a is Loadable.Ready) a.copy(refreshing = true) else a } ?: Loadable.Loading) }
        runCatching {
            val budget = selectedBudget()
            teller.accounts(budget, refresh) to engine.accounts(budget)
        }
            .onSuccess { (accounts, local) -> state.update { it.copy(accounts = Loadable.Ready(accounts), localAccounts = local) } }
            .onFailure { e -> state.update { it.copy(accounts = Loadable.Failed(e.userMessage())) } }
        refreshStatus()
    }

    // ── The guided setup ──

    fun wizardTo(step: TellerStep?) = state.update { it.copy(wizard = step, message = null) }

    /** Files from the picker: Teller's .zip, or the two .pem files. */
    fun certificateFiles(files: List<Pair<String, ByteArray>>) {
        val found = app.centsible.core.domain.TellerCertificates.extract(files)
        state.update {
            it.copy(
                form = it.form.copy(certificate = found.certificate ?: it.form.certificate, privateKey = found.privateKey ?: it.form.privateKey),
                certificateNote = found.problem ?: "Certificate ✓ · Private key ✓",
            )
        }
    }

    /** Saves the wizard's answers; on success the next step is connecting a bank. */
    fun saveFromWizard() {
        val f = state.value.form
        act("Teller is set up") {
            val s = teller.configure(f.applicationId, f.environment, f.certificate.ifBlank { null }, f.privateKey.ifBlank { null })
            state.update { it.copy(status = Loadable.Ready(s), wizard = TellerStep.ConnectBank, certificateNote = null, form = it.form.copy(certificate = "", privateKey = "")) }
        }
    }

    // ── Setup (owners) ──

    fun editSetup(open: Boolean) = state.update { it.copy(editingSetup = open) }
    fun onForm(form: TellerSetupForm) = state.update { it.copy(form = form, message = null) }

    fun saveSetup() {
        val f = state.value.form
        act("Teller is set up. Connect a bank next.") {
            val s = teller.configure(f.applicationId, f.environment, f.certificate.ifBlank { null }, f.privateKey.ifBlank { null })
            // The key stays on the bridge only; drop it from memory here.
            state.update { it.copy(status = Loadable.Ready(s), editingSetup = false, form = it.form.copy(certificate = "", privateKey = "")) }
        }
    }

    fun askReset(ask: Boolean) = state.update { it.copy(confirmReset = ask) }
    fun reset() = act("Teller removed. Imported transactions stay.") {
        teller.reset()
        state.update { it.copy(confirmReset = false, accounts = null) }
        load()
    }

    // ── Bank logins ──

    /** Opens Teller Connect from the bridge, with the Cloudflare Access headers the phone already uses. */
    fun openConnect(enrollmentId: String? = null) = viewModelScope.launch {
        val session = sessions.current() ?: return@launch
        val url = session.bridgeUrl.trimEnd('/') + "/v1/bank-sync/teller/connect" + (enrollmentId?.let { "?enrollmentId=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")
        val headers = buildMap {
            val id = session.cfAccessClientId
            val secret = session.cfAccessClientSecret
            if (id != null && secret != null) { put("CF-Access-Client-Id", id); put("CF-Access-Client-Secret", secret) }
        }
        state.update { it.copy(connect = TellerConnectRequest(url, headers, enrollmentId)) }
    }

    fun closeConnect(message: String? = null) = state.update { it.copy(connect = null, message = message ?: it.message) }

    /** Teller Connect succeeded: hand the bank login to the bridge, which checks it with Teller. */
    fun onConnected(result: TellerEnrollmentResult) {
        val repairing = state.value.connect?.enrollmentId != null
        state.update { it.copy(connect = null, wizard = null) }
        act(if (repairing) "${result.institution.ifBlank { "The bank" }} is reconnected" else "${result.institution.ifBlank { "Bank" }} connected. Link its accounts below.") {
            teller.addEnrollment(result)
            refreshStatus()
            loadAccounts(refresh = true).join()
        }
    }

    fun askRemove(id: String?) = state.update { it.copy(confirmRemove = id) }
    fun remove() {
        val id = state.value.confirmRemove ?: return
        act("Bank disconnected. Its transactions stay.") {
            teller.removeEnrollment(id)
            state.update { it.copy(confirmRemove = null) }
            refreshStatus()
            loadAccounts(refresh = false).join()
        }
    }

    // ── Linking ──

    fun startLink(account: TellerAccount?) {
        if (account == null) return state.update { it.copy(linking = null) }
        // A credit card is the likeliest to arrive with purchases positive; the preview decides.
        state.update { it.copy(linking = TellerLinking(account, flip = false)) }
        preview()
    }

    fun linkTarget(id: AccountId) = state.update { it.copy(linking = it.linking?.copy(target = id)) }

    fun toggleFlip(flip: Boolean) {
        state.update { it.copy(linking = it.linking?.copy(flip = flip, preview = Loadable.Loading)) }
        preview()
    }

    private fun preview() = viewModelScope.launch {
        val l = state.value.linking ?: return@launch
        runCatching { teller.preview(selectedBudget(), l.account.id, l.flip) }
            .onSuccess { p -> state.update { st -> st.copy(linking = st.linking?.takeIf { it.account.id == l.account.id && it.flip == l.flip }?.copy(preview = Loadable.Ready(p)) ?: st.linking) } }
            .onFailure { e -> state.update { st -> st.copy(linking = st.linking?.copy(preview = Loadable.Failed(e.userMessage()))) } }
    }

    fun link() {
        val l = state.value.linking ?: return
        val target = l.target ?: return
        act("Linked ${l.account.label}.") {
            val r = teller.link(selectedBudget(), l.account.id, target, l.flip)
            state.update { it.copy(linking = null, message = r.error ?: "Linked ${l.account.label}: " + when (r.newTransactions) { 0 -> "up to date"; 1 -> "1 new transaction"; else -> "${r.newTransactions} new transactions" }) }
            loadAccounts(refresh = false).join()
        }
    }

    fun openOptions(account: TellerAccount?) = state.update { it.copy(options = account) }

    fun setFlip(flip: Boolean) {
        val a = state.value.options ?: return
        val id = a.linkedAccountId ?: return
        act(if (flip) "Signs reversed for new imports" else "Signs kept as the bank sends them") {
            teller.setFlip(selectedBudget(), id, flip)
            state.update { it.copy(options = a.copy(flip = flip)) }
            loadAccounts(refresh = false).join()
        }
    }

    fun unlink() {
        val a = state.value.options ?: return
        val id = a.linkedAccountId ?: return
        act("${a.linkedAccountName ?: a.name} unlinked. Its transactions stay.") {
            teller.unlink(selectedBudget(), id)
            state.update { it.copy(options = null) }
            loadAccounts(refresh = false).join()
        }
    }

    fun messageShown() = state.update { it.copy(message = null) }

    private suspend fun refreshStatus() {
        runCatching { teller.status() }.onSuccess { s -> state.update { it.copy(status = Loadable.Ready(s)) } }
    }

    private fun act(success: String, block: suspend () -> Unit) = viewModelScope.launch {
        state.update { it.copy(busy = true) }
        runCatching { block() }
            .onSuccess { state.update { it.copy(busy = false, message = it.message ?: success) } }
            .onFailure { e -> state.update { it.copy(busy = false, message = e.userMessage()) } }
    }
}
