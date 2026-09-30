package app.centsible.feature.planning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.domain.BudgetChanges
import app.centsible.core.domain.BudgetEngine
import app.centsible.core.domain.PlanAheadGateway
import app.centsible.core.domain.SelectedBudget
import app.centsible.core.domain.SessionStore
import app.centsible.core.domain.userMessage
import app.centsible.core.model.CategoryId
import app.centsible.core.model.Goal
import app.centsible.core.model.GoalInput
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A category a goal can go on: expense categories that don't have one yet. */
data class GoalCategory(val id: CategoryId, val name: String, val groupName: String)

/** The editor: [goal] is null when adding one. */
data class GoalEditor(val goal: Goal?, val category: GoalCategory?)

data class GoalsUiState(
    val goals: Loadable<List<Goal>> = Loadable.Loading,
    val categories: List<GoalCategory> = emptyList(),
    val canEdit: Boolean = false,
    val editor: GoalEditor? = null,
    /** Goals on accounts and on monthly spending. */
    val targets: List<app.centsible.core.model.Target> = emptyList(),
    val accounts: List<Pair<app.centsible.core.model.AccountId, String>> = emptyList(),
    val categoryNames: Map<String, String> = emptyMap(),
    val choosing: Boolean = false,
    val targetEditor: TargetEditor? = null,
    val busy: Boolean = false,
    val message: String? = null,
) {
    val available get() = (goals as? Loadable.Ready)?.value.orEmpty().map { it.categoryId }.toSet().let { taken -> categories.filter { it.id !in taken } }
}

@HiltViewModel
class GoalsViewModel @Inject constructor(
    private val plan: PlanAheadGateway,
    private val engine: BudgetEngine,
    private val selectedBudget: SelectedBudget,
    private val sessions: SessionStore,
    changes: BudgetChanges,
) : ViewModel() {
    private val state = MutableStateFlow(GoalsUiState())
    val uiState: StateFlow<GoalsUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            state.update { it.copy(canEdit = sessions.current()?.member?.role?.canWrite == true) }
            load()
            changes.changes.collect { load() }
        }
    }

    fun refresh() = viewModelScope.launch { load() }

    fun edit(editor: GoalEditor?) = state.update { it.copy(editor = editor, choosing = false) }
    fun choose(open: Boolean) = state.update { it.copy(choosing = open) }
    fun editTarget(editor: TargetEditor?) = state.update { it.copy(targetEditor = editor, choosing = false) }

    fun saveTarget(id: String?, input: app.centsible.core.model.TargetInput) = act("Goal saved") { plan.saveTarget(selectedBudget(), id, input) }

    fun deleteTarget(id: String) = act("Goal removed") { plan.deleteTarget(selectedBudget(), id) }

    fun save(category: CategoryId, input: GoalInput) = act("Goal saved") { plan.setGoal(selectedBudget(), category, input) }

    fun remove(category: CategoryId) = act("Goal removed. The category and its money stay.") { plan.setGoal(selectedBudget(), category, null) }

    fun messageShown() = state.update { it.copy(message = null) }

    private suspend fun load() {
        runCatching {
            val budget = selectedBudget()
            val goals = plan.goals(budget)
            val groups = engine.categoryGroups(budget)
            val categories = groups.filter { !it.isIncome }.flatMap { g ->
                g.categories.filter { !it.hidden }.map { GoalCategory(it.id, it.name, g.name) }
            }
            // An older bridge has no such goals: show the rest anyway.
            val targets = runCatching { plan.targets(budget) }.getOrDefault(emptyList())
            val accounts = engine.accounts(budget).filter { !it.closed }.map { it.id to it.name }
            Loaded(goals, categories, targets, accounts, groups.flatMap { it.categories }.associate { it.id.raw to it.name })
        }
            .onSuccess { l ->
                state.update {
                    it.copy(goals = Loadable.Ready(l.goals), categories = l.categories, targets = l.targets, accounts = l.accounts, categoryNames = l.names)
                }
            }
            .onFailure { e -> state.update { it.copy(goals = if (it.goals is Loadable.Ready) it.goals else Loadable.Failed(e.userMessage()), message = e.userMessage()) } }
    }

    private fun act(success: String, block: suspend () -> Unit) = viewModelScope.launch {
        state.update { it.copy(busy = true) }
        runCatching { block() }
            .onSuccess {
                state.update { it.copy(busy = false, editor = null, targetEditor = null, message = success) }
                load()
            }
            .onFailure { e -> state.update { it.copy(busy = false, message = e.userMessage()) } }
    }
}

private data class Loaded(
    val goals: List<Goal>,
    val categories: List<GoalCategory>,
    val targets: List<app.centsible.core.model.Target>,
    val accounts: List<Pair<app.centsible.core.model.AccountId, String>>,
    val names: Map<String, String>,
)
