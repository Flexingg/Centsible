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
    /** Arranging Home: every card that could show, in order, and whether it does. */
    val arranging: List<Pair<DashboardWidget, Boolean>>? = null,
)

/** This person's order and hidden cards, applied to the cards this budget can show. */
internal fun arrange(eligible: List<DashboardWidget>, layout: app.centsible.core.model.HomeLayout): List<DashboardWidget> {
    val byId = eligible.associateBy { it.id }
    val ordered = layout.order.mapNotNull { byId[it] }
    // Cards the person hasn't placed yet (new ones) keep their default spot at the end.
    return ordered + eligible.filter { it.id !in layout.order }
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val engine: BudgetEngine,
    private val selectedBudget: SelectedBudget,
    private val sessions: SessionStore,
    private val allWidgets: Set<@JvmSuppressWildcards DashboardWidget>,
    private val tools: app.centsible.core.domain.TransactionTools,
    private val personal: app.centsible.core.domain.PersonalGateway,
    changes: BudgetChanges,
) : ViewModel() {
    private val state = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch { changes.changes.collect { refresh() } }
    }

    private var eligible: List<DashboardWidget> = emptyList()
    private var layout: app.centsible.core.model.HomeLayout? = null

    // ── Arranging Home ──

    fun startArranging() {
        val l = layout ?: app.centsible.core.model.HomeLayout()
        state.value = state.value.copy(arranging = arrange(eligible, l).map { it to (it.alwaysAvailable || it.id !in l.hidden) })
    }

    fun move(index: Int, by: Int) {
        val list = state.value.arranging?.toMutableList() ?: return
        val to = index + by
        if (to !in list.indices) return
        list.add(to, list.removeAt(index))
        state.value = state.value.copy(arranging = list)
    }

    fun toggle(index: Int) {
        val list = state.value.arranging?.toMutableList() ?: return
        val (w, on) = list[index]
        if (w.alwaysAvailable) return
        list[index] = w to !on
        state.value = state.value.copy(arranging = list)
    }

    fun resetArrangement() {
        state.value = state.value.copy(arranging = eligible.map { it to true })
    }

    /** Saves the arrangement to the bridge (so it follows this person to another phone). */
    fun doneArranging() {
        val list = state.value.arranging ?: return
        val next = app.centsible.core.model.HomeLayout(list.map { it.first.id }, list.filter { !it.second }.map { it.first.id }.toSet())
        layout = next
        state.value = state.value.copy(
            arranging = null,
            widgets = arrange(eligible, next).filter { it.alwaysAvailable || it.id !in next.hidden },
        )
        viewModelScope.launch { runCatching { personal.setHomeLayout(next) } }
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
                eligible = allWidgets.filter { w -> w.requires.all(ctx.capabilities::has) }.sortedBy { it.order }
                if (layout == null) layout = runCatching { personal.homeLayout() }.getOrNull() ?: app.centsible.core.model.HomeLayout()
                val shown = arrange(eligible, layout!!).filter { it.alwaysAvailable || it.id !in layout!!.hidden }
                state.value = DashboardUiState(member?.displayName, Loadable.Ready(ctx), shown, arranging = state.value.arranging)
            }
            .onFailure { state.value = DashboardUiState(member?.displayName, Loadable.Failed(it.userMessage())) }
    }
}
