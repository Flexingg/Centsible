package app.centsible.feature.planning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.domain.BudgetChanges
import app.centsible.core.domain.PlanAheadGateway
import app.centsible.core.domain.SelectedBudget
import app.centsible.core.domain.userMessage
import app.centsible.core.model.Forecast
import app.centsible.core.model.YearMonth
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ForecastUiState(
    val today: LocalDate = LocalDate.now(),
    val days: Int = 90,
    val includeTypical: Boolean = true,
    val data: Loadable<Forecast> = Loadable.Loading,
    /** Chart readout. */
    val selectedIndex: Int? = null,
    /** Bill calendar. */
    val calendarMonth: YearMonth = today.let { YearMonth.of(it.year, it.monthValue) },
    val selectedDate: String? = null,
) {
    val thisMonth get() = YearMonth.of(today.year, today.monthValue)
    val lastMonthShown get() = thisMonth.plus(CALENDAR_MONTHS - 1)

    companion object {
        val RANGES = listOf(30, 60, 90, 180, 365)
        /** The calendar shows this month and the next three. */
        const val CALENDAR_MONTHS = 4
    }
}

/** The cash-flow forecast and the bill calendar: the same projection, two views. */
@HiltViewModel
class ForecastViewModel @Inject constructor(
    private val plan: PlanAheadGateway,
    private val selectedBudget: SelectedBudget,
    changes: BudgetChanges,
) : ViewModel() {
    private val state = MutableStateFlow(ForecastUiState())
    val uiState: StateFlow<ForecastUiState> = state.asStateFlow()
    private var loading: Job? = null

    init {
        load()
        viewModelScope.launch { changes.changes.collect { load() } }
    }

    /** The calendar needs enough days to reach the end of its last month. */
    fun forCalendar() {
        val end = state.value.lastMonthShown
        val lastDay = LocalDate.of(end.year, end.month, 1).plusMonths(1).minusDays(1)
        val days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), lastDay).toInt().coerceIn(7, 366)
        if (days != state.value.days) {
            state.update { it.copy(days = days) }
            load()
        }
    }

    fun setDays(days: Int) {
        state.update { it.copy(days = days, selectedIndex = null) }
        load()
    }

    fun setIncludeTypical(on: Boolean) {
        state.update { it.copy(includeTypical = on, selectedIndex = null) }
        load()
    }

    fun select(index: Int?) = state.update { it.copy(selectedIndex = index) }
    fun selectDate(date: String?) = state.update { it.copy(selectedDate = date) }
    fun changeMonth(delta: Int) = state.update { s ->
        val next = s.calendarMonth.plus(delta)
        if (next < s.thisMonth || next > s.lastMonthShown) s else s.copy(calendarMonth = next, selectedDate = null)
    }

    fun load() {
        loading?.cancel()
        loading = viewModelScope.launch {
            val current = state.value.data
            if (current is Loadable.Ready) state.update { it.copy(data = current.copy(refreshing = true)) }
            runCatching { plan.forecast(selectedBudget(), state.value.days, includeTypical = state.value.includeTypical) }
                .onSuccess { f -> state.update { it.copy(data = Loadable.Ready(f)) } }
                .onFailure { e -> state.update { it.copy(data = Loadable.Failed(e.userMessage())) } }
        }
    }
}
