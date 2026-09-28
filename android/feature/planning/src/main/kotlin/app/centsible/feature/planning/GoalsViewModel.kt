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

    fun edit(editor: GoalEditor?) = state.update { it.copy(editor = editor) }

    fun save(category: CategoryId, input: GoalInput) = act("Goal saved") { plan.setGoal(selectedBudget(), category, input) }

    fun remove(category: CategoryId) = act("Goal removed. The category and its money stay.") { plan.setGoal(selectedBudget(), category, null) }

    fun messageShown() = state.update { it.copy(message = null) }

    private suspend fun load() {
        runCatching {
            val budget = selectedBudget()
            val goals = plan.goals(budget)
            val categories = engine.categoryGroups(budget).filter { !it.isIncome }.flatMap { g ->
                g.categories.filter { !it.hidden }.map { GoalCategory(it.id, it.name, g.name) }
            }
            goals to categories
        }
            .onSuccess { (goals, categories) -> state.update { it.copy(goals = Loadable.Ready(goals), categories = categories) } }
            .onFailure { e -> state.update { it.copy(goals = if (it.goals is Loadable.Ready) it.goals else Loadable.Failed(e.userMessage()), message = e.userMessage()) } }
    }

    private fun act(success: String, block: suspend () -> Unit) = viewModelScope.launch {
        state.update { it.copy(busy = true) }
        runCatching { block() }
            .onSuccess {
                state.update { it.copy(busy = false, editor = null, message = success) }
                load()
            }
            .onFailure { e -> state.update { it.copy(busy = false, message = e.userMessage()) } }
    }
}
