package app.centsible.feature.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.domain.BudgetChanges
import app.centsible.core.domain.ReportsGateway
import app.centsible.core.domain.SelectedBudget
import app.centsible.core.domain.userMessage
import app.centsible.core.domain.BudgetEngine
import app.centsible.core.model.CashFlowMonth
import app.centsible.core.model.CategoryTree
import app.centsible.core.model.FlowsReport
import app.centsible.core.model.MAX_CATEGORY_LEVEL
import kotlinx.coroutines.coroutineScope
import app.centsible.core.model.NetWorthPoint
import app.centsible.core.model.SpendingReport
import app.centsible.core.model.YearMonth
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ReportTab(val label: String) { CashFlow("Cash flow"), Spending("Spending"), MoneyFlow("Money flow"), NetWorth("Net worth") }

/** The Money flow tab's report and the category tree it hangs from. */
data class MoneyFlow(val report: FlowsReport, val tree: CategoryTree)

data class ReportsUiState(
    val tab: ReportTab = ReportTab.CashFlow,
    val months: Int = 6,
    val cashFlow: Loadable<List<CashFlowMonth>> = Loadable.Loading,
    val selectedMonth: Int? = null,
    val spendingMonth: YearMonth = thisMonth(),
    val spending: Loadable<SpendingReport> = Loadable.Loading,
    val netWorth: Loadable<List<NetWorthPoint>> = Loadable.Loading,
    val selectedPoint: Int? = null,
    /** Money flow: how many months back from this one (1 = this month only). */
    val flowMonths: Int = 1,
    /** Money flow: the deepest level shown (1 = groups only, up to 5). */
    val flowDepth: Int = MAX_CATEGORY_LEVEL,
    val flow: Loadable<MoneyFlow> = Loadable.Loading,
    /** Money flow: the tapped box (its key). */
    val flowSelected: String? = null,
) {
    /** The range the Money flow tab covers, first month to last. */
    val flowRange: Pair<YearMonth, YearMonth> get() = thisMonth().let { it.plus(-(flowMonths - 1)) to it }
}

internal fun thisMonth() = LocalDate.now().let { YearMonth.of(it.year, it.monthValue) }

@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val reports: ReportsGateway,
    private val selectedBudget: SelectedBudget,
    private val engine: BudgetEngine,
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
        val flow = async { loadFlow(s.flowRange) }
        state.update {
            it.copy(
                flow = flow.await(),
                cashFlow = cash.await().fold({ v -> Loadable.Ready(v) }, { e -> Loadable.Failed(e.userMessage()) }),
                spending = spend.await().fold({ v -> Loadable.Ready(v) }, { e -> Loadable.Failed(e.userMessage()) }),
                netWorth = nw.await().fold({ v -> Loadable.Ready(v) }, { e -> Loadable.Failed(e.userMessage()) }),
            )
        }
    }

    private suspend fun loadFlow(range: Pair<YearMonth, YearMonth>): Loadable<MoneyFlow> = runCatching {
        coroutineScope {
            val b = selectedBudget()
            val report = async { reports.flows(b, range.first, range.second) }
            val groups = async { engine.categoryGroups(b) }
            MoneyFlow(report.await(), CategoryTree.of(groups.await()))
        }
    }.fold({ Loadable.Ready(it) }, { e -> Loadable.Failed(e.userMessage()) })

    fun flowMonths(months: Int) {
        state.update { it.copy(flowMonths = months, flow = Loadable.Loading, flowSelected = null) }
        viewModelScope.launch {
            val r = loadFlow(state.value.flowRange)
            state.update { it.copy(flow = r) }
        }
    }

    fun flowDepth(depth: Int) = state.update { it.copy(flowDepth = depth.coerceIn(1, MAX_CATEGORY_LEVEL), flowSelected = null) }
    fun selectFlow(key: String?) = state.update { it.copy(flowSelected = if (it.flowSelected == key) null else key) }

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
