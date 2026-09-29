package app.centsible.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.centsible.core.extensions.DashboardWidget
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.component.LoadingState
import app.centsible.core.designsystem.component.MessageState
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.extensions.Destination
import java.time.LocalTime

@Composable
fun DashboardRoute(onNavigate: (Destination) -> Unit, viewModel: DashboardViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DashboardScreen(
        state, onNavigate, onRetry = { viewModel.refresh() }, onRefresh = viewModel::pullToRefresh,
        arrange = ArrangeActions(viewModel::startArranging, viewModel::move, viewModel::toggle, viewModel::resetArrangement, viewModel::doneArranging),
    )
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun DashboardScreen(
    state: DashboardUiState,
    onNavigate: (Destination) -> Unit,
    onRetry: () -> Unit,
    now: LocalTime = LocalTime.now(),
    onRefresh: () -> Unit = onRetry,
    arrange: ArrangeActions = ArrangeActions(),
) {
    val colors = CentsibleTheme.colors
    Column(Modifier.fillMaxSize().background(colors.canvas)) {
        val greeting = when (now.hour) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; else -> "Good evening" }
        Text(
            listOfNotNull(greeting, state.greetingName).joinToString(", "),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 8.dp),
        )
        when (val data = state.data) {
            Loadable.Loading -> LoadingState()
            is Loadable.Failed -> MessageState("Couldn't reach your budget", data.message, emoji = "🔌", actionLabel = "Try again", onAction = onRetry)
            is Loadable.Ready -> {
                state.arranging?.let { list ->
                    ArrangeHome(list, arrange)
                    return@Column
                }
                val context = data.value.copy(navigate = onNavigate)
                val pull = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
                androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = onRefresh,
                    state = pull,
                    indicator = {
                        app.centsible.core.designsystem.component.DialPullIndicator(pull, state.refreshing, Modifier.align(androidx.compose.ui.Alignment.TopCenter))
                    },
                ) {
                    LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.widgets, key = { it.id }) { Box(Modifier.animateItem()) { it.Content(context) } }
                        item(key = "arrange") {
                            TextButton(onClick = arrange.start, modifier = Modifier.fillMaxWidth()) { Text("Arrange Home") }
                        }
                    }
                }
            }
        }
    }
}

data class ArrangeActions(
    val start: () -> Unit = {},
    val move: (Int, Int) -> Unit = { _, _ -> },
    val toggle: (Int) -> Unit = {},
    val reset: () -> Unit = {},
    val done: () -> Unit = {},
)

/** Arranging Home: show or hide each card, and move it up or down. Saved per person. */
@Composable
private fun ArrangeHome(list: List<Pair<DashboardWidget, Boolean>>, actions: ArrangeActions) {
    val colors = CentsibleTheme.colors
    val haptics = app.centsible.core.designsystem.motion.rememberHaptics()
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Arrange Home", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = actions.reset) { Text("Reset") }
                androidx.compose.material3.Button(onClick = { haptics.confirm(); actions.done() }) { Text("Done") }
            }
            Text("Just for you: others in the household keep their own Home.", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
        item {
            app.centsible.core.designsystem.component.CentsibleCard(contentPadding = PaddingValues(0.dp)) {
                list.forEachIndexed { i, (w, on) ->
                    if (i > 0) androidx.compose.material3.HorizontalDivider(color = colors.border)
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(w.title, style = MaterialTheme.typography.bodyLarge, color = if (on) colors.textPrimary else colors.textTertiary)
                            if (w.alwaysAvailable) Text("Shows only when there's something new", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
                        }
                        androidx.compose.material3.IconButton(onClick = { haptics.tick(); actions.move(i, -1) }, enabled = i > 0) {
                            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.KeyboardArrowUp, contentDescription = "Move ${w.title} up")
                        }
                        androidx.compose.material3.IconButton(onClick = { haptics.tick(); actions.move(i, 1) }, enabled = i < list.lastIndex) {
                            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.KeyboardArrowDown, contentDescription = "Move ${w.title} down")
                        }
                        androidx.compose.material3.Switch(
                            checked = on,
                            onCheckedChange = { actions.toggle(i) },
                            enabled = !w.alwaysAvailable,
                            modifier = Modifier.semantics { contentDescription = "Show ${w.title}" },
                        )
                    }
                }
            }
        }
    }
}

