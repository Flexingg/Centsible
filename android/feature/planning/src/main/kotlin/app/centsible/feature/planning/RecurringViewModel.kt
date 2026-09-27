package app.centsible.feature.planning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.domain.BudgetChanges
import app.centsible.core.domain.BudgetEngine
import app.centsible.core.domain.PlanningGateway
import app.centsible.core.domain.SelectedBudget
import app.centsible.core.domain.SessionStore
import app.centsible.core.domain.userMessage
import app.centsible.core.model.Account
import app.centsible.core.model.Feature
import app.centsible.core.model.Schedule
import app.centsible.core.model.ScheduleDraft
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RecurringData(
    val schedules: List<Schedule>,
    val payeeNames: Map<String, String>,
    val accounts: List<Account>,
) {
    fun title(s: Schedule) = Describe.scheduleTitle(s, payeeNames)
    fun accountName(s: Schedule) = s.accountId?.let { id -> accounts.firstOrNull { it.id == id }?.name }

    /** Due within 30 days, soonest first. */
    fun upcoming(today: LocalDate) = active().filter { s -> s.nextDate?.let { LocalDate.parse(it) <= today.plusDays(30) } == true }
    fun later(today: LocalDate) = active().filter { s -> s.nextDate?.let { LocalDate.parse(it) > today.plusDays(30) } != false }
    private fun active() = schedules.filter { !it.completed }.sortedBy { it.nextDate ?: "9999" }
}

data class RecurringUiState(
    val data: Loadable<RecurringData> = Loadable.Loading,
    val canEdit: Boolean = false,
    val canSkip: Boolean = false,
    val canPost: Boolean = false,
    val editing: Schedule? = null,
    val creating: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class RecurringViewModel @Inject constructor(
    private val planning: PlanningGateway,
    private val engine: BudgetEngine,
    private val selectedBudget: SelectedBudget,
    private val sessions: SessionStore,
    changes: BudgetChanges,
) : ViewModel() {
    private val state = MutableStateFlow(RecurringUiState())
    val uiState: StateFlow<RecurringUiState> = state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch { changes.changes.collect { refresh() } }
    }

    fun refresh() = viewModelScope.launch {
        runCatching {
            val b = selectedBudget()
            val schedules = async { planning.schedules(b) }
            val payees = async { engine.payees(b).associate { it.id.raw to it.name } }
            val accounts = async { engine.accounts(b) }
            val caps = async { engine.capabilities() }
            val canWrite = sessions.current()?.member?.role?.canWrite == true
            val c = caps.await()
            state.update {
                it.copy(
                    data = Loadable.Ready(RecurringData(schedules.await(), payees.await(), accounts.await())),
                    canEdit = canWrite && c.has(Feature.SchedulesWrite),
                    canSkip = canWrite && c.has(Feature.SchedulesSkip),
                    canPost = canWrite && c.has(Feature.SchedulesPost),
                )
            }
        }.onFailure { e -> state.update { it.copy(data = Loadable.Failed(e.userMessage())) } }
    }

    fun open(s: Schedule?) = state.update { it.copy(editing = s, creating = s == null) }
    fun close() = state.update { it.copy(editing = null, creating = false) }
    fun messageShown() = state.update { it.copy(message = null) }

    fun save(draft: ScheduleDraft) = act("Saved") {
        val id = state.value.editing?.id
        if (id == null) planning.createSchedule(selectedBudget(), draft) else planning.updateSchedule(selectedBudget(), id, draft)
        close()
    }

    fun skip(s: Schedule) = act("Skipped ${Describe.shortDate(s.nextDate ?: "")}") { planning.skipSchedule(selectedBudget(), s.id); close() }
    fun postNow(s: Schedule) = act("Added to your transactions") { planning.postSchedule(selectedBudget(), s.id); close() }
    fun delete(s: Schedule) = act("Deleted") { planning.deleteSchedule(selectedBudget(), s.id); close() }

    private fun act(success: String, block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }
            .onSuccess { state.update { it.copy(message = success) } }
            .onFailure { e -> state.update { it.copy(message = e.userMessage()) } }
    }
}
