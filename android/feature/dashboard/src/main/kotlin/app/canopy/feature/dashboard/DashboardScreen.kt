package app.canopy.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import app.canopy.core.designsystem.component.Loadable
import app.canopy.core.designsystem.component.LoadingState
import app.canopy.core.designsystem.component.MessageState
import app.canopy.core.designsystem.theme.CanopyTheme
import app.canopy.core.extensions.Destination
import java.time.LocalTime

@Composable
fun DashboardRoute(onNavigate: (Destination) -> Unit, viewModel: DashboardViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DashboardScreen(state, onNavigate, onRetry = { viewModel.refresh() })
}

@Composable
fun DashboardScreen(state: DashboardUiState, onNavigate: (Destination) -> Unit, onRetry: () -> Unit, now: LocalTime = LocalTime.now()) {
    val colors = CanopyTheme.colors
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
                val context = data.value.copy(navigate = onNavigate)
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.widgets, key = { it.id }) { it.Content(context) }
                }
            }
        }
    }
}
