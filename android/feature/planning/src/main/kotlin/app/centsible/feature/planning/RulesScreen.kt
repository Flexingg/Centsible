package app.centsible.feature.planning

import androidx.compose.material.icons.rounded.Search
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.centsible.core.designsystem.component.CentsibleCard
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.component.LoadingState
import app.centsible.core.designsystem.component.MessageState
import app.centsible.core.designsystem.component.StatLabel
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.domain.BudgetChanges
import app.centsible.core.domain.BudgetEngine
import app.centsible.core.domain.PlanningGateway
import app.centsible.core.domain.SelectedBudget
import app.centsible.core.domain.SessionStore
import app.centsible.core.domain.userMessage
import app.centsible.core.model.CategoryGroup
import app.centsible.core.model.Feature
import app.centsible.core.model.Payee
import app.centsible.core.model.Rule
import app.centsible.core.model.RuleDraft
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RulesData(val rules: List<Rule>, val names: Describe.Names, val payees: List<Payee>, val groups: List<CategoryGroup>) {
    val visible get() = rules.filter { it.scheduleId == null }
    val scheduleRuleCount get() = rules.count { it.scheduleId != null }
}

data class RulesUiState(val data: Loadable<RulesData> = Loadable.Loading, val canEdit: Boolean = false, val message: String? = null)

@HiltViewModel
class RulesViewModel @Inject constructor(
    private val planning: PlanningGateway,
    private val engine: BudgetEngine,
    private val selectedBudget: SelectedBudget,
    private val sessions: SessionStore,
    changes: BudgetChanges,
) : ViewModel() {
    private val state = MutableStateFlow(RulesUiState())
    val uiState: StateFlow<RulesUiState> = state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch { changes.changes.collect { refresh() } }
    }

    fun refresh() = viewModelScope.launch {
        runCatching {
            val b = selectedBudget()
            val rules = async { planning.rules(b) }
            val payees = async { engine.payees(b) }
            val groups = async { engine.categoryGroups(b) }
            val accounts = async { engine.accounts(b) }
            val caps = async { engine.capabilities() }
            val p = payees.await()
            val g = groups.await()
            val names = Describe.Names(
                p.associate { it.id.raw to it.name },
                g.flatMap { it.categories }.associate { it.id.raw to it.name },
                accounts.await().associate { it.id.raw to it.name },
            )
            RulesData(rules.await(), names, p, g) to (sessions.current()?.member?.role?.canWrite == true && caps.await().has(Feature.RulesWrite))
        }
            .onSuccess { (d, canEdit) -> state.update { it.copy(data = Loadable.Ready(d), canEdit = canEdit) } }
            .onFailure { e -> state.update { it.copy(data = Loadable.Failed(e.userMessage())) } }
    }

    fun save(id: String?, draft: RuleDraft) = act("Rule saved") {
        if (id == null) planning.createRule(selectedBudget(), draft) else planning.updateRule(selectedBudget(), id, draft)
    }

    fun delete(id: String) = act("Rule deleted") { planning.deleteRule(selectedBudget(), id) }
    fun messageShown() = state.update { it.copy(message = null) }

    private fun act(success: String, block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }
            .onSuccess { state.update { it.copy(message = success) } }
            .onFailure { e -> state.update { it.copy(message = e.userMessage()) } }
    }
}

@Composable
fun RulesRoute(onBack: () -> Unit, onOpenRule: (String?) -> Unit = {}, viewModel: RulesViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    RulesScreen(state, onBack, onOpen = onOpenRule, onRetry = { viewModel.refresh() }, onMessageShown = viewModel::messageShown)
}

@Composable
fun RulesScreen(
    state: RulesUiState,
    onBack: () -> Unit,
    /** Opens the rule editor; null starts a new rule. */
    onOpen: (String?) -> Unit = {},
    onRetry: () -> Unit = {},
    onMessageShown: () -> Unit = {},
) {
    val colors = CentsibleTheme.colors
    val snackbar = remember { SnackbarHostState() }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); onMessageShown() } }
    Scaffold(
        containerColor = colors.canvas,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("Rules", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (state.canEdit) TextButton(onClick = { onOpen(null) }) { Text("Add") }
            }
        },
    ) { padding ->
        when (val d = state.data) {
            Loadable.Loading -> LoadingState(Modifier.padding(padding))
            is Loadable.Failed -> MessageState("Couldn't load rules", d.message, actionLabel = "Try again", onAction = onRetry, modifier = Modifier.padding(padding))
            is Loadable.Ready -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        "Rules run on new and imported transactions to clean up merchants and set categories automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                if (d.value.visible.size > 5) {
                    item {
                        androidx.compose.material3.OutlinedTextField(
                            query, { query = it },
                            placeholder = { Text("Search rules") },
                            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (d.value.visible.isEmpty()) {
                    item { Text("No rules yet. Tip: when you pick a category for a merchant, tick \"Always use this category\".", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, modifier = Modifier.padding(4.dp)) }
                }
                val shown = d.value.visible.map { it to Describe.rule(it, d.value.names) }
                    .filter { (_, text) -> query.isBlank() || (text.first + " " + text.second).contains(query.trim(), ignoreCase = true) }
                items(shown, key = { it.first.id }) { (rule, text) ->
                    val (ifText, thenText) = text
                    CentsibleCard(onClick = { onOpen(rule.id) }) {
                        StatLabel(if (rule.stage == "pre") "Runs first" else if (rule.stage == "post") "Runs last" else "Rule")
                        Text(ifText, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
                        Text("→ $thenText", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                if (d.value.scheduleRuleCount > 0) {
                    item {
                        Text(
                            "${d.value.scheduleRuleCount} more rule(s) belong to recurring schedules; edit those under Recurring.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textTertiary,
                            modifier = Modifier.padding(4.dp),
                        )
                    }
                }
            }
        }
    }

}
