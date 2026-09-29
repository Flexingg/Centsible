package app.centsible

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.centsible.core.data.CapabilitiesRepository
import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.domain.BudgetEngine
import app.centsible.core.domain.ConnectionStatus
import app.centsible.core.domain.PendingChanges
import app.centsible.core.domain.SessionStore
import app.centsible.core.model.BudgetId
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Currency
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface AppState {
    data object Starting : AppState
    data object NeedsPairing : AppState
    data object NeedsBudget : AppState
    data class Ready(val budget: BudgetId) : AppState
}

@HiltViewModel
class AppViewModel @Inject constructor(
    sessions: SessionStore,
    private val capabilities: CapabilitiesRepository,
    private val engine: BudgetEngine,
    connection: ConnectionStatus,
    pendingChanges: PendingChanges,
    outboxSync: app.centsible.core.data.OutboxSync,
    undoCenter: app.centsible.core.domain.UndoCenter,
    private val personal: app.centsible.core.domain.PersonalGateway,
    changes: app.centsible.core.domain.BudgetChanges,
) : ViewModel() {
    /** The household's category colors and emoji, for the whole app. */
    val look: kotlinx.coroutines.flow.MutableStateFlow<app.centsible.core.designsystem.component.CategoryLook> =
        kotlinx.coroutines.flow.MutableStateFlow(app.centsible.core.designsystem.component.CategoryLook())
    private var openBudget: BudgetId? = null
    val undoOffers: kotlinx.coroutines.flow.SharedFlow<app.centsible.core.domain.Undoable> = undoCenter.offers
    val offline: StateFlow<Boolean> = connection.offline
    val pending: StateFlow<Int> = pendingChanges.pending

    init {
        outboxSync.start(connection.offline)
        viewModelScope.launch { changes.changes.collect { openBudget?.let { loadLook(it) } } }
    }

    /** Older bridges don't keep appearance; the defaults apply then. */
    private suspend fun loadLook(budget: BudgetId) {
        runCatching {
            val byId = personal.appearance(budget)
            val names = engine.categoryGroups(budget).flatMap { g -> listOf(g.id.raw to g.name) + g.categories.map { it.id.raw to it.name } }.toMap()
            app.centsible.core.designsystem.component.CategoryLook.of(byId, names)
        }.onSuccess { look.value = it }
    }

    val state: StateFlow<AppState> = sessions.session
        .map {
            val budget = it?.selectedBudget
            when {
                it == null -> AppState.NeedsPairing
                budget == null -> AppState.NeedsBudget
                else -> AppState.Ready(budget)
            }
        }
        .distinctUntilChanged()
        .onEach { if (it is AppState.Ready) onBudgetOpened(it.budget) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppState.Starting)

    private fun onBudgetOpened(budget: BudgetId) = viewModelScope.launch {
        openBudget = budget
        capabilities.refresh()
        loadLook(budget)
        // Actual budgets are single-currency; format money the way the budget says.
        runCatching { engine.preferences(budget) }.onSuccess { prefs ->
            runCatching { Currency.getInstance(prefs.currencyCode) }.onSuccess { MoneyFormat.currency = it }
        }
    }
}
