package app.centsible.feature.planning

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.centsible.core.designsystem.component.AnimatedCheck
import app.centsible.core.designsystem.component.CentsibleCard
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.component.LoadingState
import app.centsible.core.designsystem.component.MessageState
import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.designsystem.component.MoneyText
import app.centsible.core.designsystem.component.MonthSwitcher
import app.centsible.core.designsystem.component.StatLabel
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.Forecast
import app.centsible.core.model.ForecastEvent
import app.centsible.core.model.Money
import app.centsible.core.model.YearMonth
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

@Composable
fun BillCalendarRoute(onBack: () -> Unit, onOpenRecurring: () -> Unit, onOpenForecast: () -> Unit, viewModel: ForecastViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.forCalendar() }
    BillCalendarScreen(
        state,
        CalendarActions(
            back = onBack,
            retry = viewModel::load,
            changeMonth = viewModel::changeMonth,
            selectDate = viewModel::selectDate,
            openRecurring = onOpenRecurring,
            openForecast = onOpenForecast,
        ),
    )
}

data class CalendarActions(
    val back: () -> Unit = {},
    val retry: () -> Unit = {},
    val changeMonth: (Int) -> Unit = {},
    val selectDate: (String?) -> Unit = {},
    val openRecurring: () -> Unit = {},
    val openForecast: () -> Unit = {},
)

private val LONG = DateTimeFormatter.ofPattern("EEEE, MMM d")

@Composable
fun BillCalendarScreen(state: ForecastUiState, actions: CalendarActions) {
    val today = state.today
    val colors = CentsibleTheme.colors
    Scaffold(
        containerColor = colors.canvas,
        topBar = {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("Bill calendar", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                MonthSwitcher(
                    month = state.calendarMonth,
                    onPrevious = { actions.changeMonth(-1) }.takeIf { state.calendarMonth > state.thisMonth },
                    onNext = { actions.changeMonth(1) }.takeIf { state.calendarMonth < state.lastMonthShown },
                )
            }
        },
    ) { padding ->
        when (val d = state.data) {
            Loadable.Loading -> LoadingState(Modifier.padding(padding))
            is Loadable.Failed -> MessageState("Couldn't load your bills", d.message, emoji = "📅", actionLabel = "Try again", onAction = actions.retry, modifier = Modifier.padding(padding))
            is Loadable.Ready -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val f = d.value
                val month = state.calendarMonth
                val inMonth = f.events.filter { it.date.startsWith(month.raw) && !it.internalTransfer }
                item { MonthTotals(inMonth) }
                item { CentsibleCard(contentPadding = PaddingValues(8.dp)) { MonthGrid(f, month, state.selectedDate, today, actions.selectDate) } }
                val selected = state.selectedDate
                if (selected != null) {
                    item { DayCard(f, selected, actions) }
                } else {
                    item {
                        StatLabel("This month", Modifier.padding(start = 4.dp, top = 4.dp))
                    }
                    item {
                        if (inMonth.isEmpty()) {
                            Text(
                                "No bills or income scheduled. Add recurring ones and they'll show up here.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                            TextButton(onClick = actions.openRecurring) { Text("Open Recurring") }
                        } else {
                            CentsibleCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                                inMonth.forEachIndexed { i, e ->
                                    if (i > 0) HorizontalDivider(color = colors.border)
                                    EventRow(e, showDate = true, balanceAfter = f.day(e.date)?.balance)
                                }
                            }
                        }
                    }
                }
                item {
                    Row {
                        TextButton(onClick = actions.openForecast) { Text("See the forecast") }
                        TextButton(onClick = actions.openRecurring) { Text("Manage recurring") }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthTotals(events: List<ForecastEvent>) {
    val colors = CentsibleTheme.colors
    val out = Money(events.filter { it.amount.isNegative }.sumOf { it.amount.minor })
    val income = Money(events.filter { !it.amount.isNegative }.sumOf { it.amount.minor })
    CentsibleCard(contentPadding = PaddingValues(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                StatLabel("Bills")
                MoneyText(out.abs(), style = MaterialTheme.typography.titleLarge, showCents = false)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                StatLabel("Income")
                MoneyText(income, style = MaterialTheme.typography.titleLarge, showCents = false, color = colors.positive)
            }
            Column(horizontalAlignment = Alignment.End) {
                StatLabel("Scheduled")
                Text("${events.size}", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun MonthGrid(f: Forecast, month: YearMonth, selected: String?, today: LocalDate, onSelect: (String?) -> Unit) {
    val colors = CentsibleTheme.colors
    val first = LocalDate.of(month.year, month.month, 1)
    val firstDow = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val lead = ((first.dayOfWeek.value - firstDow.value) + 7) % 7
    val days = first.lengthOfMonth()
    Row(Modifier.fillMaxWidth()) {
        (0 until 7).forEach { i ->
            val dow = DayOfWeek.of(((firstDow.value - 1 + i) % 7) + 1)
            Text(
                dow.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).padding(vertical = 4.dp),
            )
        }
    }
    val cells = lead + days
    (0 until (cells + 6) / 7).forEach { week ->
        Row(Modifier.fillMaxWidth()) {
            (0 until 7).forEach { col ->
                val n = week * 7 + col - lead + 1
                Box(Modifier.weight(1f).aspectRatio(0.85f).padding(2.dp)) {
                    if (n in 1..days) {
                        val date = first.withDayOfMonth(n)
                        DayCell(date, f, date.toString() == selected, date == today, date < today, onSelect)
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(date: LocalDate, f: Forecast, isSelected: Boolean, isToday: Boolean, past: Boolean, onSelect: (String?) -> Unit) {
    val colors = CentsibleTheme.colors
    val iso = date.toString()
    val events = f.eventsOn(iso).filter { !it.internalTransfer }
    val paid = f.paidOn(iso)
    val balance = f.day(iso)?.balance
    val low = balance?.isNegative == true
    val label = buildString {
        append(date.format(LONG))
        if (paid.isNotEmpty()) append(", paid: ${paid.joinToString { "${it.name} ${MoneyFormat.format(it.amount.abs())}" }}")
        if (events.isNotEmpty()) append(", ${events.size} scheduled: ${events.joinToString { "${it.name} ${MoneyFormat.format(it.amount)}" }}")
        if (balance != null) append(", projected balance ${MoneyFormat.format(balance)}")
    }
    Column(
        Modifier.fillMaxSize()
            .background(if (isSelected) colors.accentSoft else if (low) colors.negative.copy(alpha = 0.08f) else colors.card, RoundedCornerShape(10.dp))
            .then(if (isToday) Modifier.border(1.5.dp, colors.accent, RoundedCornerShape(10.dp)) else Modifier)
            .selectable(isSelected, enabled = !past, role = Role.Button) { onSelect(if (isSelected) null else iso) }
            .clearAndSetSemantics {
                contentDescription = label
                this.selected = isSelected
            }
            .padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "${date.dayOfMonth}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isToday) FontWeight.SemiBold else null,
            color = if (past) colors.textTertiary else colors.textPrimary,
        )
        Spacer(Modifier.height(4.dp))
        // Bills already paid tick themselves off, one day after another.
        if (paid.isNotEmpty()) AnimatedCheck(size = 14.dp, key = iso, delayMillis = date.dayOfMonth * 40, description = null)
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            events.take(3).forEach { e ->
                Box(Modifier.size(6.dp).background(if (e.amount.isNegative) colors.warning else colors.positive, CircleShape))
            }
        }
    }
}

@Composable
private fun DayCard(f: Forecast, date: String, actions: CalendarActions) {
    val colors = CentsibleTheme.colors
    val events = f.eventsOn(date).filter { !it.internalTransfer }
    val balance = f.day(date)?.balance
    CentsibleCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(LocalDate.parse(date).format(LONG), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { actions.selectDate(null) }) { Text("Close") }
        }
        balance?.let {
            Text(
                "Projected balance at the end of the day: ${MoneyFormat.format(it)}",
                style = MaterialTheme.typography.bodySmall,
                color = if (it.isNegative) colors.negative else colors.textSecondary,
            )
        }
        if (events.isEmpty()) Text("Nothing scheduled.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        events.forEachIndexed { i, e ->
            if (i > 0) HorizontalDivider(color = colors.border)
            EventRow(e, showDate = false, balanceAfter = null)
        }
    }
}

@Composable
private fun EventRow(e: ForecastEvent, showDate: Boolean, balanceAfter: Money?) {
    val colors = CentsibleTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showDate) {
            Column(Modifier.width(56.dp)) {
                Text(shortDate(e.date), style = MaterialTheme.typography.labelMedium, color = if (e.overdue) colors.negative else colors.textSecondary)
                if (e.overdue) Text("Overdue", style = MaterialTheme.typography.labelSmall, color = colors.negative)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(e.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            e.accountName?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textTertiary) }
        }
        Column(horizontalAlignment = Alignment.End) {
            MoneyText(e.amount, style = MaterialTheme.typography.bodyLarge, signed = true, color = if (e.amount.isNegative) colors.textPrimary else colors.positive)
            balanceAfter?.let { Text("then ${MoneyFormat.format(it)}", style = MaterialTheme.typography.labelSmall, color = if (it.isNegative) colors.negative else colors.textTertiary) }
        }
    }
}
