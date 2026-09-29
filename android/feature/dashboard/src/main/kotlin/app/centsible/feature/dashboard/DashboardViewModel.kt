package app.centsible.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.domain.BudgetChanges
import app.centsible.core.domain.BudgetEngine
import app.centsible.core.domain.SelectedBudget
import app.centsible.core.domain.SessionStore
import app.centsible.core.domain.TransactionQuery
import app.centsible.core.domain.userMessage
import app.centsible.core.extensions.DashboardContext
import app.centsible.core.extensions.DashboardWidget
import app.centsible.core.extensions.Destination
import app.centsible.core.model.YearMonth
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
    /** A pull-to-refresh is running. */
    val refreshing: Boolean = false,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val engine: BudgetEngine,
    private val selectedBudget: SelectedBudget,
    private val sessions: SessionStore,
    private val allWidgets: Set<@JvmSuppressWildcards DashboardWidget>,
    private val tools: app.centsible.core.domain.TransactionTools,
    changes: BudgetChanges,
) : ViewModel() {
    private val state = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch { changes.changes.collect { refresh() } }
    }

    fun pullToRefresh() {
        state.value = state.value.copy(refreshing = true)
        refresh()
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
            // Older bridges have no inbox; the card just doesn't show.
            val review = async { runCatching { tools.inbox(budget, limit = 3) }.getOrNull() }
            val inbox = review.await()
            DashboardContext(
                budget, member, caps.await(), month.await(), accounts.await(), recent.await(), categories.await(), navigate = {},
                reviewCount = inbox?.total,
                reviewPreview = inbox?.items.orEmpty(),
            )
        }
            .onSuccess { ctx ->
                val widgets = allWidgets.filter { w -> w.requires.all(ctx.capabilities::has) }.sortedBy { it.order }
                state.value = DashboardUiState(member?.displayName, Loadable.Ready(ctx), widgets)
            }
            .onFailure { state.value = DashboardUiState(member?.displayName, Loadable.Failed(it.userMessage())) }
    }
}
