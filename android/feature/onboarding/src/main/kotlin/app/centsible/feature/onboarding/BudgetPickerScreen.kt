package app.centsible.feature.onboarding

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.setValue
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
    val owner by viewModel.isOwner.collectAsStateWithLifecycle()
    val creating by viewModel.isCreating.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    BudgetPickerScreen(state, owner, creating, error, onRetry = { viewModel.load() }, onSelect = { viewModel.select(it) }, onCreate = { viewModel.create(it) }, onSignOut = { viewModel.signOut() })
}

@Composable
fun BudgetPickerScreen(
    state: Result<List<app.centsible.core.model.Budget>>?,
    isOwner: Boolean,
    creating: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onSelect: (app.centsible.core.model.BudgetId) -> Unit,
    onCreate: (String) -> Unit,
    onSignOut: () -> Unit,
) {
    val colors = CentsibleTheme.colors
    Column(Modifier.fillMaxSize().background(colors.canvas).statusBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val budgets = state?.getOrNull()
        Text(if (budgets?.isEmpty() == true && isOwner) "Create your budget" else "Choose a budget", style = MaterialTheme.typography.headlineMedium)
        when {
            state == null -> LoadingState()
            state.isFailure -> MessageState("Couldn't list budgets", state.exceptionOrNull()!!.userMessage(), actionLabel = "Try again", onAction = onRetry)
            budgets!!.isEmpty() && isOwner -> CreateBudgetCard(creating, error, onCreate)
            budgets.isEmpty() -> MessageState(
                "No budgets shared with you",
                "Ask the household owner to give you access, then try again.",
                emoji = "🗂️",
                actionLabel = "Try again",
                onAction = onRetry,
            )
            else -> budgets.forEach { b ->
                CentsibleCard(onClick = { onSelect(b.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(b.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (b.encrypted) Text("🔒", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        TextButton(onClick = onSignOut) { Text("Use a different bridge") }
    }
}

@Composable
private fun CreateBudgetCard(creating: Boolean, error: String?, onCreate: (String) -> Unit) {
    val colors = CentsibleTheme.colors
    var name by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("Household") }
    CentsibleCard {
        Text("Your Actual server has no budgets yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Start an empty envelope budget. Next, add your accounts and categories, or import a statement from an account's page.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text("Budget name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.negative, modifier = Modifier.padding(top = 8.dp)) }
        androidx.compose.material3.Button(onClick = { onCreate(name) }, enabled = name.isNotBlank() && !creating, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text(if (creating) "Creating…" else "Create budget")
        }
    }
}
