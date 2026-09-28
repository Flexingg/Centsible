package app.centsible.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.centsible.core.domain.BridgeUrls
import app.centsible.core.domain.BudgetEngine
import app.centsible.core.domain.ClaimBridge
import app.centsible.core.domain.PairDevice
import app.centsible.core.domain.PairingLinks
import app.centsible.core.domain.SessionStore
import app.centsible.core.domain.SetupGateway
import app.centsible.core.domain.userMessage
import app.centsible.core.model.ActualSetup
import app.centsible.core.model.BridgeAddress
import app.centsible.core.model.Budget
import app.centsible.core.model.BudgetId
import app.centsible.core.model.PairingLink
import app.centsible.core.model.Role
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Address: where's your bridge? The bridge then says which path applies:
 * Setup (no owner yet: setup code, your name, Actual's password) or Join (invite code).
 */
enum class OnboardingStep { Address, Setup, Join }

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.Address,
    val url: String = "",
    val showAccess: Boolean = false,
    val cfId: String = "",
    val cfSecret: String = "",
    val actual: ActualSetup = ActualSetup.Ready,
    val setupCode: String = "",
    val displayName: String = "",
    val actualPassword: String = "",
    val actualPasswordAgain: String = "",
    val inviteCode: String = "",
    val deviceName: String = android.os.Build.MODEL ?: "Android",
    val busy: Boolean = false,
    val error: String? = null,
) {
    val address: BridgeAddress?
        get() = BridgeUrls.normalize(url)?.let { BridgeAddress(it, cfId.trim().ifEmpty { null }, cfSecret.trim().ifEmpty { null }) }

    val canContinue get() = address != null && !busy

    /** Why the setup button is off, shown under the password fields. */
    val passwordProblem: String?
        get() = when (actual) {
            ActualSetup.NeedsPassword -> when {
                actualPassword.isEmpty() -> null
                actualPassword.length < 8 -> "At least 8 characters"
                actualPasswordAgain.isNotEmpty() && actualPasswordAgain != actualPassword -> "The passwords don't match"
                else -> null
            }
            else -> null
        }

    val canClaim: Boolean
        get() {
            val passwordOk = when (actual) {
                ActualSetup.NeedsPassword -> actualPassword.length >= 8 && actualPassword == actualPasswordAgain
                ActualSetup.NeedsLogin -> actualPassword.isNotEmpty()
                ActualSetup.Ready, ActualSetup.Unknown -> true
                ActualSetup.Unreachable, ActualSetup.Unsupported -> false
            }
            return setupCode.count { it.isLetterOrDigit() } >= 4 && displayName.isNotBlank() && deviceName.isNotBlank() && passwordOk && !busy
        }

    val canJoin get() = inviteCode.count { it.isLetterOrDigit() } >= 6 && deviceName.isNotBlank() && !busy
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val setup: SetupGateway,
    private val claimBridge: ClaimBridge,
    private val pairDevice: PairDevice,
) : ViewModel() {
    private val state = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = state.asStateFlow()

    fun edit(f: (OnboardingUiState) -> OnboardingUiState) = state.update { f(it).copy(error = null) }
    fun back() = state.update { it.copy(step = OnboardingStep.Address, error = null, busy = false) }

    /** Asks the bridge what it needs, then shows setup or join. */
    fun next() {
        val address = state.value.address
        if (address == null) {
            state.update { it.copy(error = "Enter your bridge's address, like budget-api.example.com. Plain http:// only works on a home network.") }
            return
        }
        state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            runCatching { setup.status(address) }
                .onSuccess { s ->
                    state.update {
                        it.copy(busy = false, actual = s.actual, step = if (s.needsOwner) OnboardingStep.Setup else OnboardingStep.Join)
                    }
                }
                .onFailure { e ->
                    val blocked = e is app.centsible.core.domain.BridgeException.AccessBlocked
                    state.update {
                        it.copy(
                            busy = false,
                            showAccess = it.showAccess || blocked,
                            error = if (e is app.centsible.core.domain.BridgeException.Network) "Can't reach ${address.url}. Check the address and that the bridge is running." else e.userMessage(),
                        )
                    }
                }
        }
    }

    fun claim() {
        val s = state.value
        val address = s.address ?: return
        if (!s.canClaim) return
        state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val password = s.actualPassword.takeIf { s.actual == ActualSetup.NeedsPassword || s.actual == ActualSetup.NeedsLogin }
            runCatching { claimBridge(address, s.setupCode, s.displayName, s.deviceName, password) }
                .onFailure { e -> state.update { it.copy(busy = false, error = e.userMessage()) } }
            // On success the session appears and the app moves on to budgets.
        }
    }

    fun join() {
        val s = state.value
        val address = s.address ?: return
        if (!s.canJoin) return
        state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            runCatching { pairDevice(PairingLink(address.url, s.inviteCode.trim(), address.cfAccessClientId, address.cfAccessClientSecret), s.deviceName) }
                .onFailure { e -> state.update { it.copy(busy = false, error = e.userMessage()) } }
        }
    }

    /** A scanned invite QR code or actualbridge:// link carries everything: join right away. */
    fun onScanned(raw: String) {
        val link = PairingLinks.parse(raw)
        if (link == null) {
            state.update { it.copy(error = "That QR code isn't an invite from a Centsible bridge.") }
            return
        }
        state.update {
            it.copy(
                step = OnboardingStep.Join, url = link.bridgeUrl, inviteCode = link.code,
                cfId = link.cfAccessClientId.orEmpty(), cfSecret = link.cfAccessClientSecret.orEmpty(), error = null,
            )
        }
        join()
    }
}

@HiltViewModel
class BudgetPickerViewModel @Inject constructor(
    private val engine: BudgetEngine,
    private val sessions: SessionStore,
) : ViewModel() {
    private val state = MutableStateFlow<Result<List<Budget>>?>(null)
    val uiState: StateFlow<Result<List<Budget>>?> = state.asStateFlow()
    private val owner = MutableStateFlow(false)
    val isOwner: StateFlow<Boolean> = owner.asStateFlow()
    private val creating = MutableStateFlow(false)
    val isCreating: StateFlow<Boolean> = creating.asStateFlow()
    private val createError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = createError.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        owner.value = sessions.current()?.member?.role == Role.Owner
        state.value = runCatching { engine.budgets() }
    }

    fun select(id: BudgetId) = viewModelScope.launch { sessions.selectBudget(id) }

    fun create(name: String) = viewModelScope.launch {
        creating.value = true
        createError.value = null
        runCatching { engine.createBudget(name.trim()) }
            .onSuccess { sessions.selectBudget(it.id) }
            .onFailure { createError.value = it.userMessage() }
        creating.value = false
    }

    fun signOut() = viewModelScope.launch { sessions.clear() }
}
