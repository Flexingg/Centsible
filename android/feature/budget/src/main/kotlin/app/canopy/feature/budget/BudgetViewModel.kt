package app.canopy.feature.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.canopy.core.designsystem.component.Loadable
import app.canopy.core.domain.BudgetChanges
import app.canopy.core.domain.BudgetEngine
import app.canopy.core.domain.MoveMoney
import app.canopy.core.domain.SelectedBudget
import app.canopy.core.domain.SessionStore
import app.canopy.core.domain.userMessage
import app.canopy.core.model.BudgetId
import app.canopy.core.model.BudgetMonth
import app.canopy.core.model.BudgetPot
import app.canopy.core.model.Capabilities
import app.canopy.core.model.CategoryId
import app.canopy.core.model.Feature
import app.canopy.core.model.Money
import app.canopy.core.model.YearMonth
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BudgetUiState(
    val month: YearMonth = currentMonth(),
    val availableMonths: List<YearMonth> = emptyList(),
    val data: Loadable<BudgetMonth> = Loadable.Loading,
    val canEdit: Boolean = false,
    val canMoveMoney: Boolean = false,
    val canToggleRollover: Boolean = false,
    val canManageCategories: Boolean = false,
    val selectedCategory: CategoryId? = null,
    val saving: Boolean = false,
    val message: String? = null,
) {
    val hasPrevious get() = availableMonths.any { it < month }
    val hasNext get() = availableMonths.any { it > month }
}

fun currentMonth(): YearMonth = LocalDate.now().let { YearMonth.of(it.year, it.monthValue) }

@HiltViewModel
class BudgetViewModel @Inject constructor(
    private val engine: BudgetEngine,
    private val selectedBudget: SelectedBudget,
    private val sessions: SessionStore,
    private val moveMoney: MoveMoney,
    changes: BudgetChanges,
) : ViewModel() {
    private val state = MutableStateFlow(BudgetUiState())
    val uiState: StateFlow<BudgetUiState> = state.asStateFlow()
    private var selected: BudgetId? = null
    private val budget: BudgetId get() = checkNotNull(selected) { "Budget still loading" }

    init {
        viewModelScope.launch {
            selected = selectedBudget()
            val role = sessions.current()?.member?.role
            val caps = runCatching { engine.capabilities() }.getOrDefault(Capabilities.None)
            state.update {
                it.copy(
                    canEdit = role?.canWrite == true && caps.has(Feature.BudgetEnvelope),
                    canMoveMoney = role?.canWrite == true && caps.has(Feature.BudgetMoveMoney),
                    canToggleRollover = role?.canWrite == true && caps.has(Feature.BudgetCarryover),
                    canManageCategories = role?.canWrite == true && caps.has(Feature.CategoriesWrite),
                    availableMonths = runCatching { engine.budgetMonths(budget) }.getOrDefault(emptyList()),
                )
            }
            load()
            // Transactions edited elsewhere change "spent"; reload quietly.
            changes.changes.collect { load(refreshing = true) }
        }
    }

    fun previousMonth() = changeMonth(-1)
    fun nextMonth() = changeMonth(+1)
    fun refresh() = viewModelScope.launch { load(refreshing = true) }
    fun openCategory(id: CategoryId?) = state.update { it.copy(selectedCategory = id) }
    fun messageShown() = state.update { it.copy(message = null) }

    fun assign(category: CategoryId, amount: Money) = mutate("Budget updated") {
        engine.setBudgeted(budget, state.value.month, category, amount)
    }

    fun move(from: BudgetPot, to: BudgetPot, amount: Money) = mutate("Moved ${app.canopy.core.designsystem.component.MoneyFormat.format(amount)}") {
        moveMoney(budget, state.value.month, from, to, amount)
    }

    fun setRollover(category: CategoryId, enabled: Boolean) = mutate(null) {
        engine.setCarryover(budget, state.value.month, category, enabled)
    }

    private fun changeMonth(delta: Int) {
        state.update { it.copy(month = it.month.plus(delta), data = Loadable.Loading, selectedCategory = null) }
        viewModelScope.launch { load() }
    }

    private suspend fun load(refreshing: Boolean = false) {
        val current = state.value.data
        if (refreshing && current is Loadable.Ready) state.update { it.copy(data = current.copy(refreshing = true)) }
        val month = state.value.month
        runCatching { engine.budgetMonth(budget, month) }
            .onSuccess { m -> if (state.value.month == month) state.update { it.copy(data = Loadable.Ready(m)) } }
            .onFailure { e -> state.update { it.copy(data = if (current is Loadable.Ready) current.copy(refreshing = false) else Loadable.Failed(e.userMessage()), message = e.userMessage()) } }
    }

    /** Every change returns Actual's recomputed month; the UI never computes budget math. */
    private fun mutate(success: String?, block: suspend () -> BudgetMonth) = viewModelScope.launch {
        state.update { it.copy(saving = true) }
        runCatching { block() }
            .onSuccess { m -> state.update { it.copy(data = Loadable.Ready(m), saving = false, message = success) } }
            .onFailure { e -> state.update { it.copy(saving = false, message = e.userMessage()) } }
    }
}
