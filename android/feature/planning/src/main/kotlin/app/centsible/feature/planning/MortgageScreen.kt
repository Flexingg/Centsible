package app.centsible.feature.planning

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
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
import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.designsystem.component.MoneyInput
import app.centsible.core.designsystem.component.MoneyText
import app.centsible.core.designsystem.component.ProgressRing
import app.centsible.core.designsystem.component.StatLabel
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.domain.BudgetChanges
import app.centsible.core.domain.BudgetEngine
import app.centsible.core.domain.PlanAheadGateway
import app.centsible.core.domain.SelectedBudget
import app.centsible.core.domain.SessionStore
import app.centsible.core.domain.userMessage
import app.centsible.core.model.AccountId
import app.centsible.core.model.Money
import app.centsible.core.model.Mortgage
import app.centsible.core.model.MortgageInput
import app.centsible.core.model.PayeeId
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

data class MortgageUiState(
    val items: Loadable<List<Mortgage>> = Loadable.Loading,
    /** The one open in detail, with its schedule. */
    val detail: Mortgage? = null,
    val accounts: List<Pair<AccountId, String>> = emptyList(),
    val payees: List<Pair<PayeeId, String>> = emptyList(),
    val canEdit: Boolean = false,
    /** The setup form: a mortgage to edit, or [creating] a new one. */
    val editing: Mortgage? = null,
    val creating: Boolean = false,
    val valuing: Mortgage? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class MortgageViewModel @Inject constructor(
    private val plan: PlanAheadGateway,
    private val engine: BudgetEngine,
    private val selectedBudget: SelectedBudget,
    private val sessions: SessionStore,
    changes: BudgetChanges,
) : ViewModel() {
    private val state = MutableStateFlow(MortgageUiState())
    val uiState: StateFlow<MortgageUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            state.update { it.copy(canEdit = sessions.current()?.member?.role?.canWrite == true) }
            load()
            changes.changes.collect { load() }
        }
    }

    fun refresh() = viewModelScope.launch { load() }

    private suspend fun load() {
        runCatching {
            coroutineScope {
                val b = selectedBudget()
                val items = async { plan.mortgages(b) }
                val accounts = async { engine.accounts(b).filter { !it.closed }.map { it.id to it.name } }
                val payees = async { engine.payees(b).filter { it.transferAccountId == null }.map { it.id to it.name }.sortedBy { it.second.lowercase() } }
                val open = state.value.detail?.id
                Triple(items.await(), accounts.await() to payees.await(), open?.let { plan.mortgage(b, it) })
            }
        }
            .onSuccess { (items, lists, detail) ->
                state.update { it.copy(items = Loadable.Ready(items), accounts = lists.first, payees = lists.second, detail = detail) }
            }
            .onFailure { e -> state.update { it.copy(items = if (it.items is Loadable.Ready) it.items else Loadable.Failed(e.userMessage()), message = e.userMessage()) } }
    }

    fun open(m: Mortgage?) {
        if (m == null) {
            state.update { it.copy(detail = null) }
            return
        }
        state.update { it.copy(detail = m) }
        viewModelScope.launch {
            runCatching { plan.mortgage(selectedBudget(), m.id) }
                .onSuccess { d -> state.update { it.copy(detail = d) } }
                .onFailure { e -> state.update { it.copy(message = e.userMessage()) } }
        }
    }

    fun create(open: Boolean) = state.update { it.copy(creating = open, editing = null) }
    fun edit(m: Mortgage?) = state.update { it.copy(editing = m, creating = false) }
    fun value(m: Mortgage?) = state.update { it.copy(valuing = m) }

    fun save(id: String?, input: MortgageInput) = act("Saved") { plan.saveMortgage(selectedBudget(), id, input) }
    fun delete(id: String) = act("Mortgage removed. Its accounts stay in Actual.") {
        plan.deleteMortgage(selectedBudget(), id)
        state.update { it.copy(detail = null) }
    }
    fun setHomeValue(id: String, value: Money) = act("Home value updated") { plan.setHomeValue(selectedBudget(), id, value) }
    fun recordPrincipal(id: String) = act(null) {
        val n = plan.recordPrincipal(selectedBudget(), id)
        state.update { it.copy(message = "Recorded the principal from $n payment${if (n == 1) "" else "s"}") }
    }

    fun messageShown() = state.update { it.copy(message = null) }

    private fun act(success: String?, block: suspend () -> Unit) = viewModelScope.launch {
        state.update { it.copy(busy = true) }
        runCatching { block() }
            .onSuccess {
                state.update { it.copy(busy = false, creating = false, editing = null, valuing = null, message = success ?: it.message) }
                load()
            }
            .onFailure { e -> state.update { it.copy(busy = false, message = e.userMessage()) } }
    }
}

data class MortgageActions(
    val back: () -> Unit = {},
    val retry: () -> Unit = {},
    val open: (Mortgage?) -> Unit = {},
    val create: (Boolean) -> Unit = {},
    val edit: (Mortgage?) -> Unit = {},
    val value: (Mortgage?) -> Unit = {},
    val save: (String?, MortgageInput) -> Unit = { _, _ -> },
    val delete: (String) -> Unit = {},
    val setHomeValue: (String, Money) -> Unit = { _, _ -> },
    val recordPrincipal: (String) -> Unit = {},
    val messageShown: () -> Unit = {},
)

@Composable
fun MortgageRoute(onBack: () -> Unit, viewModel: MortgageViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    MortgageScreen(
        state,
        MortgageActions(
            back = { if (state.detail != null) viewModel.open(null) else onBack() },
            retry = { viewModel.refresh() },
            open = viewModel::open,
            create = viewModel::create,
            edit = viewModel::edit,
            value = viewModel::value,
            save = { id, i -> viewModel.save(id, i) },
            delete = { viewModel.delete(it) },
            setHomeValue = { id, v -> viewModel.setHomeValue(id, v) },
            recordPrincipal = { viewModel.recordPrincipal(it) },
            messageShown = viewModel::messageShown,
        ),
    )
}

@Composable
fun MortgageScreen(state: MortgageUiState, actions: MortgageActions) {
    val colors = CentsibleTheme.colors
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); actions.messageShown() } }
    androidx.activity.compose.BackHandler(enabled = state.detail != null) { actions.open(null) }
    val detail = state.detail
    Scaffold(
        containerColor = colors.canvas,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text(detail?.name ?: "Mortgage", style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (detail != null && state.canEdit) IconButton(onClick = { actions.edit(detail) }) { Icon(Icons.Rounded.Edit, contentDescription = "Edit terms") }
            }
        },
    ) { padding ->
        when (val d = state.items) {
            Loadable.Loading -> LoadingState(Modifier.padding(padding))
            is Loadable.Failed -> MessageState("Couldn't load your mortgage", d.message, emoji = "🏠", actionLabel = "Try again", onAction = actions.retry, modifier = Modifier.padding(padding))
            is Loadable.Ready -> when {
                detail != null -> MortgageDetail(detail, state, actions, Modifier.padding(padding))
                d.value.isEmpty() -> MessageState(
                    "Track your mortgage",
                    "See every payment's interest and principal, when it'll be paid off, and your home's value in net worth.",
                    emoji = "🏠",
                    actionLabel = if (state.canEdit) "Set up a mortgage" else null,
                    onAction = { actions.create(true) },
                    modifier = Modifier.padding(padding),
                )
                else -> LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(d.value, key = { it.id }) { m -> MortgageCard(m, state, actions, onClick = { actions.open(m) }) }
                    if (state.canEdit) item { OutlinedButton(onClick = { actions.create(true) }, modifier = Modifier.fillMaxWidth()) { Text("Add another loan") } }
                }
            }
        }
    }
    if (state.creating || state.editing != null) MortgageSheet(state.editing, state, onSave = actions.save, onDelete = actions.delete, onDismiss = { actions.create(false); actions.edit(null) })
    state.valuing?.let { m -> HomeValueDialog(m, onSave = { actions.setHomeValue(m.id, it) }, onDismiss = { actions.value(null) }) }
}

@Composable
private fun MortgageCard(m: Mortgage, state: MortgageUiState, actions: MortgageActions, onClick: (() -> Unit)?) {
    val colors = CentsibleTheme.colors
    CentsibleCard(onClick = onClick, contentPadding = PaddingValues(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(m.paidOff, 72.dp, 8.dp, color = colors.positive)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                StatLabel("Owed")
                MoneyText(m.balance, style = MaterialTheme.typography.headlineSmall)
                Text("Paid off ${monthYear(m.payoffDate)} · ${m.paymentsLeft} payments left", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Fact("Each month", MoneyFormat.format(m.monthlyTotal), Modifier.weight(1f))
            Fact("Home value", m.homeValue?.let { MoneyFormat.format(it, showCents = false) } ?: "Not set", Modifier.weight(1f))
            Fact("Equity", m.equity?.let { MoneyFormat.format(it, showCents = false) } ?: "–", Modifier.weight(1f))
        }
        val (text, good) = standing(m)
        Text(text, style = MaterialTheme.typography.bodySmall, color = if (good) colors.positive else colors.textSecondary, modifier = Modifier.padding(top = 12.dp))
        if (state.canEdit) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { actions.value(m) }, modifier = Modifier.weight(1f)) { Text(if (m.homeValue == null) "Add home value" else "Update value") }
                if (m.unrecorded > 0) {
                    FilledTonalButton(onClick = { actions.recordPrincipal(m.id) }, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                        Text("Record ${m.unrecorded} payment${if (m.unrecorded == 1) "" else "s"}")
                    }
                }
            }
            if (m.unrecorded > 0) {
                Text(
                    "Takes each payment's principal off the loan's balance, so net worth follows.",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        StatLabel(label)
        Text(value, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Ahead of the schedule, on it, or behind, and what extra is saving. */
internal fun standing(m: Mortgage): Pair<String, Boolean> {
    val ahead = m.aheadBy.minor
    val saved = if (m.interestSaved.minor > 0) " Saving ${MoneyFormat.format(m.interestSaved, showCents = false)} in interest." else ""
    return when {
        ahead > 100 -> "${MoneyFormat.format(m.aheadBy, showCents = false)} ahead of schedule.$saved" to true
        ahead < -100 -> "${MoneyFormat.format(Money(-ahead), showCents = false)} behind the schedule." to false
        else -> "On schedule.$saved" to (m.interestSaved.minor > 0)
    }
}

@Composable
private fun MortgageDetail(m: Mortgage, state: MortgageUiState, actions: MortgageActions, modifier: Modifier) {
    val colors = CentsibleTheme.colors
    val today = LocalDate.now().toString()
    val years = m.schedule.groupBy { it.date.take(4) }.toList()
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { MortgageCard(m, state, actions, onClick = null) }
        item {
            CentsibleCard(contentPadding = PaddingValues(20.dp)) {
                StatLabel("Balance over time")
                BalanceChart(m, today, Modifier.fillMaxWidth().height(140.dp).padding(top = 8.dp))
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Fact("Interest paid", MoneyFormat.format(m.interestPaid, showCents = false), Modifier.weight(1f))
                    Fact("Interest left", MoneyFormat.format(m.interestLeft, showCents = false), Modifier.weight(1f))
                    Fact("Rate", "${m.rate}%", Modifier.weight(1f))
                }
            }
        }
        if (m.schedule.isNotEmpty()) {
            item { StatLabel("Payments, year by year", Modifier.padding(start = 4.dp, top = 4.dp)) }
            item {
                CentsibleCard(contentPadding = PaddingValues(vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        listOf("Year", "Interest", "Principal", "Owed after").forEachIndexed { i, h ->
                            Text(h, style = MaterialTheme.typography.labelSmall, color = colors.textTertiary, modifier = Modifier.weight(if (i == 0) 0.7f else 1f))
                        }
                    }
                    years.forEach { (year, rows) ->
                        HorizontalDivider(color = colors.border)
                        val paid = rows.count { it.paidOn != null }
                        val current = year == today.take(4)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(year + if (paid == rows.size) " ✓" else "", style = MaterialTheme.typography.bodyMedium, color = if (current) colors.accent else colors.textPrimary, modifier = Modifier.weight(0.7f))
                            Text(MoneyFormat.format(Money(rows.sumOf { it.interest.minor }), showCents = false), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(MoneyFormat.format(Money(rows.sumOf { it.principal.minor + it.extra.minor }), showCents = false), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(MoneyFormat.format(rows.last().balance, showCents = false), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/** What's owed over the life of the loan, with a marker at today. */
@Composable
private fun BalanceChart(m: Mortgage, today: String, modifier: Modifier) {
    val colors = CentsibleTheme.colors
    val rows = m.schedule
    val line = colors.accent
    val grid = colors.border
    val marker = colors.textSecondary
    val nowIndex = rows.indexOfLast { it.date <= today }
    Canvas(modifier.semantics { contentDescription = "Owed falls from ${MoneyFormat.format(m.principal, showCents = false)} to zero by ${monthYear(m.payoffDate)}" }) {
        if (rows.isEmpty()) return@Canvas
        val max = m.principal.minor.toFloat()
        fun x(i: Int) = size.width * i / (rows.size - 1).coerceAtLeast(1)
        fun y(v: Long) = size.height * (1f - v / max)
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 2f)
        val path = Path().apply {
            moveTo(0f, y(m.principal.minor))
            rows.forEachIndexed { i, r -> lineTo(x(i), y(r.balance.minor)) }
        }
        drawPath(path, line, style = Stroke(width = 5f, cap = StrokeCap.Round))
        if (nowIndex >= 0) {
            val px = x(nowIndex)
            drawLine(marker, Offset(px, 0f), Offset(px, size.height), 3f)
            drawCircle(line, 9f, Offset(px, y(rows[nowIndex].balance.minor)))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MortgageSheet(existing: Mortgage?, state: MortgageUiState, onSave: (String?, MortgageInput) -> Unit, onDelete: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = CentsibleTheme.colors
    var name by remember { mutableStateOf(existing?.name ?: "Mortgage") }
    var principal by remember { mutableStateOf(existing?.principal?.let(MoneyInput::toInput).orEmpty()) }
    var rate by remember { mutableStateOf(existing?.rate?.toString().orEmpty()) }
    var years by remember { mutableStateOf(existing?.termMonths?.let { if (it % 12 == 0) (it / 12).toString() else null } ?: "30") }
    var first by remember { mutableStateOf(existing?.firstPayment ?: LocalDate.now().withDayOfMonth(1).toString()) }
    var escrow by remember { mutableStateOf(existing?.escrow?.takeIf { it.minor > 0 }?.let(MoneyInput::toInput).orEmpty()) }
    var extra by remember { mutableStateOf(existing?.extra?.takeIf { it.minor > 0 }?.let(MoneyInput::toInput).orEmpty()) }
    var payee by remember { mutableStateOf(existing?.payeeId) }
    var account by remember { mutableStateOf(existing?.paymentAccountId) }
    var createLoan by remember { mutableStateOf(existing == null) }
    var owedNow by remember { mutableStateOf("") }
    var homeValue by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val p = MoneyInput.parse(principal)?.takeIf { it.minor > 0 }
    val r = rate.toDoubleOrNull()?.takeIf { it >= 0 && it < 50 }
    val n = years.toIntOrNull()?.takeIf { it in 1..50 }
    val date = runCatching { LocalDate.parse(first) }.getOrNull()
    val ready = name.isNotBlank() && p != null && r != null && n != null && date != null && !state.busy

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.card) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (existing == null) "Set up a mortgage" else "Mortgage terms", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            MoneyField(principal, { principal = it }, "Amount borrowed")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    rate, { rate = it }, label = { Text("Rate") }, suffix = { Text("%") }, singleLine = true,
                    isError = rate.isNotEmpty() && r == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    years, { years = it }, label = { Text("Years") }, singleLine = true,
                    isError = years.isNotEmpty() && n == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                first, { first = it }, label = { Text("First payment (YYYY-MM-DD)") }, singleLine = true,
                isError = date == null, supportingText = { Text("Later payments fall on the same day each month.") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (p != null && r != null && n != null) {
                val monthly = monthlyPayment(p.minor, r, n * 12)
                Text("Principal and interest: ${MoneyFormat.format(Money(monthly))} a month", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            }
            MoneyField(escrow, { escrow = it }, "Escrow each month (optional)")
            MoneyField(extra, { extra = it }, "Extra principal each month (optional)")

            StatLabel("Payments go to")
            ChoiceList(state.payees, payee, { payee = it }, empty = "No payees yet")
            StatLabel("Paid from")
            ChoiceList(state.accounts, account, { account = it }, empty = "No accounts")

            if (existing == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Add a loan account", style = MaterialTheme.typography.bodyLarge)
                        Text("Off budget, owing the balance, so net worth counts it.", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
                    }
                    Switch(checked = createLoan, onCheckedChange = { createLoan = it }, modifier = Modifier.semantics { contentDescription = "Add a loan account" })
                }
                if (createLoan) MoneyField(owedNow, { owedNow = it }, "Owed today (blank: from the schedule)")
                MoneyField(homeValue, { homeValue = it }, "Home's value (optional)")
            }
            Button(
                onClick = {
                    onSave(
                        existing?.id,
                        MortgageInput(
                            name = name.trim(), principal = p!!, rate = r!!, termMonths = n!! * 12, firstPayment = date!!.toString(),
                            escrow = MoneyInput.parse(escrow) ?: Money.Zero, extra = MoneyInput.parse(extra) ?: Money.Zero,
                            payeeId = payee, paymentAccountId = account,
                            loanAccountId = existing?.loanAccountId, homeAccountId = existing?.homeAccountId,
                            createLoanAccount = existing == null && createLoan,
                            currentBalance = MoneyInput.parse(owedNow).takeIf { createLoan },
                            homeValue = MoneyInput.parse(homeValue),
                        ),
                    )
                },
                enabled = ready,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
            if (existing != null) {
                OutlinedButton(onClick = { confirmDelete = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Remove", color = colors.negative) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove this mortgage?") },
            text = { Text("Only the terms go. The loan and home accounts stay in Actual with their history.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(existing.id) }) { Text("Remove", color = colors.negative) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MoneyField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, prefix = { Text("$") }, singleLine = true,
        isError = value.isNotEmpty() && MoneyInput.parse(value) == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
    )
}

/** A short radio list; long ones show the first few plus the chosen one. */
@Composable
private fun <T> ChoiceList(items: List<Pair<T, String>>, selected: T?, onPick: (T?) -> Unit, empty: String) {
    val colors = CentsibleTheme.colors
    var all by remember { mutableStateOf(false) }
    if (items.isEmpty()) {
        Text(empty, style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
        return
    }
    val shown = if (all || items.size <= 6) items else (items.take(5) + items.filter { it.first == selected }).distinct()
    Column {
        shown.forEach { (id, n) ->
            Row(
                Modifier.fillMaxWidth().selectable(selected == id, role = Role.RadioButton) { onPick(if (selected == id) null else id) }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == id, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(n, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!all && items.size > 6) TextButton(onClick = { all = true }) { Text("Show all ${items.size}") }
    }
}

@Composable
private fun HomeValueDialog(m: Mortgage, onSave: (Money) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(m.homeValue?.let(MoneyInput::toInput).orEmpty()) }
    val parsed = MoneyInput.parse(value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Home's value") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("What it would sell for today, from an appraisal or an estimate. Net worth counts it.", style = MaterialTheme.typography.bodyMedium)
                MoneyField(value, { value = it }, "Value")
            }
        },
        confirmButton = { TextButton(onClick = { parsed?.let(onSave) }, enabled = parsed != null) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Same formula as the bridge, for the form's preview. */
internal fun monthlyPayment(principal: Long, rate: Double, months: Int): Long {
    val r = rate / 100 / 12
    if (r == 0.0) return (principal + months - 1) / months
    return Math.round(principal * r / (1 - Math.pow(1 + r, -months.toDouble())))
}

private fun monthYear(iso: String) = runCatching { LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())) }.getOrDefault(iso)
