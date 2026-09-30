package app.centsible.feature.planning

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.centsible.core.designsystem.component.CentsibleCard
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.component.LoadingState
import app.centsible.core.designsystem.component.MerchantAvatar
import app.centsible.core.designsystem.component.MessageState
import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.designsystem.component.MoneyText
import app.centsible.core.designsystem.component.StatLabel
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.domain.InsightsGateway
import app.centsible.core.domain.PlanningGateway
import app.centsible.core.domain.SelectedBudget
import app.centsible.core.domain.SessionStore
import app.centsible.core.domain.userMessage
import app.centsible.core.model.AmountOp
import app.centsible.core.model.Frequency
import app.centsible.core.model.Money
import app.centsible.core.model.PayeeId
import app.centsible.core.model.PriceChange
import app.centsible.core.model.Recurrence
import app.centsible.core.model.RecurringCandidate
import app.centsible.core.model.RecurringPattern
import app.centsible.core.model.ScheduleDraft
import app.centsible.core.model.Subscriptions
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SubscriptionsUiState(
    val data: Loadable<Subscriptions> = Loadable.Loading,
    val canEdit: Boolean = false,
    /** Payees being tracked or dismissed right now. */
    val busy: Set<String> = emptySet(),
    val message: String? = null,
)

@HiltViewModel
class SubscriptionsViewModel @Inject constructor(
    private val insights: InsightsGateway,
    private val planning: PlanningGateway,
    private val selectedBudget: SelectedBudget,
    private val sessions: SessionStore,
) : ViewModel() {
    private val state = MutableStateFlow(SubscriptionsUiState())
    val uiState: StateFlow<SubscriptionsUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            state.update { it.copy(canEdit = sessions.current()?.member?.role?.canWrite == true) }
            load()
        }
    }

    fun load() = viewModelScope.launch {
        val r = runCatching { insights.subscriptions(selectedBudget()) }
        state.update { it.copy(data = r.fold({ v -> Loadable.Ready(v) }, { e -> Loadable.Failed(e.userMessage()) })) }
    }

    /** Turns a discovered pattern into a schedule, so it shows in Recurring, the calendar and the forecast. */
    fun track(c: RecurringCandidate) = act(c, "${c.payeeName} added to Recurring") {
        planning.createSchedule(
            selectedBudget(),
            ScheduleDraft(
                name = c.payeeName,
                payeeId = c.payeeId,
                accountId = c.accountId,
                amount = c.amount,
                amountOp = if (c.approximateAmount) AmountOp.IsApprox else AmountOp.Is,
                recurrence = c.recurrence,
            ),
        )
    }

    fun dismiss(c: RecurringCandidate) = act(c, "Won't suggest ${c.payeeName} again") { insights.dismissSubscription(selectedBudget(), c.payeeId) }

    /**
     * A bill that varies, or business-day pay, as a schedule: a range of amounts (lately's
     * lowest to highest) on those days of the month, moved off weekends the way it has been.
     */
    fun trackPattern(p: RecurringPattern) = actOn(p.payeeId, "${p.payeeName} added to Recurring") {
        val days = p.days.joinToString(",") { """{"type":"day","value":$it}""" }
        val start = p.next?.date ?: p.occurrences.lastOrNull()?.date ?: java.time.LocalDate.now().toString()
        planning.createSchedule(
            selectedBudget(),
            ScheduleDraft(
                name = p.payeeName,
                payeeId = p.payeeId,
                accountId = p.accountId,
                amount = if (p.varies) p.min else p.average3,
                amountOp = if (p.varies) AmountOp.IsBetween else AmountOp.IsApprox,
                amountMax = if (p.varies) p.max else null,
                recurrence = Recurrence(
                    frequency = Frequency.Monthly,
                    start = start,
                    skipWeekend = true,
                    weekendBefore = !p.weekendAfter,
                    patternsJson = "[$days]",
                ),
            ),
        )
    }

    fun dismissPattern(p: RecurringPattern) = actOn(p.payeeId, "Won't suggest ${p.payeeName} again") { insights.dismissSubscription(selectedBudget(), p.payeeId) }

    private fun actOn(payee: PayeeId, success: String, block: suspend () -> Unit) = viewModelScope.launch {
        state.update { it.copy(busy = it.busy + payee.raw) }
        runCatching { block() }
            .onSuccess {
                state.update { s ->
                    val data = (s.data as? Loadable.Ready)?.value
                    s.copy(
                        busy = s.busy - payee.raw,
                        message = success,
                        data = data?.let { Loadable.Ready(it.copy(patterns = it.patterns.filter { x -> x.payeeId != payee })) } ?: s.data,
                    )
                }
            }
            .onFailure { e -> state.update { it.copy(busy = it.busy - payee.raw, message = e.userMessage()) } }
    }

    fun messageShown() = state.update { it.copy(message = null) }

    private fun act(c: RecurringCandidate, success: String, block: suspend () -> Unit) = viewModelScope.launch {
        state.update { it.copy(busy = it.busy + c.payeeId.raw) }
        runCatching { block() }
            .onSuccess {
                state.update { s ->
                    val data = (s.data as? Loadable.Ready)?.value
                    s.copy(
                        busy = s.busy - c.payeeId.raw,
                        message = success,
                        data = data?.let { Loadable.Ready(it.copy(candidates = it.candidates.filter { x -> x.payeeId != c.payeeId })) } ?: s.data,
                    )
                }
            }
            .onFailure { e -> state.update { it.copy(busy = it.busy - c.payeeId.raw, message = e.userMessage()) } }
    }
}

@Composable
fun SubscriptionsRoute(onBack: () -> Unit, onOpenTransactions: (app.centsible.core.extensions.Destination.TransactionsFor) -> Unit = {}, viewModel: SubscriptionsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SubscriptionsScreen(
        state, onBack,
        onRetry = { viewModel.load() },
        onTrack = { viewModel.track(it) },
        onDismiss = { viewModel.dismiss(it) },
        onMessageShown = viewModel::messageShown,
        onOpenTransactions = onOpenTransactions,
        onTrackPattern = { viewModel.trackPattern(it) },
        onDismissPattern = { viewModel.dismissPattern(it) },
    )
}

internal fun every(c: RecurringCandidate): String {
    val n = c.recurrence.interval
    return when (c.recurrence.frequency) {
        Frequency.Weekly -> if (n == 2) "every 2 weeks" else if (n == 1) "a week" else "every $n weeks"
        Frequency.Monthly -> if (n == 1) "a month" else "every $n months"
        Frequency.Yearly -> if (n == 1) "a year" else "every $n years"
        Frequency.Daily -> if (n == 1) "a day" else "every $n days"
    }
}

@Composable
fun SubscriptionsScreen(
    state: SubscriptionsUiState,
    onBack: () -> Unit = {},
    onRetry: () -> Unit = {},
    onTrack: (RecurringCandidate) -> Unit = {},
    onDismiss: (RecurringCandidate) -> Unit = {},
    onMessageShown: () -> Unit = {},
    onOpenTransactions: (app.centsible.core.extensions.Destination.TransactionsFor) -> Unit = {},
    onTrackPattern: (RecurringPattern) -> Unit = {},
    onDismissPattern: (RecurringPattern) -> Unit = {},
) {
    val colors = CentsibleTheme.colors
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); onMessageShown() } }
    Scaffold(
        containerColor = colors.canvas,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("Subscriptions", style = MaterialTheme.typography.headlineSmall)
            }
        },
    ) { padding ->
        when (val d = state.data) {
            Loadable.Loading -> Column(Modifier.padding(padding)) {
                LoadingState(Modifier.weight(1f))
                Text("Looking through your history…", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, modifier = Modifier.padding(24.dp))
            }
            is Loadable.Failed -> MessageState("Couldn't look for subscriptions", d.message, emoji = "🔎", actionLabel = "Try again", onAction = onRetry, modifier = Modifier.padding(padding))
            is Loadable.Ready -> {
                val s = d.value
                val bills = s.candidates.filter { !it.income }
                val income = s.candidates.filter { it.income }
                val varying = s.patterns.filter { !it.income }
                val pay = s.patterns.filter { it.income }
                val open = { p: RecurringPattern -> onOpenTransactions(app.centsible.core.extensions.Destination.TransactionsFor(p.payeeName, payeeId = p.payeeId)) }
                if (s.candidates.isEmpty() && s.priceChanges.isEmpty() && s.patterns.isEmpty()) {
                    MessageState(
                        "Nothing new found",
                        "Everything that repeats in your history is already in Recurring. New ones show up here after three regular payments.",
                        emoji = "✅",
                        modifier = Modifier.padding(padding),
                    )
                    return@Scaffold
                }
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (bills.isNotEmpty()) {
                        item {
                            val yearly = Money(bills.sumOf { it.yearlyAmount.minor })
                            CentsibleCard(contentPadding = PaddingValues(20.dp)) {
                                StatLabel("Found in your history")
                                Text(
                                    "${bills.size} recurring payment${if (bills.size == 1) "" else "s"} you're not tracking yet",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    "About ${MoneyFormat.format(yearly.abs())} a year. Track them to see them in Recurring, the bill calendar and the forecast.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textSecondary,
                                )
                            }
                        }
                    }
                    if (s.priceChanges.isNotEmpty()) {
                        item { StatLabel("Price changes", Modifier.padding(start = 4.dp, top = 4.dp)) }
                        items(s.priceChanges, key = { "p" + it.scheduleId }) { PriceChangeCard(it) }
                    }
                    if (bills.isNotEmpty()) {
                        item { StatLabel("Subscriptions and bills", Modifier.padding(start = 4.dp, top = 4.dp)) }
                        items(bills, key = { it.payeeId.raw }) { CandidateCard(it, state, onTrack, onDismiss, onOpen = { onOpenTransactions(app.centsible.core.extensions.Destination.TransactionsFor(it.payeeName, payeeId = it.payeeId)) }) }
                    }
                    if (varying.isNotEmpty()) {
                        item { StatLabel("Bills that vary", Modifier.padding(start = 4.dp, top = 4.dp)) }
                        items(varying, key = { "v" + it.payeeId.raw + it.accountId.raw }) { PatternCard(it, state, onTrackPattern, onDismissPattern, onOpen = { open(it) }) }
                    }
                    if (income.isNotEmpty() || pay.isNotEmpty()) {
                        item { StatLabel("Income", Modifier.padding(start = 4.dp, top = 4.dp)) }
                        items(pay, key = { "i" + it.payeeId.raw + it.accountId.raw }) { PatternCard(it, state, onTrackPattern, onDismissPattern, onOpen = { open(it) }) }
                        items(income, key = { it.payeeId.raw }) { CandidateCard(it, state, onTrack, onDismiss, onOpen = { onOpenTransactions(app.centsible.core.extensions.Destination.TransactionsFor(it.payeeName, payeeId = it.payeeId)) }) }
                    }
                }
            }
        }
    }
}

/**
 * A bill that varies, or business-day pay: when it comes, what the next one will likely be
 * (and why), and the last payments as small bars so the swing is visible.
 */
@Composable
private fun PatternCard(p: RecurringPattern, state: SubscriptionsUiState, onTrack: (RecurringPattern) -> Unit, onDismiss: (RecurringPattern) -> Unit, onOpen: () -> Unit) {
    val colors = CentsibleTheme.colors
    val busy = p.payeeId.raw in state.busy
    CentsibleCard(onClick = onOpen, contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MerchantAvatar(p.payeeName)
            androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.payeeName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(p.description, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
        }
        p.next?.let { n ->
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Next, ${shortDate(n.date)}", style = MaterialTheme.typography.labelMedium, color = colors.textTertiary)
                    Text(
                        if (n.fromLastYear) "Like this month last year" else "The average of the last three",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textTertiary,
                    )
                }
                Text("about ", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                MoneyText(n.amount.abs(), style = MaterialTheme.typography.titleMedium, color = if (p.income) colors.positive else colors.textPrimary)
            }
        }
        if (p.occurrences.size > 1) {
            AmountBars(p, Modifier.padding(top = 10.dp))
            Text(
                if (p.varies) "Lately ${MoneyFormat.format(p.min.abs(), showCents = false)} to ${MoneyFormat.format(p.max.abs(), showCents = false)}"
                else "About ${MoneyFormat.format(p.average3.abs())} each time",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (state.canEdit) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onDismiss(p) }, enabled = !busy) { Text("Not recurring") }
                FilledTonalButton(onClick = { onTrack(p) }, enabled = !busy) { Text("Track it") }
            }
        }
    }
}

/** The last payments, oldest first, as bars scaled to the biggest. */
@Composable
private fun AmountBars(p: RecurringPattern, modifier: Modifier = Modifier) {
    val colors = CentsibleTheme.colors
    val biggest = p.occurrences.maxOf { it.amount.abs().minor }.coerceAtLeast(1)
    Row(
        modifier.fillMaxWidth().height(40.dp).semantics(mergeDescendants = true) {
            contentDescription = "Last ${p.occurrences.size}: " + p.occurrences.joinToString { MoneyFormat.format(it.amount.abs(), showCents = false) }
        },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        p.occurrences.forEach { o ->
            androidx.compose.foundation.layout.Box(
                Modifier.weight(1f)
                    .fillMaxHeight(o.amount.abs().minor.toFloat() / biggest)
                    .background(if (p.income) colors.positive.copy(alpha = 0.6f) else colors.accent.copy(alpha = 0.6f), androidx.compose.foundation.shape.RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
            )
        }
    }
}

@Composable
private fun CandidateCard(c: RecurringCandidate, state: SubscriptionsUiState, onTrack: (RecurringCandidate) -> Unit, onDismiss: (RecurringCandidate) -> Unit, onOpen: () -> Unit = {}) {
    val colors = CentsibleTheme.colors
    val busy = c.payeeId.raw in state.busy
    // Tapping the card shows the payments it was found from.
    CentsibleCard(onClick = onOpen, contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MerchantAvatar(c.payeeName)
            androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.payeeName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(c.accountName, c.lastDate?.let { "last ${shortDate(it)}" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.Bottom) {
                    if (c.approximateAmount) Text("~", style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary)
                    MoneyText(c.amount.abs(), style = MaterialTheme.typography.bodyLarge, color = if (c.income) colors.positive else colors.textPrimary)
                }
                Text(every(c), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
        }
        Text(
            "${MoneyFormat.format(c.yearlyAmount.abs())} a year",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (state.canEdit) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onDismiss(c) }, enabled = !busy) { Text("Not recurring") }
                FilledTonalButton(onClick = { onTrack(c) }, enabled = !busy) { Text("Track it") }
            }
        }
    }
}

@Composable
private fun PriceChangeCard(p: PriceChange) {
    val colors = CentsibleTheme.colors
    val up = p.latest < p.previous // bills are negative: more negative = more expensive
    CentsibleCard(contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (up) "🏷️" else "🎉", style = MaterialTheme.typography.titleLarge)
            androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${p.name} ${if (up) "went up" else "went down"}", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${MoneyFormat.format(p.previous.abs())} → ${MoneyFormat.format(p.latest.abs())} on ${shortDate(p.date)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
            Text("${if (p.changePct > 0) "+" else ""}${p.changePct}%", style = MaterialTheme.typography.titleSmall, color = if (up) colors.warning else colors.positive)
        }
    }
}
