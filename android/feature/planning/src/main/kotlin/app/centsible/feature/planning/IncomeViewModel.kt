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
import app.centsible.core.model.IncomeMode
import app.centsible.core.model.IncomeOverview
import app.centsible.core.model.PayStub
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class IncomeTab(val label: String) {
    Overview("Income"),
    PayStubs("Pay stubs"),
}

data class IncomeData(
    val overview: IncomeOverview,
    val categoryNames: Map<String, String>,
)

data class IncomeUiState(
    val year: Int = LocalDate.now().year,
    val tab: IncomeTab = IncomeTab.Overview,
    val data: Loadable<IncomeData> = Loadable.Loading,
    val selectedMonth: Int? = null,
    val canEdit: Boolean = false,
    val pickingCategory: Boolean = false,
    val editingStub: PayStubFormState? = null,
    val saving: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class IncomeViewModel @Inject constructor(
    private val plan: PlanAheadGateway,
    private val engine: BudgetEngine,
    private val selectedBudget: SelectedBudget,
    private val sessions: SessionStore,
    changes: BudgetChanges,
) : ViewModel() {
    private val state = MutableStateFlow(IncomeUiState())
    val uiState: StateFlow<IncomeUiState> = state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch { changes.changes.collect { refresh(silent = true) } }
    }

    fun refresh(silent: Boolean = false) = viewModelScope.launch {
        if (!silent && state.value.data !is Loadable.Ready) {
            state.update { it.copy(data = Loadable.Loading) }
        }
        val yr = state.value.year
        runCatching {
            coroutineScope {
                val b = selectedBudget()
                val ov = async { plan.incomeOverview(b, yr) }
                val groups = async { engine.categoryGroups(b) }
                val names = groups.await().flatMap { it.categories }.associate { it.id.raw to it.name }
                IncomeData(ov.await(), names)
            }
        }
            .onSuccess { d ->
                val canEdit = sessions.current()?.member?.role?.canWrite == true
                state.update { it.copy(data = Loadable.Ready(d), canEdit = canEdit) }
            }
            .onFailure { e ->
                if (!silent) state.update { it.copy(data = Loadable.Failed(e.userMessage())) }
                else state.update { it.copy(message = e.userMessage()) }
            }
    }

    fun setTab(tab: IncomeTab) = state.update { it.copy(tab = tab) }

    fun shiftYear(delta: Int) {
        val next = (state.value.year + delta).coerceIn(2000, LocalDate.now().year + 1)
        if (next == state.value.year) return
        state.update { it.copy(year = next, selectedMonth = null) }
        refresh()
    }

    fun selectMonth(index: Int?) = state.update { it.copy(selectedMonth = if (it.selectedMonth == index) null else index) }

    fun setMode(mode: IncomeMode) {
        val cur = state.value.data.valueOrNull?.overview ?: return
        if (cur.mode == mode) return
        if (mode == IncomeMode.Categories && cur.categoryIds.isEmpty()) {
            state.update { it.copy(pickingCategory = true) }
            return
        }
        saveSettings(mode, cur.categoryIds)
    }

    fun openCategoryPicker(open: Boolean) = state.update { it.copy(pickingCategory = open) }

    fun addCategory(id: CategoryId) {
        val cur = state.value.data.valueOrNull?.overview ?: return
        state.update { it.copy(pickingCategory = false) }
        val next = (cur.categoryIds + id).distinct()
        saveSettings(IncomeMode.Categories, next)
    }

    fun removeCategory(id: CategoryId) {
        val cur = state.value.data.valueOrNull?.overview ?: return
        val next = cur.categoryIds.filterNot { it == id }
        val nextMode = if (next.isEmpty()) IncomeMode.All else IncomeMode.Categories
        saveSettings(nextMode, next)
    }

    private fun saveSettings(mode: IncomeMode, categoryIds: List<CategoryId>) = viewModelScope.launch {
        runCatching { plan.setIncomeSettings(selectedBudget(), mode, categoryIds) }
            .onSuccess { refresh(silent = true) }
            .onFailure { e -> state.update { it.copy(message = e.userMessage()) } }
    }

    // ── Pay stub editor ──────────────────────────────────────────────────────

    fun startNewStub() {
        val template = state.value.data.valueOrNull?.overview?.template
        val today = LocalDate.now().let { d ->
            if (d.year == state.value.year) d.toString() else "${state.value.year}-12-31"
        }
        val form = if (template != null) IncomeCalc.fromTemplate(template, today) else PayStubFormState(date = today)
        state.update { it.copy(editingStub = form) }
    }

    fun editStub(stub: PayStub) {
        state.update { it.copy(editingStub = IncomeCalc.fromStub(stub)) }
    }

    fun dismissStubEditor() = state.update { it.copy(editingStub = null) }

    fun updateForm(f: (PayStubFormState) -> PayStubFormState) {
        state.update { s -> s.copy(editingStub = s.editingStub?.let(f)) }
    }

    fun saveStub() {
        val form = state.value.editingStub ?: return
        if (!form.isValid || state.value.saving) return
        state.update { it.copy(saving = true) }
        viewModelScope.launch {
            runCatching { plan.savePayStub(selectedBudget(), form.id, IncomeCalc.toInput(form)) }
                .onSuccess {
                    state.update {
                        it.copy(
                            editingStub = null,
                            saving = false,
                            message = if (form.id == null) "Pay stub saved" else "Pay stub updated",
                        )
                    }
                    refresh(silent = true)
                }
                .onFailure { e -> state.update { it.copy(saving = false, message = e.userMessage()) } }
        }
    }

    fun deleteStub(id: String) {
        state.update { it.copy(saving = true) }
        viewModelScope.launch {
            runCatching { plan.deletePayStub(selectedBudget(), id) }
                .onSuccess {
                    state.update { it.copy(editingStub = null, saving = false, message = "Pay stub deleted") }
                    refresh(silent = true)
                }
                .onFailure { e -> state.update { it.copy(saving = false, message = e.userMessage()) } }
        }
    }

    fun messageShown() = state.update { it.copy(message = null) }
}
