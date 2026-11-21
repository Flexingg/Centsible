package app.centsible.feature.transactions

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import app.centsible.core.designsystem.component.DialSpinner
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.component.LoadingState
import app.centsible.core.designsystem.component.MessageState
import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.designsystem.component.MoneyText
import app.centsible.core.designsystem.component.PickerItem
import app.centsible.core.designsystem.component.PickerSheet
import app.centsible.core.designsystem.component.StatLabel
import app.centsible.core.designsystem.motion.staggeredEntrance
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.domain.BudgetEngine
import app.centsible.core.domain.SelectedBudget
import app.centsible.core.domain.SessionStore
import app.centsible.core.domain.TransactionTools
import app.centsible.core.domain.userMessage
import app.centsible.core.model.Account
import app.centsible.core.model.AccountId
import app.centsible.core.model.Money
import app.centsible.core.model.Transaction
import app.centsible.core.model.TransactionId
import app.centsible.core.model.TransferPair
import app.centsible.core.model.TransferSearchOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TransferSearchMode { AllAccounts, TwoAccounts }

data class TransfersData(
    val pairs: List<TransferPair>,
    val auto: Boolean,
    val accountNames: Map<String, String>,
    val accounts: List<Account> = emptyList(),
)

data class TransfersUiState(
    val data: Loadable<TransfersData> = Loadable.Loading,
    val canEdit: Boolean = true,
    val message: String? = null,
    val showFilters: Boolean = false,
    val mode: TransferSearchMode = TransferSearchMode.AllAccounts,
    val allTime: Boolean = false,
    val fromAccountId: AccountId? = null,
    val toAccountId: AccountId? = null,
    val windowDays: Int = 21,
    val fuzzy: Boolean = true,
    val searching: Boolean = false,
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
    private val state = MutableStateFlow(TransfersUiState(showFilters = true))
    val uiState: StateFlow<TransfersUiState> = state.asStateFlow()
    private var searchJob: Job? = null

    init {
        load()
    }

    private fun currentOptions(s: TransfersUiState): TransferSearchOptions = when (s.mode) {
        TransferSearchMode.AllAccounts -> TransferSearchOptions(allTime = s.allTime)
        TransferSearchMode.TwoAccounts -> TransferSearchOptions(
            allTime = s.allTime,
            fromAccountId = s.fromAccountId,
            toAccountId = s.toAccountId,
            windowDays = s.windowDays,
            fuzzy = s.fuzzy,
        )
    }

    fun load() = viewModelScope.launch {
        runCatching {
            coroutineScope {
                val b = selectedBudget()
                val matches = async { tools.transferMatches(b, currentOptions(state.value)) }
                val accounts = async { engine.accounts(b) }
                val m = matches.await()
                val accts = accounts.await()
                TransfersData(m.pairs, m.auto, accts.associate { it.id.raw to it.name }, accts)
            }
        }
            .onSuccess { d -> state.update { it.copy(data = Loadable.Ready(d), canEdit = sessions.current()?.member?.role?.canWrite != false, searching = false) } }
            .onFailure { e -> state.update { it.copy(data = Loadable.Failed(e.userMessage()), searching = false) } }
    }

    private fun refreshMatches() {
        val s = state.value
        if (s.mode == TransferSearchMode.TwoAccounts && (s.fromAccountId == null || s.toAccountId == null || s.fromAccountId == s.toAccountId)) {
            update { it.copy(pairs = emptyList()) }
            return
        }
        searchJob?.cancel()
        state.update { it.copy(searching = true) }
        searchJob = viewModelScope.launch {
            runCatching { tools.transferMatches(selectedBudget(), currentOptions(state.value)) }
                .onSuccess { m ->
                    update { it.copy(pairs = m.pairs, auto = m.auto) }
                    state.update { it.copy(searching = false) }
                }
                .onFailure { e ->
                    state.update { it.copy(searching = false, message = e.userMessage()) }
                }
        }
    }

    fun setMode(mode: TransferSearchMode) {
        state.update { s ->
            if (s.mode == mode) return@update s
            val accounts = s.data.valueOrNull?.accounts.orEmpty()
            val open = accounts.filter { !it.closed }
            val defaultFrom = s.fromAccountId ?: open.getOrNull(0)?.id ?: accounts.getOrNull(0)?.id
            val defaultTo = s.toAccountId ?: open.firstOrNull { it.id != defaultFrom }?.id ?: accounts.firstOrNull { it.id != defaultFrom }?.id
            s.copy(
                mode = mode,
                allTime = if (mode == TransferSearchMode.TwoAccounts) true else s.allTime,
                fromAccountId = if (mode == TransferSearchMode.TwoAccounts) defaultFrom else s.fromAccountId,
                toAccountId = if (mode == TransferSearchMode.TwoAccounts) defaultTo else s.toAccountId,
            )
        }
        refreshMatches()
    }

    fun setAllTime(allTime: Boolean) {
        if (state.value.allTime == allTime) return
        state.update { it.copy(allTime = allTime) }
        refreshMatches()
    }

    fun setFromAccount(id: AccountId) {
        state.update { s ->
            val other = if (s.toAccountId == id) s.fromAccountId else s.toAccountId
            s.copy(fromAccountId = id, toAccountId = other)
        }
        refreshMatches()
    }

    fun setToAccount(id: AccountId) {
        state.update { s ->
            val other = if (s.fromAccountId == id) s.toAccountId else s.fromAccountId
            s.copy(toAccountId = id, fromAccountId = other)
        }
        refreshMatches()
    }

    fun setWindowDays(days: Int) {
        if (state.value.windowDays == days) return
        state.update { it.copy(windowDays = days) }
        refreshMatches()
    }

    fun setFuzzy(fuzzy: Boolean) {
        if (state.value.fuzzy == fuzzy) return
        state.update { it.copy(fuzzy = fuzzy) }
        refreshMatches()
    }

    fun link(pair: TransferPair) {
        if (pair.amountDiff != 0L) return
        drop(listOf(pair))
        viewModelScope.launch {
            runCatching { tools.linkTransfer(selectedBudget(), pair.from.id, pair.to.id) }
                .onFailure { e -> state.update { it.copy(message = e.userMessage()) }; refreshMatches() }
        }
    }

    fun linkAllSure() {
        val sure = state.value.data.valueOrNull?.pairs.orEmpty().filter { it.confident && it.amountDiff == 0L }
        if (sure.isEmpty()) return
        drop(sure)
        viewModelScope.launch {
            var done = 0
            runCatching { sure.forEach { tools.linkTransfer(selectedBudget(), it.from.id, it.to.id); done++ } }
                .onSuccess { state.update { it.copy(message = "Linked $done transfer${if (done == 1) "" else "s"}") } }
                .onFailure { e -> state.update { it.copy(message = e.userMessage()) }; refreshMatches() }
        }
    }

    fun dismiss(pair: TransferPair) {
        drop(listOf(pair))
        viewModelScope.launch {
            runCatching { tools.dismissTransfer(selectedBudget(), pair.from.id, pair.to.id) }
                .onFailure { e -> state.update { it.copy(message = e.userMessage()) }; refreshMatches() }
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
    val setMode: (TransferSearchMode) -> Unit = {},
    val setAllTime: (Boolean) -> Unit = {},
    val setFromAccount: (AccountId) -> Unit = {},
    val setToAccount: (AccountId) -> Unit = {},
    val setWindowDays: (Int) -> Unit = {},
    val setFuzzy: (Boolean) -> Unit = {},
    val openTransaction: (TransactionId) -> Unit = {},
    val messageShown: () -> Unit = {},
)

@Composable
fun TransfersRoute(
    onBack: () -> Unit,
    onOpenTransaction: (TransactionId) -> Unit = {},
    viewModel: TransfersViewModel = hiltViewModel(),
) {
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
            setMode = viewModel::setMode,
            setAllTime = viewModel::setAllTime,
            setFromAccount = viewModel::setFromAccount,
            setToAccount = viewModel::setToAccount,
            setWindowDays = viewModel::setWindowDays,
            setFuzzy = viewModel::setFuzzy,
            openTransaction = onOpenTransaction,
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
                Text("Match transfers", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                if (state.searching) DialSpinner(size = 18.dp, modifier = Modifier.padding(end = 12.dp))
            }
        },
    ) { padding ->
        when (val d = state.data) {
            Loadable.Loading -> LoadingState(Modifier.padding(padding))
            is Loadable.Failed -> MessageState("Couldn't look for transfers", d.message, emoji = "🔗", actionLabel = "Try again", onAction = actions.retry, modifier = Modifier.padding(padding))
            is Loadable.Ready -> {
                val sure = d.value.pairs.count { it.confident && it.amountDiff == 0L }
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
                    if (state.showFilters) {
                        item {
                            SearchControlsCard(state = state, accounts = d.value.accounts, accountNames = d.value.accountNames, actions = actions)
                        }
                    }
                    if (d.value.pairs.isEmpty()) {
                        val emptyBody = when {
                            state.mode == TransferSearchMode.TwoAccounts && (state.fromAccountId == null || state.toAccountId == null) ->
                                "Pick two different accounts above to run a detailed search between them."
                            state.mode == TransferSearchMode.TwoAccounts ->
                                "No unlinked transfers found between these two accounts. Try a wider date window or All time."
                            state.allTime ->
                                "All matching payments across your full history are linked."
                            else ->
                                "Payments between your accounts in the last four months are all linked."
                        }
                        item { MessageState("Nothing to match", emptyBody, emoji = "🔗", modifier = Modifier.padding(top = 24.dp)) }
                    } else if (sure > 1 && state.canEdit) {
                        item {
                            Button(onClick = actions.linkAllSure, modifier = Modifier.fillMaxWidth()) { Text("Link all $sure sure matches") }
                        }
                    }
                    items(d.value.pairs, key = { it.from.id.raw + it.to.id.raw }) { p ->
                        PairCard(p, d.value.accountNames, state.canEdit, state.showFilters, actions, Modifier.staggeredEntrance(d.value.pairs.indexOf(p)))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SearchControlsCard(
    state: TransfersUiState,
    accounts: List<Account>,
    accountNames: Map<String, String>,
    actions: TransfersActions,
) {
    val colors = CentsibleTheme.colors
    var pickingFrom by remember { mutableStateOf(false) }
    var pickingTo by remember { mutableStateOf(false) }

    CentsibleCard(Modifier.animateContentSize()) {
        StatLabel("Search scope")
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = state.mode == TransferSearchMode.AllAccounts,
                onClick = { actions.setMode(TransferSearchMode.AllAccounts) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) {
                Text("All accounts", maxLines = 1)
            }
            SegmentedButton(
                selected = state.mode == TransferSearchMode.TwoAccounts,
                onClick = { actions.setMode(TransferSearchMode.TwoAccounts) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) {
                Text("Between 2 accounts", maxLines = 1)
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !state.allTime,
                onClick = { actions.setAllTime(false) },
                label = { Text("Last 4 months") },
            )
            FilterChip(
                selected = state.allTime,
                onClick = { actions.setAllTime(true) },
                label = { Text("All time") },
            )
        }

        if (state.mode == TransferSearchMode.TwoAccounts) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { pickingFrom = true },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        state.fromAccountId?.let { accountNames[it.raw] } ?: "Account 1",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "and", tint = colors.textTertiary)
                OutlinedButton(
                    onClick = { pickingTo = true },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        state.toAccountId?.let { accountNames[it.raw] } ?: "Account 2",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text("Posting window", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(7, 14, 21, 30).forEach { d ->
                    FilterChip(
                        selected = state.windowDays == d,
                        onClick = { actions.setWindowDays(d) },
                        label = { Text("${d}d") },
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Fuzzy near-amount matches", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Also surface pairs within \$5 or 2% so you can spot fee or rounding differences.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textTertiary,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = state.fuzzy, onCheckedChange = actions.setFuzzy)
            }
        }
    }

    val accountItems = remember(accounts) {
        accounts.map { a ->
            PickerItem(
                key = a.id.raw,
                label = if (a.closed) "${a.name} (closed)" else a.name,
                supporting = if (a.offBudget) "Off budget" else null,
            )
        }
    }
    if (pickingFrom) {
        PickerSheet(
            title = "First account",
            items = accountItems,
            selectedKey = state.fromAccountId?.raw,
            onPick = { actions.setFromAccount(AccountId(it.key)); pickingFrom = false },
            onDismiss = { pickingFrom = false },
        )
    }
    if (pickingTo) {
        PickerSheet(
            title = "Second account",
            items = accountItems,
            selectedKey = state.toAccountId?.raw,
            onPick = { actions.setToAccount(AccountId(it.key)); pickingTo = false },
            onDismiss = { pickingTo = false },
        )
    }
}

@Composable
private fun PairCard(p: TransferPair, accounts: Map<String, String>, canEdit: Boolean, showDetails: Boolean, actions: TransfersActions, modifier: Modifier = Modifier) {
    val colors = CentsibleTheme.colors
    val isFuzzy = p.amountDiff > 0L
    CentsibleCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MoneyText(p.to.amount, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            val daysText = when (p.days) { 0 -> "Same day"; 1 -> "1 day apart"; else -> "${p.days} days apart" }
            val badge = buildList {
                if (isFuzzy) add("Differs by ${MoneyFormat.format(Money(p.amountDiff))}")
                else p.reason?.let { add(it) }
                add(daysText)
                if (!p.confident && !isFuzzy) add("other matches")
            }.joinToString(" · ")
            Text(
                badge,
                style = MaterialTheme.typography.labelMedium,
                color = if (p.confident && !isFuzzy) colors.textTertiary else colors.warning,
            )
        }
        Spacer(Modifier.padding(top = 10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Side(p.from, accounts, showAmount = isFuzzy, showNotes = showDetails, Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "to", tint = colors.textTertiary, modifier = Modifier.padding(horizontal = 8.dp))
            Side(p.to, accounts, showAmount = isFuzzy, showNotes = showDetails, Modifier.weight(1f))
        }
        if (canEdit) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { actions.dismiss(p) }, modifier = Modifier.weight(1f)) { Text("Not a transfer") }
                if (isFuzzy) {
                    Button(onClick = { actions.openTransaction(p.from.id) }, modifier = Modifier.weight(1f)) { Text("Inspect") }
                } else {
                    Button(onClick = { actions.link(p) }, modifier = Modifier.weight(1f)) { Text("Link") }
                }
            }
        }
    }
}

@Composable
private fun Side(t: Transaction, accounts: Map<String, String>, showAmount: Boolean = false, showNotes: Boolean = false, modifier: Modifier = Modifier) {
    val colors = CentsibleTheme.colors
    Column(modifier.background(colors.cardMuted, MaterialTheme.shapes.medium).padding(10.dp)) {
        Text(accounts[t.accountId.raw] ?: "Account", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(t.payeeName ?: "No payee", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val meta = buildString {
            append(shortDate(t.date))
            if (showAmount) append(" · ${MoneyFormat.format(t.amount)}")
        }
        Text(meta, style = MaterialTheme.typography.labelSmall, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (showNotes) {
            t.notes?.trim()?.takeIf { it.isNotEmpty() }?.let { note ->
                Text(note, style = MaterialTheme.typography.labelSmall, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun shortDate(iso: String): String = runCatching {
    val d = LocalDate.parse(iso)
    val pattern = if (d.year == LocalDate.now().year) "MMM d" else "MMM d, yyyy"
    d.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}.getOrDefault(iso)
