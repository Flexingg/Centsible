package app.centsible.feature.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.centsible.core.designsystem.component.LoadingState
import app.centsible.core.designsystem.component.MoneyText
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.domain.BudgetEngine
import app.centsible.core.domain.SelectedBudget
import app.centsible.core.domain.TransactionTools
import app.centsible.core.domain.userMessage
import app.centsible.core.model.Transaction
import app.centsible.core.model.TransferCandidate
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LinkTransferState(
    val query: String = "",
    val candidates: List<TransferCandidate>? = null,
    val accountNames: Map<String, String> = emptyMap(),
    val linking: Boolean = false,
    val error: String? = null,
)

/** Finds and links the other side of a payment between two accounts. */
@HiltViewModel
class LinkTransferViewModel @Inject constructor(
    private val tools: TransactionTools,
    private val engine: BudgetEngine,
    private val selectedBudget: SelectedBudget,
) : ViewModel() {
    private val state = MutableStateFlow(LinkTransferState())
    val uiState: StateFlow<LinkTransferState> = state.asStateFlow()
    private var of: Transaction? = null
    private var searchJob: Job? = null

    fun start(t: Transaction) {
        if (of?.id == t.id) return
        of = t
        state.value = LinkTransferState()
        viewModelScope.launch {
            runCatching { engine.accounts(selectedBudget()) }.onSuccess { a -> state.update { it.copy(accountNames = a.associate { x -> x.id.raw to x.name }) } }
        }
        search("", 0)
    }

    /** Waits for typing to pause before asking the bridge. */
    fun search(q: String, wait: Long = 250) {
        val t = of ?: return
        state.update { it.copy(query = q) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(wait)
            runCatching { tools.transferCandidates(selectedBudget(), t.id, q.trim().ifEmpty { null }) }
                .onSuccess { c -> state.update { it.copy(candidates = c, error = null) } }
                .onFailure { e -> state.update { it.copy(candidates = emptyList(), error = e.userMessage()) } }
        }
    }

    /** Links [t] and the chosen one; the money-out side goes first, as Actual expects. */
    fun link(c: TransferCandidate, onDone: (TransferCandidate, String) -> Unit) {
        val t = of ?: return
        val (from, to) = if (t.amount.isNegative) t to c.transaction else c.transaction to t
        state.update { it.copy(linking = true) }
        viewModelScope.launch {
            runCatching { tools.linkTransfer(selectedBudget(), from.id, to.id) }
                .onSuccess {
                    state.update { it.copy(linking = false) }
                    onDone(c, state.value.accountNames[c.transaction.accountId.raw] ?: "the other account")
                }
                .onFailure { e -> state.update { it.copy(linking = false, error = e.userMessage()) } }
        }
    }
}

/**
 * "This is a payment between my accounts": pick the other side. The same amount going the
 * other way is listed first; typing searches merchant, notes or an amount.
 */
@Composable
fun LinkTransferSheet(
    transaction: Transaction,
    onLinked: (other: TransferCandidate, accountName: String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: LinkTransferViewModel = hiltViewModel(key = "link-transfer"),
) {
    LaunchedEffect(transaction.id) { viewModel.start(transaction) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LinkTransferContent(transaction, state, onQuery = { viewModel.search(it) }, onPick = { viewModel.link(it, onLinked) }, onDismiss = onDismiss)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkTransferContent(
    transaction: Transaction,
    state: LinkTransferState,
    onQuery: (String) -> Unit,
    onPick: (TransferCandidate) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CentsibleTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.card) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text("Link as a transfer", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 4.dp))
            Text(
                "${if (transaction.amount.isNegative) "Paid from" else "Received in"} ${state.accountNames[transaction.accountId.raw] ?: "this account"}: pick the other side, like the card it paid off.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
            Row(
                Modifier.fillMaxWidth().background(colors.cardMuted, RoundedCornerShape(14.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(transaction.payeeName ?: "No payee", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(shortDay(transaction.date), style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
                }
                MoneyText(transaction.amount, style = MaterialTheme.typography.titleMedium, signed = true)
            }
            OutlinedTextField(
                state.query, onQuery,
                placeholder = { Text("Search merchant, notes or amount") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.negative, modifier = Modifier.padding(top = 6.dp)) }
            Box(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 460.dp).padding(top = 8.dp)) {
                val list = state.candidates
                when {
                    list == null -> LoadingState()
                    list.isEmpty() -> Text(
                        if (state.query.isBlank()) "Nothing in your other accounts around then. Try searching." else "Nothing matches “${state.query}”.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(16.dp),
                    )
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(list, key = { it.transaction.id.raw }) { c ->
                            CandidateRow(c, state.accountNames[c.transaction.accountId.raw], enabled = !state.linking, onClick = { onPick(c) })
                        }
                    }
                }
            }
            Spacer(Modifier.padding(bottom = 12.dp))
        }
    }
}

@Composable
private fun CandidateRow(c: TransferCandidate, account: String?, enabled: Boolean, onClick: () -> Unit) {
    val colors = CentsibleTheme.colors
    val t = c.transaction
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClickLabel = "Link", onClick = onClick).padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(account ?: "Account", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(t.payeeName, shortDay(t.date), when (c.days) { 0 -> "same day"; 1 -> "1 day apart"; else -> "${c.days} days apart" }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            MoneyText(t.amount, style = MaterialTheme.typography.bodyLarge, signed = true)
            if (c.exact) Text("Same amount", style = MaterialTheme.typography.labelSmall, color = colors.positive)
        }
    }
}

private fun shortDay(iso: String) = runCatching { LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())) }.getOrDefault(iso)
