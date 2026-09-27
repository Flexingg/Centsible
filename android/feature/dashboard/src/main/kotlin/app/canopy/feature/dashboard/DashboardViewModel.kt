package app.canopy.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.canopy.core.designsystem.component.Loadable
import app.canopy.core.domain.BudgetChanges
import app.canopy.core.domain.BudgetEngine
import app.canopy.core.domain.SelectedBudget
import app.canopy.core.domain.SessionStore
import app.canopy.core.domain.TransactionQuery
import app.canopy.core.domain.userMessage
import app.canopy.core.extensions.DashboardContext
import app.canopy.core.extensions.DashboardWidget
import app.canopy.core.extensions.Destination
import app.canopy.core.model.YearMonth
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DashboardUiState(
    val greetingName: String? = null,
    /** Context with a no-op navigate; the screen supplies the real one. */
    val data: Loadable<DashboardContext> = Loadable.Loading,
    val widgets: List<DashboardWidget> = emptyList(),
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val engine: BudgetEngine,
    private val selectedBudget: SelectedBudget,
    private val sessions: SessionStore,
    private val allWidgets: Set<@JvmSuppressWildcards DashboardWidget>,
    changes: BudgetChanges,
) : ViewModel() {
    private val state = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch { changes.changes.collect { refresh() } }
    }

    fun refresh() = viewModelScope.launch {
        val member = sessions.current()?.member
        runCatching {
            val budget = selectedBudget()
            val now = LocalDate.now()
            val caps = async { engine.capabilities() }
            val month = async { runCatching { engine.budgetMonth(budget, YearMonth.of(now.year, now.monthValue)) }.getOrNull() }
            val accounts = async { engine.accounts(budget) }
            val recent = async { engine.transactions(budget, TransactionQuery(limit = 6)).items }
            val categories = async { engine.categoryGroups(budget).flatMap { it.categories }.associate { it.id.raw to it.name } }
            DashboardContext(budget, member, caps.await(), month.await(), accounts.await(), recent.await(), categories.await(), navigate = {})
        }
            .onSuccess { ctx ->
                val widgets = allWidgets.filter { w -> w.requires.all(ctx.capabilities::has) }.sortedBy { it.order }
                state.value = DashboardUiState(member?.displayName, Loadable.Ready(ctx), widgets)
            }
            .onFailure { state.value = DashboardUiState(member?.displayName, Loadable.Failed(it.userMessage())) }
    }
}
