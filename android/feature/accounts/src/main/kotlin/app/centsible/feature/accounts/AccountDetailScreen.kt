package app.centsible.feature.accounts

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.animateContentSize
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
import app.centsible.core.designsystem.component.CentsibleCard
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.component.LoadingState
import app.centsible.core.designsystem.component.MessageState
import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.designsystem.component.MoneyText
import app.centsible.core.designsystem.component.StatLabel
import app.centsible.core.designsystem.component.TransactionRow
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.Account
import app.centsible.core.model.AccountId
import app.centsible.core.model.TransactionId

@Composable
fun AccountDetailRoute(
    onBack: () -> Unit,
    onOpenTransaction: (TransactionId) -> Unit,
    viewModel: AccountDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(state.gone) { if (state.gone) onBack() }
    // The system file picker: no storage permission needed.
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readPickedFile(context, uri)?.let { (name, bytes) -> viewModel.pickedFile(name, bytes) }
    }
    AccountDetailScreen(
        actions = AccountActions(
            sync = viewModel::syncNow,
            import = { picker.launch(arrayOf("*/*")) },
            importOptions = viewModel::importOptions,
            confirmImport = { viewModel.confirmImport() },
            cancelImport = viewModel::cancelImport,
            reconcile = { viewModel.startReconcile() },
            submitReconcile = { amount, adjust -> viewModel.reconcile(amount, adjust) },
            cancelReconcile = viewModel::cancelReconcile,
            loadMore = viewModel::loadMore,
        ),
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
    actions: AccountActions = AccountActions(),
) {
    val colors = CentsibleTheme.colors
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
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                LaunchedEffect(listState, d.nextCursor) {
                    if (d.nextCursor == null) return@LaunchedEffect
                    androidx.compose.runtime.snapshotFlow {
                        val info = listState.layoutInfo
                        (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 3
                    }.collect { nearEnd -> if (nearEnd) actions.loadMore() }
                }
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        CentsibleCard(contentPadding = PaddingValues(20.dp)) {
                            StatLabel(if (d.account.closed) "Closed · final balance" else "Balance")
                            MoneyText(d.account.balance, style = MaterialTheme.typography.displaySmall, color = if (d.account.balance.isNegative) colors.negative else colors.textPrimary, animate = true)
                            Text(
                                listOf(d.account.kind().title, if (d.account.offBudget) "Tracking only" else "On budget").joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary,
                            )
                            if (d.account.syncSource != null) {
                                Text(
                                    "Bank sync · " + (d.account.lastSync?.let { "last synced ${it.take(10)}" } ?: "not synced yet"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textTertiary,
                                )
                                app.centsible.core.model.BankSyncStatus.describe(d.account.bankSyncStatus)?.let {
                                    Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = colors.warning)
                                }
                            }
                        }
                    }
                    if (!d.account.closed && (state.canImport || state.canReconcile || (state.canSync && d.account.syncSource != null))) {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (state.canSync && d.account.syncSource != null) {
                                    androidx.compose.material3.FilledTonalButton(onClick = actions.sync, enabled = !state.syncing, modifier = Modifier.weight(1f)) {
                                        if (state.syncing) { app.centsible.core.designsystem.component.DialSpinner(size = 18.dp); androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp)) }
                                        Text(if (state.syncing) "Syncing…" else "Sync now")
                                    }
                                }
                                if (state.canImport) {
                                    androidx.compose.material3.OutlinedButton(onClick = actions.import, modifier = Modifier.weight(1f)) { Text("Import file") }
                                }
                                if (state.canReconcile) {
                                    androidx.compose.material3.OutlinedButton(onClick = actions.reconcile, modifier = Modifier.weight(1f)) { Text("Reconcile") }
                                }
                            }
                        }
                    }
                    item {
                        CentsibleCard(Modifier.animateContentSize(androidx.compose.animation.core.tween(app.centsible.core.designsystem.motion.Motion.MEDIUM)), contentPadding = PaddingValues(0.dp)) {
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
                        item {
                            TextButton(onClick = actions.loadMore, enabled = !d.loadingMore, modifier = Modifier.fillMaxWidth()) {
                                Text(if (d.loadingMore) "Loading…" else "Load more")
                            }
                        }
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
    state.importing?.let { ImportSheet(it, actions) }
    state.reconcile?.let { ReconcileDialog(it, actions) }
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
