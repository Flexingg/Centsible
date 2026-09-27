package app.canopy.feature.planning

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.HorizontalDivider
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.canopy.core.designsystem.component.CanopyCard
import app.canopy.core.designsystem.component.Loadable
import app.canopy.core.designsystem.component.LoadingState
import app.canopy.core.designsystem.component.MerchantAvatar
import app.canopy.core.designsystem.component.MessageState
import app.canopy.core.designsystem.component.MoneyFormat
import app.canopy.core.designsystem.component.MoneyText
import app.canopy.core.designsystem.component.MoneyTone
import app.canopy.core.designsystem.component.StatLabel
import app.canopy.core.designsystem.theme.CanopyTheme
import app.canopy.core.model.Schedule
import app.canopy.core.model.ScheduleDraft
import app.canopy.core.model.sum
import java.time.LocalDate

@Composable
fun RecurringRoute(onBack: () -> Unit, viewModel: RecurringViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    RecurringScreen(
        state = state,
        onBack = onBack,
        onRetry = { viewModel.refresh() },
        onOpen = viewModel::open,
        onClose = viewModel::close,
        onSave = { viewModel.save(it) },
        onSkip = { viewModel.skip(it) },
        onPost = { viewModel.postNow(it) },
        onDelete = { viewModel.delete(it) },
        onMessageShown = viewModel::messageShown,
    )
}

@Composable
fun RecurringScreen(
    state: RecurringUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit = {},
    onOpen: (Schedule?) -> Unit = {},
    onClose: () -> Unit = {},
    onSave: (ScheduleDraft) -> Unit = {},
    onSkip: (Schedule) -> Unit = {},
    onPost: (Schedule) -> Unit = {},
    onDelete: (Schedule) -> Unit = {},
    onMessageShown: () -> Unit = {},
    today: LocalDate = LocalDate.now(),
) {
    val colors = CanopyTheme.colors
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); onMessageShown() } }
    Scaffold(
        containerColor = colors.canvas,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("Recurring", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (state.canEdit) TextButton(onClick = { onOpen(null) }) { Text("Add") }
            }
        },
    ) { padding ->
        when (val d = state.data) {
            Loadable.Loading -> LoadingState(Modifier.padding(padding))
            is Loadable.Failed -> MessageState("Couldn't load recurring", d.message, emoji = "🔌", actionLabel = "Try again", onAction = onRetry, modifier = Modifier.padding(padding))
            is Loadable.Ready -> {
                val data = d.value
                val upcoming = data.upcoming(today)
                val later = data.later(today)
                if (upcoming.isEmpty() && later.isEmpty()) {
                    MessageState(
                        "Nothing recurring yet",
                        "Add bills and paychecks so you can see what's coming up. Actual can also find them from your history.",
                        emoji = "🔁",
                        modifier = Modifier.padding(padding),
                    )
                    return@Scaffold
                }
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { SummaryCard(upcoming) }
                    if (upcoming.isNotEmpty()) item { ScheduleGroup("Next 30 days", upcoming, data, today, onOpen) }
                    if (later.isNotEmpty()) item { ScheduleGroup("Later", later, data, today, onOpen) }
                }
            }
        }
    }

    val data = state.data.valueOrNull
    if (data != null && (state.editing != null || state.creating)) {
        ScheduleSheet(
            schedule = state.editing,
            data = data,
            canEdit = state.canEdit,
            canSkip = state.canSkip,
            canPost = state.canPost,
            onDismiss = onClose,
            onSave = onSave,
            onSkip = onSkip,
            onPost = onPost,
            onDelete = onDelete,
        )
    }
}

@Composable
private fun SummaryCard(upcoming: List<Schedule>) {
    val colors = CanopyTheme.colors
    val bills = upcoming.filter { it.amount.isNegative }
    val income = upcoming.filter { !it.amount.isNegative }
    CanopyCard(contentPadding = PaddingValues(20.dp)) {
        StatLabel("Due in the next 30 days")
        MoneyText(bills.map { it.amount }.sum().abs(), style = MaterialTheme.typography.displaySmall, showCents = false)
        Text(
            "${bills.size} bill${if (bills.size == 1) "" else "s"}" +
                if (income.isNotEmpty()) " · ${MoneyFormat.format(income.map { it.amount }.sum(), showCents = false)} coming in" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
    }
}

@Composable
private fun ScheduleGroup(title: String, items: List<Schedule>, data: RecurringData, today: LocalDate, onOpen: (Schedule) -> Unit) {
    val colors = CanopyTheme.colors
    Text(title, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
    CanopyCard(contentPadding = PaddingValues(0.dp)) {
        items.forEachIndexed { i, s ->
            if (i > 0) HorizontalDivider(Modifier.padding(start = 64.dp), color = colors.border)
            ScheduleRow(s, data, today, onClick = { onOpen(s) })
        }
    }
}

@Composable
private fun ScheduleRow(s: Schedule, data: RecurringData, today: LocalDate, onClick: () -> Unit) {
    val colors = CanopyTheme.colors
    val title = data.title(s)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        MerchantAvatar(title)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (s.postsTransaction) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Auto",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary,
                        modifier = Modifier.background(colors.cardMuted, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            Text(
                listOfNotNull(Describe.recurrence(s.recurrence, s.date), data.accountName(s)).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            MoneyText(
                s.amount,
                tone = MoneyTone.Signed,
                signed = s.amount.minor > 0,
                style = MaterialTheme.typography.bodyLarge,
            )
            val due = Describe.due(s.nextDate, today)
            Text(
                (if (s.amountOp == app.canopy.core.model.AmountOp.IsApprox) "Varies · " else "") + due,
                style = MaterialTheme.typography.labelSmall,
                color = if (due.endsWith("overdue") || due == "Today") colors.warning else colors.textTertiary,
            )
        }
    }
}
