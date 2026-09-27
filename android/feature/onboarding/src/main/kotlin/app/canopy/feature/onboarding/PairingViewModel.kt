package app.canopy.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.canopy.core.domain.BudgetEngine
import app.canopy.core.domain.PairDevice
import app.canopy.core.domain.SessionStore
import app.canopy.core.domain.userMessage
import app.canopy.core.model.Budget
import app.canopy.core.model.BudgetId
import app.canopy.core.model.PairingLink
import app.canopy.core.domain.PairingLinks
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PairingUiState(
    val bridgeUrl: String = "",
    val code: String = "",
    val deviceName: String = android.os.Build.MODEL ?: "Android",
    val cfId: String? = null,
    val cfSecret: String? = null,
    val pairing: Boolean = false,
    val error: String? = null,
) {
    val canSubmit get() = PairingLinks.isAllowedBridgeUrl(bridgeUrl.trim()) && code.trim().length >= 6 && !pairing
}

@HiltViewModel
class PairingViewModel @Inject constructor(private val pairDevice: PairDevice) : ViewModel() {
    private val state = MutableStateFlow(PairingUiState())
    val uiState: StateFlow<PairingUiState> = state.asStateFlow()

    fun onUrl(v: String) = state.update { it.copy(bridgeUrl = v, error = null) }
    fun onCode(v: String) = state.update { it.copy(code = v.uppercase(), error = null) }
    fun onDeviceName(v: String) = state.update { it.copy(deviceName = v) }

    /** From a scanned QR code or an actualbridge:// deep link. Pairs right away. */
    fun onScanned(raw: String) {
        val link = PairingLinks.parse(raw)
        if (link == null) {
            state.update { it.copy(error = "That QR code isn't a pairing code from your bridge.") }
            return
        }
        state.update { it.copy(bridgeUrl = link.bridgeUrl, code = link.code, cfId = link.cfAccessClientId, cfSecret = link.cfAccessClientSecret) }
        pair()
    }

    fun pair() {
        val s = state.value
        if (!s.canSubmit) return
        state.update { it.copy(pairing = true, error = null) }
        viewModelScope.launch {
            runCatching { pairDevice(PairingLink(s.bridgeUrl.trim().trimEnd('/'), s.code.trim(), s.cfId, s.cfSecret), s.deviceName.ifBlank { "Android" }) }
                .onFailure { e -> state.update { it.copy(pairing = false, error = e.userMessage()) } }
            // On success the session appears and the app navigates away.
        }
    }
}

@HiltViewModel
class BudgetPickerViewModel @Inject constructor(
    private val engine: BudgetEngine,
    private val sessions: SessionStore,
) : ViewModel() {
    private val state = MutableStateFlow<Result<List<Budget>>?>(null)
    val uiState: StateFlow<Result<List<Budget>>?> = state.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch { state.value = runCatching { engine.budgets() } }
    fun select(id: BudgetId) = viewModelScope.launch { sessions.selectBudget(id) }
    fun signOut() = viewModelScope.launch { sessions.clear() }
}
