package app.centsible.feature.planning

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import app.centsible.core.designsystem.component.MerchantAvatar
import app.centsible.core.designsystem.component.MessageState
import app.centsible.core.designsystem.component.PickerItem
import app.centsible.core.designsystem.component.PickerSheet
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.domain.BudgetChanges
import app.centsible.core.domain.PlanningGateway
import app.centsible.core.domain.SelectedBudget
import app.centsible.core.domain.SessionStore
import app.centsible.core.domain.userMessage
import app.centsible.core.model.PayeeStat
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MerchantsUiState(val payees: Loadable<List<PayeeStat>> = Loadable.Loading, val canEdit: Boolean = false, val message: String? = null)

@HiltViewModel
class MerchantsViewModel @Inject constructor(
    private val planning: PlanningGateway,
    private val selectedBudget: SelectedBudget,
    private val sessions: SessionStore,
    changes: BudgetChanges,
) : ViewModel() {
    private val state = MutableStateFlow(MerchantsUiState())
    val uiState: StateFlow<MerchantsUiState> = state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch { changes.changes.collect { refresh() } }
    }

    fun refresh() = viewModelScope.launch {
        val canEdit = sessions.current()?.member?.role?.canWrite == true
        runCatching { planning.payeeStats(selectedBudget()).filter { it.transferAccountId == null } }
            .onSuccess { p -> state.update { it.copy(payees = Loadable.Ready(p), canEdit = canEdit) } }
            .onFailure { e -> state.update { it.copy(payees = Loadable.Failed(e.userMessage())) } }
    }

    fun rename(p: PayeeStat, name: String) = act("Renamed") { planning.renamePayee(selectedBudget(), p.id, name.trim()) }
    fun merge(into: PayeeStat, other: PayeeStat) = act("Merged ${other.name} into ${into.name}") { planning.mergePayees(selectedBudget(), into.id, listOf(other.id)) }
    fun delete(p: PayeeStat) = act("Deleted ${p.name}") { planning.deletePayee(selectedBudget(), p.id) }
    fun messageShown() = state.update { it.copy(message = null) }

    private fun act(success: String, block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }
            .onSuccess { state.update { it.copy(message = success) } }
            .onFailure { e -> state.update { it.copy(message = e.userMessage()) } }
    }
}

@Composable
fun MerchantsRoute(onBack: () -> Unit, onOpenTransactions: (app.centsible.core.extensions.Destination.TransactionsFor) -> Unit = {}, viewModel: MerchantsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    MerchantsScreen(
        state, onBack,
        onRename = { p, n -> viewModel.rename(p, n) },
        onMerge = { a, b -> viewModel.merge(a, b) },
        onDelete = { viewModel.delete(it) },
        onRetry = { viewModel.refresh() },
        onMessageShown = viewModel::messageShown,
        onOpenTransactions = onOpenTransactions,
    )
}

@Composable
fun MerchantsScreen(
    state: MerchantsUiState,
    onBack: () -> Unit,
    onRename: (PayeeStat, String) -> Unit = { _, _ -> },
    onMerge: (PayeeStat, PayeeStat) -> Unit = { _, _ -> },
    onDelete: (PayeeStat) -> Unit = {},
    onRetry: () -> Unit = {},
    onMessageShown: () -> Unit = {},
    onOpenTransactions: (app.centsible.core.extensions.Destination.TransactionsFor) -> Unit = {},
) {
    val colors = CentsibleTheme.colors
    val snackbar = remember { SnackbarHostState() }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<PayeeStat?>(null) }
    var merging by remember { mutableStateOf<PayeeStat?>(null) }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); onMessageShown() } }
    Scaffold(
        containerColor = colors.canvas,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("Merchants", style = MaterialTheme.typography.titleLarge)
            }
        },
    ) { padding ->
        when (val d = state.payees) {
            Loadable.Loading -> LoadingState(Modifier.padding(padding))
            is Loadable.Failed -> MessageState("Couldn't load merchants", d.message, actionLabel = "Try again", onAction = onRetry, modifier = Modifier.padding(padding))
            is Loadable.Ready -> Column(Modifier.fillMaxSize().padding(padding)) {
                OutlinedTextField(
                    query, { query = it },
                    placeholder = { Text("Search merchants") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = colors.card, focusedContainerColor = colors.card),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                val shown = d.value.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
                LazyColumn(contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp)) {
                    item {
                        CentsibleCard(contentPadding = PaddingValues(0.dp)) {
                            shown.forEachIndexed { i, p ->
                                if (i > 0) HorizontalDivider(Modifier.padding(start = 64.dp), color = colors.border)
                                Row(
                                    Modifier.fillMaxWidth().clickable { if (state.canEdit) selected = p else onOpenTransactions(app.centsible.core.extensions.Destination.TransactionsFor(p.name, payeeId = p.id)) }.padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    MerchantAvatar(p.name)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(p.name, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            buildString {
                                                append(if (p.transactionCount == 1) "1 transaction" else "${p.transactionCount} transactions")
                                                if (p.ruleCount > 0) append(" · ${p.ruleCount} rule" + if (p.ruleCount > 1) "s" else "")
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = colors.textTertiary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { p ->
        var name by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(p.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    if (p.transactionCount > 0) {
                        TextButton(onClick = { selected = null; onOpenTransactions(app.centsible.core.extensions.Destination.TransactionsFor(p.name, payeeId = p.id)) }) {
                            Text(if (p.transactionCount == 1) "See its transaction" else "See its ${p.transactionCount} transactions")
                        }
                    }
                    TextButton(onClick = { merging = p; selected = null }) { Text("Merge another merchant into this one…") }
                    if (p.transactionCount == 0) TextButton(onClick = { onDelete(p); selected = null }) { Text("Delete (unused)", color = colors.negative) }
                }
            },
            confirmButton = { TextButton(onClick = { onRename(p, name); selected = null }, enabled = name.isNotBlank() && name.trim() != p.name) { Text("Save") } },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Close") } },
        )
    }
    merging?.let { target ->
        val others = state.payees.valueOrNull.orEmpty().filter { it.id != target.id }
        PickerSheet(
            title = "Merge into ${target.name}",
            items = others.map { PickerItem(it.id.raw, it.name, supporting = "${it.transactionCount} transactions") },
            selectedKey = null,
            onPick = { item -> others.firstOrNull { it.id.raw == item.key }?.let { onMerge(target, it) }; merging = null },
            onDismiss = { merging = null },
        )
    }
}
