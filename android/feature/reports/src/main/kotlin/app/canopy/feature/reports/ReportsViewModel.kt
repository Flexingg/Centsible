package app.canopy.feature.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.canopy.core.designsystem.component.Loadable
import app.canopy.core.domain.BudgetChanges
import app.canopy.core.domain.ReportsGateway
import app.canopy.core.domain.SelectedBudget
import app.canopy.core.domain.userMessage
import app.canopy.core.model.CashFlowMonth
import app.canopy.core.model.NetWorthPoint
import app.canopy.core.model.SpendingReport
import app.canopy.core.model.YearMonth
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ReportTab(val label: String) { CashFlow("Cash flow"), Spending("Spending"), NetWorth("Net worth") }

data class ReportsUiState(
    val tab: ReportTab = ReportTab.CashFlow,
    val months: Int = 6,
    val cashFlow: Loadable<List<CashFlowMonth>> = Loadable.Loading,
    val selectedMonth: Int? = null,
    val spendingMonth: YearMonth = thisMonth(),
    val spending: Loadable<SpendingReport> = Loadable.Loading,
    val netWorth: Loadable<List<NetWorthPoint>> = Loadable.Loading,
    val selectedPoint: Int? = null,
)

internal fun thisMonth() = LocalDate.now().let { YearMonth.of(it.year, it.monthValue) }

@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val reports: ReportsGateway,
    private val selectedBudget: SelectedBudget,
    changes: BudgetChanges,
) : ViewModel() {
    private val state = MutableStateFlow(ReportsUiState())
    val uiState: StateFlow<ReportsUiState> = state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch { changes.changes.collect { refresh() } }
    }

    fun refresh() = viewModelScope.launch {
        val b = selectedBudget()
        val s = state.value
        val end = thisMonth()
        val cash = async { runCatching { reports.cashFlow(b, end.plus(-(s.months - 1)), end) } }
        val spend = async { runCatching { reports.spending(b, s.spendingMonth, s.spendingMonth) } }
        val nw = async { runCatching { reports.netWorth(b, 12) } }
        state.update {
            it.copy(
                cashFlow = cash.await().fold({ v -> Loadable.Ready(v) }, { e -> Loadable.Failed(e.userMessage()) }),
                spending = spend.await().fold({ v -> Loadable.Ready(v) }, { e -> Loadable.Failed(e.userMessage()) }),
                netWorth = nw.await().fold({ v -> Loadable.Ready(v) }, { e -> Loadable.Failed(e.userMessage()) }),
            )
        }
    }

    fun tab(t: ReportTab) = state.update { it.copy(tab = t) }
    fun range(months: Int) { state.update { it.copy(months = months, selectedMonth = null) }; refresh() }
    fun selectMonth(i: Int) = state.update { it.copy(selectedMonth = if (it.selectedMonth == i) null else i) }
    fun selectPoint(i: Int) = state.update { it.copy(selectedPoint = i) }

    fun spendingMonth(delta: Int) {
        val next = state.value.spendingMonth.plus(delta)
        if (next > thisMonth()) return
        state.update { it.copy(spendingMonth = next, spending = Loadable.Loading) }
        viewModelScope.launch {
            val r = runCatching { reports.spending(selectedBudget(), next, next) }
            state.update { it.copy(spending = r.fold({ v -> Loadable.Ready(v) }, { e -> Loadable.Failed(e.userMessage()) })) }
        }
    }
}
