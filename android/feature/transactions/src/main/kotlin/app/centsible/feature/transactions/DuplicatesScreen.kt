package app.centsible.feature.transactions

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import app.centsible.core.designsystem.component.MessageState
import app.centsible.core.designsystem.component.MoneyText
import app.centsible.core.designsystem.motion.staggeredEntrance
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.domain.BudgetEngine
import app.centsible.core.domain.SelectedBudget
import app.centsible.core.domain.SessionStore
import app.centsible.core.domain.TransactionTools
import app.centsible.core.domain.userMessage
import app.centsible.core.model.DuplicatePair
import app.centsible.core.model.Transaction
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DuplicatesData(val pairs: List<DuplicatePair>, val accountNames: Map<String, String>, val categoryNames: Map<String, String>)

data class DuplicatesUiState(
    val data: Loadable<DuplicatesData> = Loadable.Loading,
    val canEdit: Boolean = true,
    val message: String? = null,
)

/**
 * The same purchase twice in one account, usually after a bank connection was reconnected
 * or moved to another provider. Merging (Actual's own) keeps the bank-imported one and
 * carries over the category and notes from either.
 */
@HiltViewModel
class DuplicatesViewModel @Inject constructor(
    private val tools: TransactionTools,
    private val engine: BudgetEngine,
    private val selectedBudget: SelectedBudget,
    private val sessions: SessionStore,
) : ViewModel() {
    private val state = MutableStateFlow(DuplicatesUiState())
    val uiState: StateFlow<DuplicatesUiState> = state.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        runCatching {
            coroutineScope {
                val b = selectedBudget()
                val pairs = async { tools.duplicates(b) }
                val accounts = async { engine.accounts(b) }
                val groups = async { engine.categoryGroups(b) }
                DuplicatesData(
                    pairs.await(),
                    accounts.await().associate { it.id.raw to it.name },
                    groups.await().flatMap { it.categories }.associate { it.id.raw to it.name },
                )
            }
        }
            .onSuccess { d -> state.update { it.copy(data = Loadable.Ready(d), canEdit = sessions.current()?.member?.role?.canWrite != false) } }
            .onFailure { e -> state.update { it.copy(data = Loadable.Failed(e.userMessage())) } }
    }

    fun merge(pair: DuplicatePair) {
        drop(pair)
        viewModelScope.launch {
            runCatching { tools.merge(selectedBudget(), pair.a.id, pair.b.id) }
                .onSuccess { state.update { it.copy(message = "Merged into one") } }
                .onFailure { e -> state.update { it.copy(message = e.userMessage()) }; load() }
        }
    }

    fun dismiss(pair: DuplicatePair) {
        drop(pair)
        viewModelScope.launch {
            runCatching { tools.dismissDuplicate(selectedBudget(), pair.a.id, pair.b.id) }
                .onFailure { e -> state.update { it.copy(message = e.userMessage()) }; load() }
        }
    }

    fun messageShown() = state.update { it.copy(message = null) }

    private fun drop(pair: DuplicatePair) = state.update { s ->
        val d = (s.data as? Loadable.Ready)?.value ?: return@update s
        s.copy(data = Loadable.Ready(d.copy(pairs = d.pairs - pair)))
    }
}

data class DuplicatesActions(
    val back: () -> Unit = {},
    val retry: () -> Unit = {},
    val merge: (DuplicatePair) -> Unit = {},
    val dismiss: (DuplicatePair) -> Unit = {},
    val messageShown: () -> Unit = {},
)

@Composable
fun DuplicatesRoute(onBack: () -> Unit, viewModel: DuplicatesViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DuplicatesScreen(state, DuplicatesActions(onBack, { viewModel.load() }, viewModel::merge, viewModel::dismiss, viewModel::messageShown))
}

@Composable
fun DuplicatesScreen(state: DuplicatesUiState, actions: DuplicatesActions) {
    val colors = CentsibleTheme.colors
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); actions.messageShown() } }
    Scaffold(
        containerColor = colors.canvas,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("Duplicates", style = MaterialTheme.typography.headlineSmall)
            }
        },
    ) { padding ->
        when (val d = state.data) {
            Loadable.Loading -> LoadingState(Modifier.padding(padding))
            is Loadable.Failed -> MessageState("Couldn't look for duplicates", d.message, emoji = "🧾", actionLabel = "Try again", onAction = actions.retry, modifier = Modifier.padding(padding))
            is Loadable.Ready -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        "When a bank connection is reconnected or moved to another provider, the same purchase can come in twice. Merging keeps the bank's copy, with the category and notes from either.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                if (d.value.pairs.isEmpty()) {
                    item { MessageState("No duplicates", "Nothing in the last four months looks doubled up.", emoji = "✅", modifier = Modifier.padding(top = 24.dp)) }
                }
                items(d.value.pairs, key = { it.a.id.raw + it.b.id.raw }) { p ->
                    DuplicateCard(p, d.value, state.canEdit, actions, Modifier.staggeredEntrance(d.value.pairs.indexOf(p)))
                }
            }
        }
    }
}

@Composable
private fun DuplicateCard(p: DuplicatePair, data: DuplicatesData, canEdit: Boolean, actions: DuplicatesActions, modifier: Modifier = Modifier) {
    val colors = CentsibleTheme.colors
    CentsibleCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                MoneyText(p.a.amount, style = MaterialTheme.typography.titleLarge)
                Text(data.accountNames[p.a.accountId.raw] ?: "Account", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
            Text(when (p.days) { 0 -> "Same day"; 1 -> "1 day apart"; else -> "${p.days} days apart" }, style = MaterialTheme.typography.labelMedium, color = colors.textTertiary)
        }
        Spacer(Modifier.padding(top = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Copy(p.a, data, Modifier.weight(1f))
            Copy(p.b, data, Modifier.weight(1f))
        }
        if (canEdit) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { actions.dismiss(p) }, modifier = Modifier.weight(1f)) { Text("Not a duplicate") }
                Button(onClick = { actions.merge(p) }, modifier = Modifier.weight(1f)) { Text("Merge") }
            }
        }
    }
}

@Composable
private fun Copy(t: Transaction, data: DuplicatesData, modifier: Modifier = Modifier) {
    val colors = CentsibleTheme.colors
    Column(modifier.background(colors.cardMuted, MaterialTheme.shapes.medium).padding(10.dp)) {
        Text(t.payeeName ?: "No payee", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(t.categoryId?.let { data.categoryNames[it.raw] } ?: "Uncategorized", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row {
            Text(dupDate(t.date), style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
            if (!t.notes.isNullOrBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(t.notes!!, style = MaterialTheme.typography.labelSmall, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun dupDate(iso: String) = runCatching { LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())) }.getOrDefault(iso)
