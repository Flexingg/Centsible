package app.centsible.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.domain.BudgetEngine
import app.centsible.core.domain.HouseholdGateway
import app.centsible.core.domain.Me
import app.centsible.core.domain.SessionStore
import app.centsible.core.domain.userMessage
import app.centsible.core.model.Budget
import app.centsible.core.model.BudgetId
import app.centsible.core.model.Capabilities
import app.centsible.core.model.DeviceId
import app.centsible.core.model.Member
import app.centsible.core.model.MemberId
import app.centsible.core.model.PairingInvite
import app.centsible.core.model.Role
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
    val budgets: List<Budget> = emptyList(),
    val selectedBudget: BudgetId? = null,
)

data class SettingsUiState(
    val data: Loadable<SettingsData> = Loadable.Loading,
    val invite: Pair<Member, PairingInvite>? = null,
    val message: String? = null,
    val appLock: Boolean = false,
    val reminders: Boolean = false,
    val reminderDays: Int = 1,
    val alerts: Boolean = false,
    val reviews: Set<String> = emptySet(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val household: HouseholdGateway,
    private val engine: BudgetEngine,
    private val sessions: SessionStore,
    private val appLock: app.centsible.core.domain.AppLockSettings,
    private val reminders: app.centsible.core.domain.ReminderSettings,
    private val reminderScheduler: app.centsible.core.domain.ReminderScheduler,
) : ViewModel() {
    private val state = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = state.asStateFlow()

    init {
        viewModelScope.launch { appLock.enabled.collect { on -> state.update { it.copy(appLock = on) } } }
        viewModelScope.launch { reminders.enabled.collect { on -> state.update { it.copy(reminders = on) } } }
        viewModelScope.launch { reminders.daysAhead.collect { d -> state.update { it.copy(reminderDays = d) } } }
        viewModelScope.launch { reminders.alerts.collect { on -> state.update { it.copy(alerts = on) } } }
        viewModelScope.launch { reminders.reviews.collect { r -> state.update { it.copy(reviews = r) } } }
    }

    fun setReminders(enabled: Boolean) = viewModelScope.launch {
        reminders.setEnabled(enabled)
        reminderScheduler.apply(enabled || state.value.alerts || state.value.reviews.isNotEmpty())
    }

    /** Spending alerts share the daily background check with bill reminders. */
    fun setAlerts(enabled: Boolean) = viewModelScope.launch {
        reminders.setAlerts(enabled)
        reminderScheduler.apply(enabled || state.value.reminders || state.value.reviews.isNotEmpty())
    }

    /** "Tell me when my week/month/quarter/year is wrapped." */
    fun toggleReview(period: String) = viewModelScope.launch {
        val next = state.value.reviews.let { if (period in it) it - period else it + period }
        reminders.setReviews(next)
        reminderScheduler.apply(next.isNotEmpty() || state.value.reminders || state.value.alerts)
    }

    fun setReminderDays(days: Int) = viewModelScope.launch { reminders.setDaysAhead(days) }

    fun notificationsDenied() = state.update { it.copy(message = "Allow notifications for Centsible in Android settings to get reminders and alerts.") }

    fun setAppLock(enabled: Boolean) = viewModelScope.launch { appLock.setEnabled(enabled) }

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        runCatching {
            val me = async { household.me() }
            val caps = async { engine.capabilities() }
            val m = me.await()
            val budgets = async { runCatching { engine.budgets() }.getOrDefault(emptyList()) }
            val members = if (m.member.role == Role.Owner) household.members() else listOf(m.member)
            val session = sessions.current()
            SettingsData(m, members, caps.await(), session?.bridgeUrl.orEmpty(), budgets.await(), session?.selectedBudget)
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

    /** The app rebuilds its screens for the newly selected budget. */
    fun switchBudget(id: BudgetId) = viewModelScope.launch { sessions.selectBudget(id) }

    fun signOut() = viewModelScope.launch {
        runCatching { household.logout() }
        sessions.clear()
    }

    private fun act(block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }.onFailure { e -> state.update { it.copy(message = e.userMessage()) } }
    }
}
