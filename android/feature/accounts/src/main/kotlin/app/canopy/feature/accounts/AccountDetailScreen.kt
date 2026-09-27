package app.canopy.feature.accounts

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
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.canopy.core.designsystem.component.CanopyCard
import app.canopy.core.designsystem.component.Loadable
import app.canopy.core.designsystem.component.LoadingState
import app.canopy.core.designsystem.component.MessageState
import app.canopy.core.designsystem.component.MoneyFormat
import app.canopy.core.designsystem.component.MoneyText
import app.canopy.core.designsystem.component.StatLabel
import app.canopy.core.designsystem.component.TransactionRow
import app.canopy.core.designsystem.theme.CanopyTheme
import app.canopy.core.model.Account
import app.canopy.core.model.AccountId
import app.canopy.core.model.TransactionId

@Composable
fun AccountDetailRoute(
    onBack: () -> Unit,
    onOpenTransaction: (TransactionId) -> Unit,
    viewModel: AccountDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.gone) { if (state.gone) onBack() }
    AccountDetailScreen(
        state = state,
        onBack = onBack,
        onRetry = { viewModel.refresh() },
        onOpenTransaction = onOpenTransaction,
        onRename = { viewModel.rename(it) },
        onClose = { viewModel.close(it) },
        onReopen = { viewModel.reopen() },
        onMessageShown = viewModel::messageShown,
    )
}

@Composable
fun AccountDetailScreen(
    state: AccountDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenTransaction: (TransactionId) -> Unit = {},
    onRename: (String) -> Unit = {},
    onClose: (AccountId?) -> Unit = {},
    onReopen: () -> Unit = {},
    onMessageShown: () -> Unit = {},
) {
    val colors = CanopyTheme.colors
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); onMessageShown() } }
    val detail = state.data.valueOrNull

    Scaffold(
        containerColor = colors.canvas,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text(detail?.account?.name ?: "Account", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (state.canWrite && detail != null) {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
                        if (detail.account.closed) DropdownMenuItem(text = { Text("Reopen") }, onClick = { menu = false; onReopen() })
                        else DropdownMenuItem(text = { Text("Close account") }, onClick = { menu = false; closing = true })
                    }
                }
            }
        },
    ) { padding ->
        when (val data = state.data) {
            Loadable.Loading -> LoadingState(Modifier.padding(padding))
            is Loadable.Failed -> MessageState("Couldn't load this account", data.message, emoji = "🔌", actionLabel = "Try again", onAction = onRetry, modifier = Modifier.padding(padding))
            is Loadable.Ready -> {
                val d = data.value
                val accountNames = (d.otherAccounts + d.account).associate { it.id.raw to it.name }
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        CanopyCard(contentPadding = PaddingValues(20.dp)) {
                            StatLabel(if (d.account.closed) "Closed · final balance" else "Balance")
                            MoneyText(d.account.balance, style = MaterialTheme.typography.displaySmall, color = if (d.account.balance.isNegative) colors.negative else colors.textPrimary)
                            Text(
                                listOf(d.account.kind().title, if (d.account.offBudget) "Tracking only" else "On budget").joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary,
                            )
                        }
                    }
                    item {
                        CanopyCard(contentPadding = PaddingValues(0.dp)) {
                            if (d.transactions.isEmpty()) {
                                Text("No transactions yet", style = MaterialTheme.typography.bodyMedium, color = colors.textTertiary, modifier = Modifier.padding(16.dp))
                            }
                            d.transactions.forEachIndexed { i, t ->
                                if (i > 0) HorizontalDivider(Modifier.padding(start = 64.dp), color = colors.border)
                                TransactionRow(t, d.categoryNames, accountNames, onClick = { onOpenTransaction(t.id) })
                            }
                        }
                    }
                    if (d.nextCursor != null) {
                        item { Text("Showing the latest 50. Search on the Transactions tab for older ones.", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary) }
                    }
                }
            }
        }
    }

    if (renaming && detail != null) {
        var name by remember { mutableStateOf(detail.account.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename account") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { renaming = false; onRename(name) }, enabled = name.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (closing && detail != null) CloseAccountDialog(detail.account, detail.otherAccounts, onDismiss = { closing = false }, onClose = { closing = false; onClose(it) })
}

@Composable
private fun CloseAccountDialog(account: Account, others: List<Account>, onDismiss: () -> Unit, onClose: (AccountId?) -> Unit) {
    val needsTransfer = !account.balance.isZero
    var target by remember { mutableStateOf<AccountId?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Close ${account.name}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (needsTransfer) {
                    Text("It still has ${MoneyFormat.format(account.balance)}. Move the balance to:")
                    others.forEach { a ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.RadioButton(selected = target == a.id, onClick = { target = a.id })
                            Text(a.name)
                        }
                    }
                } else {
                    Text("Its history stays in your budget. You can reopen it any time. (If it has no transactions, Actual removes it.)")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onClose(target) }, enabled = !needsTransfer || target != null) { Text("Close account") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
