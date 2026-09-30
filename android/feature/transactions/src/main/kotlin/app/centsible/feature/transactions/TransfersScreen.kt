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
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import app.centsible.core.model.Transaction
import app.centsible.core.model.TransferPair
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

data class TransfersData(val pairs: List<TransferPair>, val auto: Boolean, val accountNames: Map<String, String>)

data class TransfersUiState(
    val data: Loadable<TransfersData> = Loadable.Loading,
    val canEdit: Boolean = true,
    val message: String? = null,
)

/**
 * Credit card payments (and other moves between accounts) arrive as two unrelated
 * transactions: money out of checking, money into the card. Linking them makes one
 * transfer, so neither counts as spending or income.
 */
@HiltViewModel
class TransfersViewModel @Inject constructor(
    private val tools: TransactionTools,
    private val engine: BudgetEngine,
    private val selectedBudget: SelectedBudget,
    private val sessions: SessionStore,
) : ViewModel() {
    private val state = MutableStateFlow(TransfersUiState())
    val uiState: StateFlow<TransfersUiState> = state.asStateFlow()

    init {
        load()
    }

    fun load() = viewModelScope.launch {
        runCatching {
            coroutineScope {
                val b = selectedBudget()
                val matches = async { tools.transferMatches(b) }
                val accounts = async { engine.accounts(b) }
                val m = matches.await()
                TransfersData(m.pairs, m.auto, accounts.await().associate { it.id.raw to it.name })
            }
        }
            .onSuccess { d -> state.update { it.copy(data = Loadable.Ready(d), canEdit = sessions.current()?.member?.role?.canWrite != false) } }
            .onFailure { e -> state.update { it.copy(data = Loadable.Failed(e.userMessage())) } }
    }

    fun link(pair: TransferPair) {
        drop(listOf(pair))
        viewModelScope.launch {
            runCatching { tools.linkTransfer(selectedBudget(), pair.from.id, pair.to.id) }
                .onFailure { e -> state.update { it.copy(message = e.userMessage()) }; load() }
        }
    }

    fun linkAllSure() {
        val sure = state.value.data.valueOrNull?.pairs.orEmpty().filter { it.confident }
        if (sure.isEmpty()) return
        drop(sure)
        viewModelScope.launch {
            var done = 0
            runCatching { sure.forEach { tools.linkTransfer(selectedBudget(), it.from.id, it.to.id); done++ } }
                .onSuccess { state.update { it.copy(message = "Linked $done transfer${if (done == 1) "" else "s"}") } }
                .onFailure { e -> state.update { it.copy(message = e.userMessage()) }; load() }
        }
    }

    fun dismiss(pair: TransferPair) {
        drop(listOf(pair))
        viewModelScope.launch {
            runCatching { tools.dismissTransfer(selectedBudget(), pair.from.id, pair.to.id) }
                .onFailure { e -> state.update { it.copy(message = e.userMessage()) }; load() }
        }
    }

    fun setAuto(auto: Boolean) {
        update { it.copy(auto = auto) }
        viewModelScope.launch {
            runCatching { tools.setAutoTransfers(selectedBudget(), auto) }
                .onSuccess { a -> update { it.copy(auto = a) } }
                .onFailure { e -> state.update { it.copy(message = e.userMessage()) }; update { it.copy(auto = !auto) } }
        }
    }

    fun messageShown() = state.update { it.copy(message = null) }

    private fun drop(pairs: List<TransferPair>) = update { d -> d.copy(pairs = d.pairs - pairs.toSet()) }

    private fun update(f: (TransfersData) -> TransfersData) = state.update { s ->
        val d = s.data.valueOrNull ?: return@update s
        s.copy(data = Loadable.Ready(f(d)))
    }
}

data class TransfersActions(
    val back: () -> Unit = {},
    val retry: () -> Unit = {},
    val link: (TransferPair) -> Unit = {},
    val dismiss: (TransferPair) -> Unit = {},
    val linkAllSure: () -> Unit = {},
    val setAuto: (Boolean) -> Unit = {},
    val messageShown: () -> Unit = {},
)

@Composable
fun TransfersRoute(onBack: () -> Unit, viewModel: TransfersViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TransfersScreen(
        state,
        TransfersActions(
            back = onBack,
            retry = { viewModel.load() },
            link = viewModel::link,
            dismiss = viewModel::dismiss,
            linkAllSure = viewModel::linkAllSure,
            setAuto = viewModel::setAuto,
            messageShown = viewModel::messageShown,
        ),
    )
}

@Composable
fun TransfersScreen(state: TransfersUiState, actions: TransfersActions) {
    val colors = CentsibleTheme.colors
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); actions.messageShown() } }
    Scaffold(
        containerColor = colors.canvas,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("Match transfers", style = MaterialTheme.typography.headlineSmall)
            }
        },
    ) { padding ->
        when (val d = state.data) {
            Loadable.Loading -> LoadingState(Modifier.padding(padding))
            is Loadable.Failed -> MessageState("Couldn't look for transfers", d.message, emoji = "🔗", actionLabel = "Try again", onAction = actions.retry, modifier = Modifier.padding(padding))
            is Loadable.Ready -> {
                val sure = d.value.pairs.count { it.confident }
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(
                            "A card payment shows up twice: leaving checking and arriving on the card. Linking the two makes one transfer, so it isn't counted as spending or income.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                    item {
                        CentsibleCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Link automatically", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "After each bank sync, the bridge links pairs with no other match on either side.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textTertiary,
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Switch(
                                    checked = d.value.auto,
                                    onCheckedChange = actions.setAuto,
                                    enabled = state.canEdit,
                                    modifier = Modifier.semantics { contentDescription = "Link automatically" },
                                )
                            }
                        }
                    }
                    if (d.value.pairs.isEmpty()) {
                        item { MessageState("Nothing to match", "Payments between your accounts in the last four months are all linked.", emoji = "🔗", modifier = Modifier.padding(top = 24.dp)) }
                    } else if (sure > 1 && state.canEdit) {
                        item {
                            Button(onClick = actions.linkAllSure, modifier = Modifier.fillMaxWidth()) { Text("Link all $sure sure matches") }
                        }
                    }
                    items(d.value.pairs, key = { it.from.id.raw + it.to.id.raw }) { p ->
                        PairCard(p, d.value.accountNames, state.canEdit, actions, Modifier.staggeredEntrance(d.value.pairs.indexOf(p)))
                    }
                }
            }
        }
    }
}

@Composable
private fun PairCard(p: TransferPair, accounts: Map<String, String>, canEdit: Boolean, actions: TransfersActions, modifier: Modifier = Modifier) {
    val colors = CentsibleTheme.colors
    CentsibleCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MoneyText(p.to.amount, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text(
                when (p.days) { 0 -> "Same day"; 1 -> "1 day apart"; else -> "${p.days} days apart" } + if (p.confident) "" else " · other matches",
                style = MaterialTheme.typography.labelMedium,
                color = if (p.confident) colors.textTertiary else colors.warning,
            )
        }
        Spacer(Modifier.padding(top = 10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Side(p.from, accounts, Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "to", tint = colors.textTertiary, modifier = Modifier.padding(horizontal = 8.dp))
            Side(p.to, accounts, Modifier.weight(1f))
        }
        if (canEdit) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { actions.dismiss(p) }, modifier = Modifier.weight(1f)) { Text("Not a transfer") }
                Button(onClick = { actions.link(p) }, modifier = Modifier.weight(1f)) { Text("Link") }
            }
        }
    }
}

@Composable
private fun Side(t: Transaction, accounts: Map<String, String>, modifier: Modifier = Modifier) {
    val colors = CentsibleTheme.colors
    Column(modifier.background(colors.cardMuted, MaterialTheme.shapes.medium).padding(10.dp)) {
        Text(accounts[t.accountId.raw] ?: "Account", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(t.payeeName ?: "No payee", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(shortDate(t.date), style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
    }
}

private fun shortDate(iso: String) = runCatching { LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())) }.getOrDefault(iso)
