package app.canopy.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.canopy.core.designsystem.component.Loadable
import app.canopy.core.domain.BudgetEngine
import app.canopy.core.domain.HouseholdGateway
import app.canopy.core.domain.Me
import app.canopy.core.domain.SessionStore
import app.canopy.core.domain.userMessage
import app.canopy.core.model.Capabilities
import app.canopy.core.model.DeviceId
import app.canopy.core.model.Member
import app.canopy.core.model.MemberId
import app.canopy.core.model.PairingInvite
import app.canopy.core.model.Role
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsData(
    val me: Me,
    val members: List<Member>,
    val capabilities: Capabilities,
    val bridgeUrl: String,
)

data class SettingsUiState(
    val data: Loadable<SettingsData> = Loadable.Loading,
    val invite: Pair<Member, PairingInvite>? = null,
    val message: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val household: HouseholdGateway,
    private val engine: BudgetEngine,
    private val sessions: SessionStore,
) : ViewModel() {
    private val state = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = state.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        runCatching {
            val me = async { household.me() }
            val caps = async { engine.capabilities() }
            val m = me.await()
            val members = if (m.member.role == Role.Owner) household.members() else listOf(m.member)
            SettingsData(m, members, caps.await(), sessions.current()?.bridgeUrl.orEmpty())
        }
            .onSuccess { d -> state.update { it.copy(data = Loadable.Ready(d)) } }
            .onFailure { e -> state.update { it.copy(data = Loadable.Failed(e.userMessage())) } }
    }

    fun addMember(name: String, role: Role) = act {
        val member = household.addMember(name.trim(), role, emptyList())
        state.update { it.copy(invite = member to household.invite(member.id)) }
        refresh()
    }

    fun invite(member: Member) = act { state.update { it.copy(invite = member to household.invite(member.id)) } }
    fun dismissInvite() = state.update { it.copy(invite = null) }
    fun revoke(device: DeviceId) = act { household.revokeDevice(device); refresh() }
    fun messageShown() = state.update { it.copy(message = null) }

    fun grantCurrentBudget(member: MemberId) = act {
        val budget = sessions.current()?.selectedBudget ?: return@act
        val current = (state.value.data.valueOrNull?.members?.firstOrNull { it.id == member }?.budgetIds).orEmpty()
        household.setBudgets(member, (current + budget).distinct())
        state.update { it.copy(message = "Access granted") }
        refresh()
    }

    fun signOut() = viewModelScope.launch {
        runCatching { household.logout() }
        sessions.clear()
    }

    private fun act(block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }.onFailure { e -> state.update { it.copy(message = e.userMessage()) } }
    }
}
