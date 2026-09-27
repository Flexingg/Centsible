package app.centsible.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.centsible.core.designsystem.component.CentsibleCard
import app.centsible.core.designsystem.component.LoadingState
import app.centsible.core.designsystem.component.MessageState
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.domain.userMessage

@Composable
fun BudgetPickerRoute(viewModel: BudgetPickerViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(CentsibleTheme.colors.canvas).statusBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Choose a budget", style = MaterialTheme.typography.headlineMedium)
        val result = state
        when {
            result == null -> LoadingState()
            result.isFailure -> MessageState("Couldn't list budgets", result.exceptionOrNull()!!.userMessage(), actionLabel = "Try again", onAction = { viewModel.load() })
            result.getOrThrow().isEmpty() -> MessageState(
                "No budgets shared with you",
                "Ask the household owner to give you access, then try again.",
                emoji = "🗂️",
                actionLabel = "Try again",
                onAction = { viewModel.load() },
            )
            else -> result.getOrThrow().forEach { b ->
                CentsibleCard(onClick = { viewModel.select(b.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(b.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (b.encrypted) Text("🔒", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        TextButton(onClick = { viewModel.signOut() }) { Text("Use a different bridge") }
    }
}
