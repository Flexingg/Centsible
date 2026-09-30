package app.centsible.feature.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.domain.BudgetChanges
import app.centsible.core.domain.BudgetEngine
import app.centsible.core.domain.MoveMoney
import app.centsible.core.domain.SelectedBudget
import app.centsible.core.domain.SessionStore
import app.centsible.core.domain.userMessage
import app.centsible.core.model.Autopilot
import app.centsible.core.model.AverageBasis
import app.centsible.core.model.BudgetId
import app.centsible.core.model.BudgetMonth
import app.centsible.core.model.BudgetPot
import app.centsible.core.model.Capabilities
import app.centsible.core.model.CategoryId
import app.centsible.core.model.Feature
import app.centsible.core.model.Money
import app.centsible.core.model.YearMonth
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
    val canApplyGoals: Boolean = false,
    val canHold: Boolean = false,
    val canEditNotes: Boolean = false,
    /** Note for the open category; goal templates live here as `#template` lines. */
    val note: String? = null,
    val noteLoaded: Boolean = false,
    /** Autopilot sheet: null when closed. */
    val autopilot: Loadable<Autopilot>? = null,
    val autopilotBasis: AverageBasis = AverageBasis.Three,
    val autopilotSelected: Set<CategoryId> = emptySet(),
    /** Cover-overspending sheet: the plan comes from the same autopilot call. */
    val cover: Loadable<Autopilot>? = null,
    /** Yearly budgets by category id (bridge-kept). */
    val annual: Map<String, app.centsible.core.model.AnnualBudget> = emptyMap(),
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
    private val planning: app.centsible.core.domain.PlanningGateway,
    private val planAhead: app.centsible.core.domain.PlanAheadGateway,
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
                    canApplyGoals = role?.canWrite == true && caps.has(Feature.BudgetTemplates),
                    canHold = role?.canWrite == true && caps.has(Feature.BudgetHold),
                    canEditNotes = caps.has(Feature.CategoryNotes),
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

    /** A yearly amount for the category (null stops it); this month follows right away. */
    fun setAnnual(category: CategoryId, amount: Money?, startMonth: Int) = viewModelScope.launch {
        state.update { it.copy(saving = true) }
        runCatching { planAhead.setAnnualBudget(budget, category, amount, startMonth) }
            .onSuccess { state.update { it.copy(saving = false, message = if (amount == null) "Yearly budget stopped. This month's budget stays." else "Yearly budget saved") } }
            .onFailure { e -> state.update { it.copy(saving = false, message = e.userMessage()) } }
        load(refreshing = true)
    }
    fun openCategory(id: CategoryId?) {
        state.update { it.copy(selectedCategory = id, note = null, noteLoaded = false) }
        if (id != null && state.value.canEditNotes) viewModelScope.launch {
            val note = runCatching { planning.categoryNote(budget, id) }.getOrNull()
            if (state.value.selectedCategory == id) state.update { it.copy(note = note, noteLoaded = true) }
        }
    }

    fun saveNote(category: CategoryId, note: String) = viewModelScope.launch {
        val text = note.trim().ifEmpty { null }
        runCatching { planning.setCategoryNote(budget, category, text) }
            .onSuccess { state.update { it.copy(note = text, message = "Note saved") } }
            .onFailure { e -> state.update { it.copy(message = e.userMessage()) } }
    }

    /** Sets aside To Budget money for next month; null releases the hold. */
    fun hold(amount: Money?) = mutate(if (amount == null) "Hold released" else "Holding ${app.centsible.core.designsystem.component.MoneyFormat.format(amount)} for next month") {
        engine.holdForNextMonth(budget, state.value.month, amount)
    }

    /** Runs Actual's goal templates for the month; Actual reports what it did. */
    fun applyGoals(overwrite: Boolean) = viewModelScope.launch {
        state.update { it.copy(saving = true) }
        runCatching { planning.applyTemplates(budget, state.value.month, overwrite) }
            .onSuccess { (m, msg) -> state.update { it.copy(data = Loadable.Ready(m), saving = false, message = msg) } }
            .onFailure { e -> state.update { it.copy(saving = false, message = e.userMessage()) } }
    }
    fun messageShown() = state.update { it.copy(message = null) }

    // ── Autopilot ──

    fun openAutopilot(open: Boolean) {
        state.update { it.copy(autopilot = if (open) Loadable.Loading else null) }
        if (open) viewModelScope.launch {
            runCatching { planAhead.autopilot(budget, state.value.month) }
                .onSuccess { a -> state.update { it.copy(autopilot = Loadable.Ready(a), autopilotSelected = differing(a, it.autopilotBasis)) } }
                .onFailure { e -> state.update { it.copy(autopilot = Loadable.Failed(e.userMessage())) } }
        }
    }

    /** A new basis preselects every category whose suggestion changes. */
    fun setBasis(basis: AverageBasis) = state.update { s ->
        s.copy(autopilotBasis = basis, autopilotSelected = (s.autopilot as? Loadable.Ready)?.value?.let { differing(it, basis) } ?: emptySet())
    }

    fun toggleSuggestion(id: CategoryId) = state.update { s ->
        s.copy(autopilotSelected = if (id in s.autopilotSelected) s.autopilotSelected - id else s.autopilotSelected + id)
    }

    fun applyAutopilot() {
        val selected = state.value.autopilotSelected.toList()
        if (selected.isEmpty()) return
        val n = selected.size
        mutate(if (n == 1) "1 category budgeted" else "$n categories budgeted") {
            planAhead.applyAutopilot(budget, state.value.month, state.value.autopilotBasis, selected).also { state.update { it.copy(autopilot = null) } }
        }
    }

    private fun differing(a: Autopilot, basis: AverageBasis) = a.suggestions.filter { it.differs(basis) && it.monthsOfHistory > 0 }.map { it.categoryId }.toSet()

    // ── Cover overspending ──

    fun openCover(open: Boolean) {
        state.update { it.copy(cover = if (open) Loadable.Loading else null) }
        if (open) viewModelScope.launch {
            runCatching { planAhead.autopilot(budget, state.value.month) }
                .onSuccess { a -> state.update { it.copy(cover = Loadable.Ready(a)) } }
                .onFailure { e -> state.update { it.copy(cover = Loadable.Failed(e.userMessage())) } }
        }
    }

    fun coverOverspending() = viewModelScope.launch {
        state.update { it.copy(saving = true) }
        runCatching { planAhead.coverOverspending(budget, state.value.month) }
            .onSuccess { res ->
                val msg = when {
                    res.moves.isEmpty() -> "Nothing to cover"
                    res.uncovered.isZero -> "Overspending covered"
                    else -> "Covered what was available. ${app.centsible.core.designsystem.component.MoneyFormat.format(res.uncovered)} is still overspent."
                }
                state.update { it.copy(data = Loadable.Ready(res.month), saving = false, cover = null, message = msg) }
            }
            .onFailure { e -> state.update { it.copy(saving = false, message = e.userMessage()) } }
    }

    fun assign(category: CategoryId, amount: Money) = mutate("Budget updated") {
        engine.setBudgeted(budget, state.value.month, category, amount)
    }

    fun move(from: BudgetPot, to: BudgetPot, amount: Money) = mutate("Moved ${app.centsible.core.designsystem.component.MoneyFormat.format(amount)}") {
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
        // An older bridge has no yearly budgets: the rest still loads.
        runCatching { planAhead.annualBudgets(budget, month) }
            .onSuccess { a -> if (state.value.month == month) state.update { it.copy(annual = a.associateBy { x -> x.categoryId.raw }) } }
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
