package app.centsible.feature.budget

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.domain.AutomationsGateway
import app.centsible.core.domain.BudgetChanges
import app.centsible.core.domain.BudgetEngine
import app.centsible.core.domain.PlanningGateway
import app.centsible.core.domain.SelectedBudget
import app.centsible.core.domain.SessionStore
import app.centsible.core.domain.userMessage
import app.centsible.core.model.Automation
import app.centsible.core.model.AutomationCategory
import app.centsible.core.model.AutomationSource
import app.centsible.core.model.CategoryId
import app.centsible.core.model.Money
import app.centsible.core.model.Role
import app.centsible.core.model.YearMonth
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private fun monthArg(saved: SavedStateHandle): YearMonth =
    saved.get<String>(ARG_MONTH)?.takeIf { it.isNotBlank() }?.let(::YearMonth) ?: LocalDate.now().let { YearMonth.of(it.year, it.monthValue) }

const val ARG_MONTH = "month"
const val ARG_CATEGORY = "category"

// ── The list ─────────────────────────────────────────────────────────────────

data class AutomationsUiState(
    val month: YearMonth = YearMonth("2000-01"),
    val data: Loadable<List<AutomationCategory>> = Loadable.Loading,
    val canEdit: Boolean = false,
    val running: Boolean = false,
    val confirmOverwrite: Boolean = false,
    val message: String? = null,
    /** Which automations need fixing, after a run that found problems. */
    val problems: String? = null,
)

@HiltViewModel
class AutomationsViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val automations: AutomationsGateway,
    private val selectedBudget: SelectedBudget,
    private val sessions: SessionStore,
    changes: BudgetChanges,
) : ViewModel() {
    private val state = MutableStateFlow(AutomationsUiState(month = monthArg(saved)))
    val uiState: StateFlow<AutomationsUiState> = state.asStateFlow()

    init {
        load()
        viewModelScope.launch { changes.changes.collect { load() } }
    }

    fun load() = viewModelScope.launch {
        val role = sessions.current()?.member?.role
        runCatching { automations.list(selectedBudget(), state.value.month) }
            .onSuccess { list -> state.update { it.copy(data = Loadable.Ready(list), canEdit = role != Role.Viewer) } }
            .onFailure { e -> state.update { it.copy(data = Loadable.Failed(e.userMessage())) } }
    }

    fun month(delta: Int) {
        state.update { it.copy(month = it.month.plus(delta), data = Loadable.Loading) }
        load()
    }

    fun askOverwrite(ask: Boolean) = state.update { it.copy(confirmOverwrite = ask) }

    fun run(overwrite: Boolean) {
        state.update { it.copy(running = true, confirmOverwrite = false, problems = null) }
        viewModelScope.launch {
            runCatching { automations.apply(selectedBudget(), state.value.month, overwrite) }
                .onSuccess { r -> state.update { it.copy(running = false, message = r.message, problems = r.details) } }
                .onFailure { e -> state.update { it.copy(running = false, message = e.userMessage()) } }
        }
    }

    fun messageShown() = state.update { it.copy(message = null) }
    fun problemsShown() = state.update { it.copy(problems = null) }
}

// ── One category's editor ────────────────────────────────────────────────────

data class AutomationEditorData(
    val categoryId: CategoryId,
    val name: String,
    val isIncome: Boolean,
    val source: AutomationSource,
    val notesHaveTemplates: Boolean,
    /** What's saved, to tell whether there are changes. */
    val saved: List<Automation>,
    /** Schedules by name (cover-schedule automations name them). */
    val schedules: List<String>,
    /** Income categories, for "% of" (id to name). */
    val incomeCategories: List<Pair<String, String>>,
)

data class AutomationEditorUiState(
    val month: YearMonth = YearMonth("2000-01"),
    val data: Loadable<AutomationEditorData> = Loadable.Loading,
    /** The list being edited. */
    val draft: List<Automation> = emptyList(),
    val projected: Money? = null,
    val perAutomation: List<Money>? = null,
    /** The projection is out of date while this is true (a preview is running). */
    val previewing: Boolean = false,
    /** Why the preview couldn't be worked out (usually an unfinished automation). */
    val previewError: String? = null,
    val canEdit: Boolean = false,
    val saving: Boolean = false,
    val adding: Boolean = false,
    val confirmNotes: Boolean = false,
    val message: String? = null,
    /** Set once saved: the screen closes. */
    val done: Boolean = false,
) {
    val dirty get() = (data as? Loadable.Ready)?.value?.saved != draft
}

@HiltViewModel
class AutomationEditorViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val automations: AutomationsGateway,
    private val engine: BudgetEngine,
    private val planning: PlanningGateway,
    private val selectedBudget: SelectedBudget,
    private val sessions: SessionStore,
) : ViewModel() {
    private val category = CategoryId(checkNotNull(saved.get<String>(ARG_CATEGORY)))
    private val state = MutableStateFlow(AutomationEditorUiState(month = monthArg(saved)))
    val uiState: StateFlow<AutomationEditorUiState> = state.asStateFlow()
    private var previewJob: Job? = null

    init {
        load()
    }

    fun load() = viewModelScope.launch {
        runCatching {
            val budget = selectedBudget()
            val current = async { automations.get(budget, category, state.value.month) }
            val groups = async { engine.categoryGroups(budget) }
            val schedules = async { runCatching { planning.schedules(budget, upcoming = 0) }.getOrDefault(emptyList()) }
            val g = groups.await()
            val cat = g.flatMap { grp -> grp.categories.map { it to grp } }.first { it.first.id == category }
            val c = current.await()
            AutomationEditorData(
                category, cat.first.name, cat.second.isIncome, c.source, c.notesHaveTemplates, c.automations,
                schedules.await().filter { !it.completed }.mapNotNull { it.name }.distinct().sorted(),
                g.filter { it.isIncome }.flatMap { grp -> grp.categories.map { it.id.raw to it.name } },
            ) to c
        }
            .onSuccess { (d, c) ->
                val role = sessions.current()?.member?.role
                state.update { it.copy(data = Loadable.Ready(d), draft = c.automations, projected = c.projected, perAutomation = c.perAutomation, canEdit = role != Role.Viewer) }
            }
            .onFailure { e -> state.update { it.copy(data = Loadable.Failed(e.userMessage())) } }
    }

    fun openAdd(open: Boolean) = state.update { it.copy(adding = open) }

    fun add(a: Automation) {
        state.update { it.copy(draft = it.draft + a, adding = false) }
        schedulePreview()
    }

    fun change(index: Int, a: Automation) {
        state.update { s -> s.copy(draft = s.draft.toMutableList().also { it[index] = a }) }
        schedulePreview()
    }

    fun remove(index: Int) {
        state.update { s -> s.copy(draft = s.draft.toMutableList().also { it.removeAt(index) }) }
        schedulePreview()
    }

    fun move(index: Int, by: Int) {
        val to = index + by
        state.update { s ->
            if (to !in s.draft.indices) s else s.copy(draft = s.draft.toMutableList().also { val a = it.removeAt(index); it.add(to, a) })
        }
    }

    /** Re-works out the projection shortly after the last edit. */
    private fun schedulePreview() {
        previewJob?.cancel()
        state.update { it.copy(previewing = true) }
        previewJob = viewModelScope.launch {
            delay(450)
            val draft = state.value.draft.filter { it !is Automation.Unreadable }
            if (draft.isEmpty()) {
                state.update { it.copy(previewing = false, projected = Money.Zero, perAutomation = emptyList(), previewError = null) }
                return@launch
            }
            runCatching { automations.preview(selectedBudget(), category, state.value.month, draft) }
                .onSuccess { p -> state.update { it.copy(previewing = false, projected = p.projected, perAutomation = p.perAutomation, previewError = null) } }
                .onFailure { e -> state.update { it.copy(previewing = false, previewError = e.userMessage()) } }
        }
    }

    /** Saves; with [apply], also budgets this month from them (overwriting). */
    fun save(apply: Boolean) {
        val draft = state.value.draft.filter { it !is Automation.Unreadable }
        state.update { it.copy(saving = true) }
        viewModelScope.launch {
            runCatching {
                val budget = selectedBudget()
                automations.set(budget, category, draft, state.value.month)
                if (apply) automations.apply(budget, state.value.month, overwrite = true, categories = listOf(category)) else null
            }
                .onSuccess { run ->
                    state.update {
                        it.copy(saving = false, done = true, message = run?.let { r -> r.details ?: "Saved and budgeted" } ?: "Saved")
                    }
                }
                .onFailure { e -> state.update { it.copy(saving = false, message = e.userMessage()) } }
        }
    }

    fun askNotes(ask: Boolean) = state.update { it.copy(confirmNotes = ask) }

    fun useNotes() {
        state.update { it.copy(confirmNotes = false, saving = true) }
        viewModelScope.launch {
            runCatching { automations.useNotes(selectedBudget(), category, state.value.month) }
                .onSuccess { c ->
                    state.update { s ->
                        val d = s.data.valueOrNull?.copy(source = c.source, saved = c.automations, notesHaveTemplates = c.notesHaveTemplates)
                        s.copy(saving = false, data = d?.let { Loadable.Ready(it) } ?: s.data, draft = c.automations, projected = c.projected, perAutomation = c.perAutomation, message = "Following the notes again")
                    }
                }
                .onFailure { e -> state.update { it.copy(saving = false, message = e.userMessage()) } }
        }
    }

    fun messageShown() = state.update { it.copy(message = null) }
}
