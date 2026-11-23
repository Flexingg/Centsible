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
    val hapticsEnabled: Boolean = true,
    val soundsEnabled: Boolean = true,
    val installedVersion: String = "",
    val installedCode: Long = 0,
    val checkingUpdate: Boolean = false,
    val latestRelease: app.centsible.core.model.AppRelease? = null,
    val updateChecked: Boolean = false,
    val updateError: String? = null,
    val appDownload: AppDownload = AppDownload.Idle,
    /** Enables App Updates and Haptics & Sounds cards in the live app (kept false in legacy screenshot defaults). */
    val showEnhancements: Boolean = false,
) {
    val updateAvailable: Boolean
        get() = latestRelease?.let { AppUpdater.isUpdateAvailable(it, installedCode, installedVersion) } == true
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val household: HouseholdGateway,
    private val engine: BudgetEngine,
    private val sessions: SessionStore,
    private val appLock: app.centsible.core.domain.AppLockSettings,
    private val reminders: app.centsible.core.domain.ReminderSettings,
    private val reminderScheduler: app.centsible.core.domain.ReminderScheduler,
    private val feedback: app.centsible.core.domain.FeedbackSettings,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : ViewModel() {
    private val state = MutableStateFlow(
        SettingsUiState(
            installedVersion = AppUpdater.installedVersionName(context),
            installedCode = AppUpdater.installedVersionCode(context),
            showEnhancements = true,
        ),
    )
    val uiState: StateFlow<SettingsUiState> = state.asStateFlow()

    init {
        viewModelScope.launch { appLock.enabled.collect { on -> state.update { it.copy(appLock = on) } } }
        viewModelScope.launch { reminders.enabled.collect { on -> state.update { it.copy(reminders = on) } } }
        viewModelScope.launch { reminders.daysAhead.collect { d -> state.update { it.copy(reminderDays = d) } } }
        viewModelScope.launch { reminders.alerts.collect { on -> state.update { it.copy(alerts = on) } } }
        viewModelScope.launch { reminders.reviews.collect { r -> state.update { it.copy(reviews = r) } } }
        viewModelScope.launch { feedback.hapticsEnabled.collect { on -> state.update { it.copy(hapticsEnabled = on) } } }
        viewModelScope.launch { feedback.soundsEnabled.collect { on -> state.update { it.copy(soundsEnabled = on) } } }
        checkForAppUpdate(silent = true)
    }

    fun setHapticsEnabled(enabled: Boolean) = viewModelScope.launch { feedback.setHapticsEnabled(enabled) }
    fun setSoundsEnabled(enabled: Boolean) = viewModelScope.launch { feedback.setSoundsEnabled(enabled) }

    fun checkForAppUpdate(silent: Boolean = false) = viewModelScope.launch {
        if (state.value.checkingUpdate) return@launch
        state.update { it.copy(checkingUpdate = true, updateError = null) }
        val result = runCatching { AppUpdater.fetchLatestGitHubRelease() }
        result.fold(
            onSuccess = { release ->
                val available = release != null && AppUpdater.isUpdateAvailable(release, state.value.installedCode, state.value.installedVersion)
                state.update {
                    it.copy(
                        checkingUpdate = false,
                        latestRelease = release,
                        updateChecked = true,
                        updateError = if (release == null && !silent) "Couldn't read GitHub releases." else null,
                        message = when {
                            silent -> it.message
                            available -> "Update v${release!!.version} is available!"
                            release != null -> "You're already on the latest version (v${it.installedVersion})"
                            else -> "Couldn't check GitHub for updates."
                        },
                    )
                }
            },
            onFailure = { e ->
                state.update {
                    it.copy(
                        checkingUpdate = false,
                        updateChecked = true,
                        updateError = if (silent) null else (e.message ?: "Couldn't reach GitHub."),
                    )
                }
            },
        )
    }

    fun downloadAppUpdate() {
        val release = state.value.latestRelease?.takeIf { !it.apkUrl.isNullOrBlank() } ?: return
        val id = runCatching { AppUpdater.download(context, release.apkUrl!!, release.version) }
            .getOrElse { e ->
                state.update { it.copy(appDownload = AppDownload.Failed(e.message ?: "Couldn't start the download.")) }
                return
            }
        viewModelScope.launch {
            AppUpdater.progress(context, id).collect { d -> state.update { it.copy(appDownload = d) } }
        }
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
