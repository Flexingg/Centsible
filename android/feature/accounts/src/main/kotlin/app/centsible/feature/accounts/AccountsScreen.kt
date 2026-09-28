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

@Composable
fun AccountsRoute(onOpenAccount: (AccountId) -> Unit, viewModel: AccountsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AccountsScreen(
        state,
        onRetry = { viewModel.refresh() },
        onOpenAccount = onOpenAccount,
        onAddAccount = { name, offBudget, balance -> viewModel.addAccount(name, offBudget, balance) },
        onMessageShown = viewModel::messageShown,
    )
}

@Composable
fun AccountsScreen(
    state: AccountsUiState,
    onRetry: () -> Unit,
    onOpenAccount: (AccountId) -> Unit = {},
    onAddAccount: (String, Boolean, Money) -> Unit = { _, _, _ -> },
    onMessageShown: () -> Unit = {},
) {
    var adding by remember { mutableStateOf(false) }
    var showClosed by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); onMessageShown() } }
    Scaffold(containerColor = CentsibleTheme.colors.canvas, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Accounts", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                if (state.canWrite) TextButton(onClick = { adding = true }) { Text("Add account") }
            }
            when (val data = state.data) {
                Loadable.Loading -> LoadingState()
                is Loadable.Failed -> MessageState("Couldn't load accounts", data.message, emoji = "🔌", actionLabel = "Try again", onAction = onRetry)
                is Loadable.Ready -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { NetWorthCard(data.value) }
                    items(data.value.sections, key = { it.kind.name }) { SectionCard(it, onOpenAccount) }
                    if (data.value.closed.isNotEmpty()) {
                        item(key = "closed") {
                            TextButton(onClick = { showClosed = !showClosed }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (showClosed) "Hide closed accounts" else "Show ${data.value.closed.size} closed")
                            }
                            if (showClosed) SectionCard(AccountSection(AccountKind.Cash, data.value.closed), onOpenAccount, title = "Closed")
                        }
                    }
                }
            }
        }
    }
    if (adding) AddAccountDialog(onDismiss = { adding = false }, onAdd = { n, o, b -> adding = false; onAddAccount(n, o, b) })
}

@Composable
private fun AddAccountDialog(onDismiss: () -> Unit, onAdd: (String, Boolean, Money) -> Unit) {
    var name by remember { mutableStateOf("") }
    var balance by remember { mutableStateOf("") }
    var offBudget by remember { mutableStateOf(false) }
    val parsed = if (balance.isBlank()) Money.Zero else MoneyInput.parse(balance)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add an account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
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
                        Text("For investments, retirement and loans. Can't be changed later.", style = MaterialTheme.typography.bodySmall, color = CentsibleTheme.colors.textSecondary)
                    }
                    Switch(checked = offBudget, onCheckedChange = null)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(name, offBudget, parsed!!) }, enabled = name.isNotBlank() && parsed != null) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
private fun SectionCard(section: AccountSection, onOpen: (AccountId) -> Unit, title: String = section.kind.title) {
    val colors = CentsibleTheme.colors
    CentsibleCard(contentPadding = PaddingValues(0.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            MoneyText(section.total, style = MaterialTheme.typography.titleSmall)
        }
        section.accounts.forEachIndexed { i, account ->
            if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = colors.border)
            AccountRow(account, onClick = { onOpen(account.id) })
        }
    }
}

@Composable
private fun AccountRow(account: Account, onClick: () -> Unit) {
    val colors = CentsibleTheme.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(account.name, style = MaterialTheme.typography.bodyLarge)
            Text(if (account.offBudget) "Tracking" else "On budget", style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
        }
        MoneyText(account.balance, style = MaterialTheme.typography.bodyLarge, color = if (account.balance.isNegative) colors.negative else colors.textPrimary)
    }
}
