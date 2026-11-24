package app.centsible.feature.accounts

import app.centsible.core.designsystem.component.toggleRow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import app.centsible.core.designsystem.component.MoneyInput
import app.centsible.core.model.AccountId
import app.centsible.core.model.Money
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.centsible.core.designsystem.component.CentsibleCard
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.component.LoadingState
import app.centsible.core.designsystem.component.MessageState
import app.centsible.core.designsystem.component.MoneyText
import app.centsible.core.designsystem.component.StatLabel
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.Account
import app.centsible.core.model.AccountType
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.semantics.Role

@Composable
fun AccountsRoute(onOpenAccount: (AccountId) -> Unit, viewModel: AccountsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val switching by viewModel.switcher.current.collectAsStateWithLifecycle()
    AccountsScreen(
        state,
        switching = switching,
        switchActions = remember(viewModel) { viewModel.switcher.actions() },
        onRetry = { viewModel.refresh() },
        onOpenAccount = onOpenAccount,
        onAddAccount = { name, offBudget, balance, type -> viewModel.addAccount(name, offBudget, balance, type) },
        onSetType = viewModel::setType,
        onMessageShown = viewModel::messageShown,
    )
}

@Composable
fun AccountsScreen(
    state: AccountsUiState,
    onRetry: () -> Unit,
    onOpenAccount: (AccountId) -> Unit = {},
    onAddAccount: (String, Boolean, Money, AccountType?) -> Unit = { _, _, _, _ -> },
    onSetType: (AccountId, AccountType?) -> Unit = { _, _ -> },
    onMessageShown: () -> Unit = {},
    /** Start in "Edit types" mode (for screenshots). */
    initiallyEditingTypes: Boolean = false,
    switching: BudgetSwitchState? = null,
    switchActions: BudgetSwitchActions = BudgetSwitchActions(),
) {
    var adding by remember { mutableStateOf(false) }
    var editingTypes by remember { mutableStateOf(initiallyEditingTypes) }
    var choosingFor by remember { mutableStateOf<Account?>(null) }
    var showClosed by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); onMessageShown() } }
    Scaffold(containerColor = CentsibleTheme.colors.canvas, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Accounts", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                if (state.canWrite && state.data is Loadable.Ready) TextButton(onClick = { editingTypes = !editingTypes }) { Text(if (editingTypes) "Done" else "Edit types") }
                if (state.canWrite && !editingTypes) TextButton(onClick = { adding = true }) { Text("Add account") }
            }
            when (val data = state.data) {
                Loadable.Loading -> LoadingState()
                is Loadable.Failed -> MessageState("Couldn't load accounts", data.message, emoji = "🔌", actionLabel = "Try again", onAction = onRetry)
                is Loadable.Ready -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (editingTypes) {
                        item(key = "types-hint") {
                            Text(
                                "Tap an account's type to change it. It decides where the account is grouped here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = CentsibleTheme.colors.textSecondary,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    } else {
                        item { NetWorthCard(data.value) }
                        val suggest = state.suggestOffBudget
                        if (state.canWrite && suggest.isNotEmpty()) {
                            item(key = "off-budget") { OffBudgetCard(suggest) { switchActions.start(it, true) } }
                        }
                    }
                    items(data.value.sections, key = { it.kind.name }) { SectionCard(it, onOpenAccount, onChooseType = if (editingTypes) { a -> choosingFor = a } else null) }
                    if (data.value.closed.isNotEmpty()) {
                        item(key = "closed") {
                            TextButton(onClick = { showClosed = !showClosed }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (showClosed) "Hide closed accounts" else "Show ${data.value.closed.size} closed")
                            }
                            if (showClosed) SectionCard(AccountSection(AccountType.Cash, data.value.closed), onOpenAccount, title = "Closed", onChooseType = if (editingTypes) { a -> choosingFor = a } else null)
                        }
                    }
                }
            }
        }
    }
    BudgetSwitchHost(switching, switchActions)
    if (adding) AddAccountDialog(onDismiss = { adding = false }, onAdd = { n, o, b, t -> adding = false; onAddAccount(n, o, b, t) })
    choosingFor?.let { account ->
        AccountTypeSheet(account, onDismiss = { choosingFor = null }, onChoose = { type -> choosingFor = null; if (type != account.type) onSetType(account.id, type) })
    }
}

/** Picks an account's type, or "Automatic" to go back to guessing from the name. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountTypeSheet(account: Account, onDismiss: () -> Unit, onChoose: (AccountType?) -> Unit) {
    val colors = CentsibleTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.card) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("${account.name} is a…", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            AccountType.entries.forEach { t ->
                TypeOption(t.singular, typeHint(t), selected = account.type == t) { onChoose(t) }
            }
            TypeOption("Automatic", "Guess from the name and balance (now: ${account.guessType().singular.lowercase()})", selected = account.type == null) { onChoose(null) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun typeHint(t: AccountType) = when (t) {
    AccountType.Cash -> "Checking and everyday spending"
    AccountType.Savings -> "Savings, money market, CDs"
    AccountType.Credit -> "Credit cards and lines of credit"
    AccountType.Investment -> "Brokerage, retirement, HSA"
    AccountType.Mortgage -> "A home loan or HELOC"
    AccountType.Loan -> "Auto, student and personal loans"
}

@Composable
private fun TypeOption(title: String, hint: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = CentsibleTheme.colors.textSecondary)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddAccountDialog(onDismiss: () -> Unit, onAdd: (String, Boolean, Money, AccountType?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var balance by remember { mutableStateOf("") }
    var offBudget by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf<AccountType?>(null) }
    val parsed = if (balance.isBlank()) Money.Zero else MoneyInput.parse(balance)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add an account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AccountType.entries.forEach { t ->
                        FilterChip(
                            selected = type == t,
                            onClick = {
                                type = if (type == t) null else t
                                // Investments and loans usually sit outside the budget.
                                if (type != null) offBudget = type in setOf(AccountType.Investment, AccountType.Mortgage, AccountType.Loan)
                            },
                            label = { Text(t.singular) },
                        )
                    }
                }
                OutlinedTextField(
                    balance, { balance = it },
                    label = { Text("Current balance") },
                    supportingText = { Text("Negative for credit cards and loans") },
                    prefix = { Text("$") },
                    isError = parsed == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Row(Modifier.toggleRow(offBudget) { offBudget = it }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Track only (off budget)", style = MaterialTheme.typography.bodyLarge)
                        Text("For investments, retirement, mortgages and loans. You can change it later.", style = MaterialTheme.typography.bodySmall, color = CentsibleTheme.colors.textSecondary)
                    }
                    Switch(checked = offBudget, onCheckedChange = null)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(name, offBudget, parsed!!, type) }, enabled = name.isNotBlank() && parsed != null) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Investments, mortgages and loans still on budget: each one a tap away from being tracked instead. */
@Composable
private fun OffBudgetCard(accounts: List<Account>, onReview: (Account) -> Unit) {
    val colors = CentsibleTheme.colors
    CentsibleCard(contentPadding = PaddingValues(16.dp)) {
        Text(
            if (accounts.size == 1) "Track this one off budget?" else "Track these ${accounts.size} off budget?",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "Investments, mortgages and loans usually sit outside the budget: their balances count in net worth, and money moved into them counts as spending.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
        )
        accounts.forEach { a ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        a.kind().singular + if (a.type == null) " (guessed from the name)" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
                TextButton(onClick = { onReview(a) }) { Text("Review") }
            }
        }
    }
}

@Composable
fun NetWorthCard(summary: AccountsSummary) {
    val colors = CentsibleTheme.colors
    CentsibleCard(contentPadding = PaddingValues(20.dp)) {
        StatLabel("Net worth")
        MoneyText(summary.netWorth, style = MaterialTheme.typography.displaySmall, showCents = false, animate = true)
        Spacer(Modifier.height(16.dp))
        val total = (summary.assets.minor - summary.liabilities.minor).coerceAtLeast(1)
        val assetShare = summary.assets.minor.toFloat() / total
        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
            if (assetShare > 0f) Box(Modifier.weight(assetShare).fillMaxHeight().background(colors.positive))
            if (assetShare < 1f) Box(Modifier.weight(1f - assetShare).fillMaxHeight().background(colors.negative.copy(alpha = 0.75f)))
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Legend("Assets", colors.positive) { MoneyText(summary.assets, style = MaterialTheme.typography.titleSmall, showCents = false) }
            Legend("Liabilities", colors.negative) { MoneyText(summary.liabilities.abs(), style = MaterialTheme.typography.titleSmall, showCents = false) }
        }
    }
}

@Composable
private fun Legend(label: String, color: Color, value: @Composable () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Spacer(Modifier.width(6.dp))
            StatLabel(label)
        }
        value()
    }
}

@Composable
private fun SectionCard(section: AccountSection, onOpen: (AccountId) -> Unit, title: String = section.kind.title, onChooseType: ((Account) -> Unit)? = null) {
    val colors = CentsibleTheme.colors
    CentsibleCard(contentPadding = PaddingValues(0.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            MoneyText(section.total, style = MaterialTheme.typography.titleSmall)
        }
        section.accounts.forEachIndexed { i, account ->
            if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = colors.border)
            AccountRow(account, onClick = { onOpen(account.id) }, onChooseType = onChooseType?.let { f -> { f(account) } })
        }
    }
}

@Composable
private fun AccountRow(account: Account, onClick: () -> Unit, onChooseType: (() -> Unit)? = null) {
    val colors = CentsibleTheme.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onChooseType ?: onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(account.name, style = MaterialTheme.typography.bodyLarge)
            Text(if (account.offBudget) "Tracking" else "On budget", style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
        }
        if (onChooseType != null) {
            FilterChip(
                selected = account.type != null,
                onClick = onChooseType,
                label = { Text(account.kind().singular + if (account.type == null) " (guess)" else "") },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        MoneyText(account.balance, style = MaterialTheme.typography.bodyLarge, color = if (account.balance.isNegative) colors.negative else colors.textPrimary)
    }
}
