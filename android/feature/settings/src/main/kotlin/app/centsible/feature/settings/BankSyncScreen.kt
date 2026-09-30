package app.centsible.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.component.LoadingState
import app.centsible.core.designsystem.component.MessageState
import app.centsible.core.designsystem.component.MoneyText
import app.centsible.core.designsystem.component.SectionCard
import app.centsible.core.designsystem.component.StatLabel
import app.centsible.core.designsystem.component.toggleRow
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.AccountId
import app.centsible.core.model.Backfill
import app.centsible.core.model.BankSyncOverview
import app.centsible.core.model.BankSyncSettings
import app.centsible.core.model.BankSyncStatus
import app.centsible.core.model.ExternalAccount
import app.centsible.core.model.FieldMapping

@Composable
fun BankSyncRoute(onBack: () -> Unit, viewModel: BankSyncViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BankSyncScreen(
        state,
        BankSyncActions(
            back = onBack,
            retry = { viewModel.load() },
            onToken = viewModel::onToken,
            connect = viewModel::connect,
            askDisconnect = viewModel::askDisconnect,
            disconnect = { viewModel.disconnect() },
            refreshAccounts = { viewModel.loadAccounts(true) },
            startLink = viewModel::startLink,
            link = viewModel::link,
            openOptions = viewModel::openOptions,
            saveOptions = viewModel::saveOptions,
            unlink = viewModel::unlink,
            setSchedule = { viewModel.setSchedule(it) },
            syncAll = viewModel::syncAll,
            historyYears = viewModel::onHistoryYears,
            startBackfill = { viewModel.startBackfill() },
            cancelBackfill = { viewModel.cancelBackfill() },
            messageShown = viewModel::messageShown,
        ),
    )
}

data class BankSyncActions(
    val back: () -> Unit = {},
    val retry: () -> Unit = {},
    val onToken: (String) -> Unit = {},
    val connect: () -> Unit = {},
    val askDisconnect: (Boolean) -> Unit = {},
    val disconnect: () -> Unit = {},
    val refreshAccounts: () -> Unit = {},
    val startLink: (ExternalAccount?) -> Unit = {},
    val link: (AccountId?, Boolean) -> Unit = { _, _ -> },
    val openOptions: (ExternalAccount?) -> Unit = {},
    val saveOptions: (BankSyncSettings) -> Unit = {},
    val unlink: () -> Unit = {},
    val setSchedule: (Int) -> Unit = {},
    val syncAll: () -> Unit = {},
    val historyYears: (Int) -> Unit = {},
    val startBackfill: () -> Unit = {},
    val cancelBackfill: () -> Unit = {},
    val messageShown: () -> Unit = {},
)

@Composable
fun BankSyncScreen(state: BankSyncUiState, actions: BankSyncActions) {
    val colors = CentsibleTheme.colors
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); actions.messageShown() } }
    Scaffold(
        containerColor = colors.canvas,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("Bank sync", style = MaterialTheme.typography.headlineSmall)
            }
        },
    ) { padding ->
        when (val o = state.overview) {
            Loadable.Loading -> LoadingState(Modifier.padding(padding))
            is Loadable.Failed -> MessageState("Couldn't load bank sync", o.message, emoji = "🏦", actionLabel = "Try again", onAction = actions.retry, modifier = Modifier.padding(padding))
            is Loadable.Ready -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { SimpleFinCard(o.value, state, actions) }
                if (o.value.simplefinConfigured) {
                    item { AccountsCard(state, actions) }
                    item { SyncCard(o.value, state, actions) }
                    item { HistoryCard(o.value, state, actions) }
                }
            }
        }
    }
    state.linking?.let { LinkSheet(it, state, actions) }
    state.options?.let { OptionsSheet(it, state, actions) }
    if (state.confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { actions.askDisconnect(false) },
            title = { Text("Disconnect SimpleFIN?") },
            text = { Text("Linked accounts stop syncing until you connect again with a new setup token. Transactions already imported stay.") },
            confirmButton = { TextButton(onClick = actions.disconnect) { Text("Disconnect", color = colors.negative) } },
            dismissButton = { TextButton(onClick = { actions.askDisconnect(false) }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SimpleFinCard(o: BankSyncOverview, state: BankSyncUiState, actions: BankSyncActions) {
    val colors = CentsibleTheme.colors
    SectionCard("SimpleFIN") {
        if (o.simplefinConfigured) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("●", color = colors.positive)
                Spacer(Modifier.width(6.dp))
                Text("Connected", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                if (state.isOwner) TextButton(onClick = { actions.askDisconnect(true) }) { Text("Disconnect") }
            }
            val nearLimit = o.requestsToday >= o.dailyQuota - 4
            Text(
                "${o.requestsToday} of ${o.dailyQuota} SimpleFIN requests in the last day" + if (nearLimit) ". Close to SimpleFIN's limit: sync less often." else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (nearLimit) colors.warning else colors.textSecondary,
            )
            return@SectionCard
        }
        Text(
            "SimpleFIN connects your banks to Actual for about \$15 a year. Once connected, your accounts sync automatically.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
        if (!state.isOwner) {
            Text("Ask the household owner to connect SimpleFIN.", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary, modifier = Modifier.padding(top = 8.dp))
            return@SectionCard
        }
        Spacer(Modifier.height(12.dp))
        Step(1, "Sign up or sign in at bridge.simplefin.org and connect your banks.")
        Step(2, "Under Apps, choose New connection and copy the setup token.")
        Step(3, "Paste it here. It works once; the bridge keeps the access it grants.")
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            state.setupToken, actions.onToken,
            label = { Text("Setup token") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = actions.connect, enabled = state.setupToken.isNotBlank() && !state.connecting, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            if (state.connecting) app.centsible.core.designsystem.component.DialSpinner(size = 20.dp, track = colors.card.copy(alpha = 0.3f)) else Text("Connect")
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text("$n.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AccountsCard(state: BankSyncUiState, actions: BankSyncActions) {
    val colors = CentsibleTheme.colors
    SectionCard("Accounts", action = "Refresh", onAction = actions.refreshAccounts) {
        when (val e = state.external) {
            null, Loadable.Loading -> Text("Loading accounts from SimpleFIN…", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            is Loadable.Failed -> Text(e.message, style = MaterialTheme.typography.bodyMedium, color = colors.negative)
            is Loadable.Ready -> {
                if (e.value.isEmpty()) {
                    Text("No accounts in your SimpleFIN connection yet. Add banks at bridge.simplefin.org, then refresh.", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                }
                e.value.forEachIndexed { i, a ->
                    if (i > 0) HorizontalDivider(color = colors.border)
                    val local = state.localAccounts.firstOrNull { it.id == a.linkedAccountId }
                    val problem = BankSyncStatus.describe(local?.bankSyncStatus)
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = state.canEdit) { if (a.linkedAccountId != null) actions.openOptions(a) else actions.startLink(a) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(a.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(a.institution, a.linkedAccountName?.let { "Linked to $it" } ?: "Not linked").joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (a.linkedAccountId != null) colors.textSecondary else colors.textTertiary,
                            )
                            problem?.let { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = colors.warning) }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            MoneyText(a.balance, style = MaterialTheme.typography.bodyMedium)
                            if (state.canEdit) Text(if (a.linkedAccountId != null) "Options" else "Link", style = MaterialTheme.typography.labelMedium, color = colors.accent)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncCard(o: BankSyncOverview, state: BankSyncUiState, actions: BankSyncActions) {
    val colors = CentsibleTheme.colors
    SectionCard("Sync") {
        Button(onClick = actions.syncAll, enabled = state.canEdit && !state.syncing, modifier = Modifier.fillMaxWidth()) {
            if (state.syncing) { app.centsible.core.designsystem.component.DialSpinner(size = 18.dp); androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp)) }
            Text(if (state.syncing) "Syncing…" else "Sync all accounts now")
        }
        Text("One SimpleFIN request covers every SimpleFIN account.", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary, modifier = Modifier.padding(top = 4.dp))
        state.syncResults?.let { results ->
            Spacer(Modifier.height(8.dp))
            results.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(r.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val short = if (r.error != null) "Didn't sync" else when (r.newTransactions) { 0 -> "Up to date"; 1 -> "1 new"; else -> "${r.newTransactions} new" }
                    Text(short, style = MaterialTheme.typography.bodySmall, color = if (r.error != null) colors.negative else colors.textSecondary)
                }
                r.error?.let { Text(BankSyncStatus.describe(r.status) ?: it, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary) }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = colors.border)
        Text("Background sync", style = MaterialTheme.typography.bodyLarge)
        Text(
            "The bridge syncs every linked account on its own, even when phones are off. SimpleFIN updates bank data about once a day.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            o.intervals.forEach { h ->
                FilterChip(
                    selected = o.schedule.intervalHours == h,
                    onClick = { actions.setSchedule(h) },
                    enabled = state.isOwner && !state.busy,
                    label = { Text(if (h == 0) "Off" else "${h}h") },
                )
            }
        }
        if (!state.isOwner) Text("Only the owner can change this.", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
        val s = o.schedule
        if (s.intervalHours > 0) {
            Text(
                listOfNotNull(s.lastRunAt?.let { "Last run ${it.take(16).replace('T', ' ')}" }, s.nextRunAt?.let { "next ${it.take(16).replace('T', ' ')}" }).joinToString(", ").ifEmpty { "Runs within a minute" },
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        s.lastResult?.let { r ->
            val text = r.skipped ?: buildString {
                append(when (r.newTransactions) { 0 -> "Last run: up to date"; 1 -> "Last run: 1 new transaction"; else -> "Last run: ${r.newTransactions} new transactions" })
                if (r.errors.isNotEmpty()) append(" · ${r.errors.size} problem${if (r.errors.size == 1) "" else "s"}")
            }
            Text(text, style = MaterialTheme.typography.bodySmall, color = if (r.skipped != null || r.errors.isNotEmpty()) colors.warning else colors.textSecondary)
            r.errors.take(3).forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = colors.warning) }
        }
    }
}

/** Older history than Actual's 90 days, fetched by the bridge 90 days per SimpleFIN request. */
@Composable
private fun HistoryCard(o: BankSyncOverview, state: BankSyncUiState, actions: BankSyncActions) {
    val colors = CentsibleTheme.colors
    SectionCard("Older history") {
        val b = o.backfill
        if (b != null && b.active) {
            Text("Importing history back to ${b.since}", style = MaterialTheme.typography.bodyLarge)
            LinearProgressIndicator(
                progress = { b.progress },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                color = colors.accent,
                trackColor = colors.border,
            )
            Text(
                "Reached ${b.reachedDate} · ${plural(b.transactionsAdded, "transaction")} added · ${b.windowsDone} of about ${b.windowsTotal} requests",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            b.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (b.status == Backfill.Status.Waiting) colors.warning else colors.textSecondary, modifier = Modifier.padding(top = 4.dp)) }
            if (state.isOwner) {
                OutlinedButton(onClick = actions.cancelBackfill, enabled = !state.busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Stop importing") }
            }
            return@SectionCard
        }
        Text(
            "Syncing brings in the last 90 days. Import older transactions from your banks here: SimpleFIN hands out 90 days per request, so the bridge walks back a " +
                "window at a time within its daily limit and keeps going on its own. Ten years can take a few days. Many banks only keep a year or two; the import " +
                "stops when yours runs out. Opening balances move back so today's balances stay the same.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
        b?.message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = if (b.status == Backfill.Status.Failed) colors.negative else colors.textPrimary, modifier = Modifier.padding(top = 8.dp))
        }
        if (!state.isOwner) {
            Text("Only the owner can import history.", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary, modifier = Modifier.padding(top = 8.dp))
            return@SectionCard
        }
        if (!o.historyAccess) {
            // Connections made before this existed: the bridge never saw the access, only Actual did.
            Text(
                "SimpleFIN was connected in Actual, which keeps the connection to itself. Two ways to import history:\n\n" +
                    "• No new token: add one line to your docker-compose.yml, under the bridge's volumes: \"- ./actual-data:/actual-data:ro\", and ACTUAL_DATA_DIR: /actual-data under its environment (the compose file in the latest release has both), then run docker compose up -d.\n\n" +
                    "• Or reconnect once here with a new setup token from bridge.simplefin.org (Apps → New connection). If an account then shows as not linked, link it to the same account again; nothing is lost.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            OutlinedTextField(
                state.setupToken, actions.onToken,
                label = { Text("New setup token") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Button(onClick = actions.connect, enabled = state.setupToken.isNotBlank() && !state.connecting, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(if (state.connecting) "Reconnecting…" else "Reconnect")
            }
            return@SectionCard
        }
        StatLabel("How far back", Modifier.padding(top = 12.dp, bottom = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Backfill.YEARS.forEach { y ->
                FilterChip(selected = state.historyYears == y, onClick = { actions.historyYears(y) }, label = { Text("${y}y") })
            }
        }
        Button(onClick = actions.startBackfill, enabled = !state.busy && state.simpleFinLinked > 0, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("Import ${plural(state.historyYears, "year")} of history")
        }
        if (state.simpleFinLinked == 0) Text("Link an account first.", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
    }
}

private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkSheet(external: ExternalAccount, state: BankSyncUiState, actions: BankSyncActions) {
    val colors = CentsibleTheme.colors
    var target by remember(external.id) { mutableStateOf<AccountId?>(null) }
    var offBudget by remember(external.id) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { actions.startLink(null) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.card) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Text("Link ${external.name}", style = MaterialTheme.typography.titleLarge)
            Text(
                "Choose where its transactions go. Linking an existing account keeps its history, and bank transactions are matched against what's already there.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            ChoiceRow("Create a new account", selected = target == null) { target = null }
            if (target == null) {
                Row(Modifier.fillMaxWidth().toggleRow(offBudget) { offBudget = it }.padding(start = 48.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Track only (off budget)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = offBudget, onCheckedChange = null)
                }
            }
            if (state.linkTargets.isNotEmpty()) {
                StatLabel("Or link to an existing account", Modifier.padding(top = 8.dp))
                state.linkTargets.forEach { a -> ChoiceRow(a.name, selected = target == a.id) { target = a.id } }
            }
            Button(onClick = { actions.link(target, offBudget) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text(if (state.busy) "Linking…" else "Link")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionsSheet(options: AccountOptions, state: BankSyncUiState, actions: BankSyncActions) {
    val colors = CentsibleTheme.colors
    var confirmUnlink by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { actions.openOptions(null) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.card) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Text(options.external.linkedAccountName ?: options.external.name, style = MaterialTheme.typography.titleLarge)
            Text("Synced from ${options.external.name}" + (options.external.institution?.let { " at $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            val settings = options.settings
            if (settings == null) {
                Text("Loading…", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary, modifier = Modifier.padding(vertical = 16.dp))
            } else {
                var s by remember(settings) { mutableStateOf(settings) }
                Spacer(Modifier.height(8.dp))
                OptionRow("Import transactions", "Off keeps only the balance in step", s.importTransactions) { s = s.copy(importTransactions = it) }
                OptionRow("Import pending transactions", "Shown uncleared until the bank posts them", s.importPending) { s = s.copy(importPending = it) }
                OptionRow("Import descriptions as notes", null, s.importNotes) { s = s.copy(importNotes = it) }
                OptionRow("Bring back deleted transactions", "If you delete an imported transaction, the next sync adds it again", s.reimportDeleted) { s = s.copy(reimportDeleted = it) }
                OptionRow("Update dates of imported transactions", "When the bank changes a date later", s.updateDates) { s = s.copy(updateDates = it) }
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = colors.border)
                Text("Where fields come from", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Some banks put the merchant in the description. Swap them here if payees look wrong.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
                MappingRows("Payments (money out)", s.payment) { s = s.copy(payment = it) }
                MappingRows("Deposits (money in)", s.deposit) { s = s.copy(deposit = it) }
                Button(onClick = { actions.saveOptions(s) }, enabled = s != settings && !state.busy, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Save") }
            }
            OutlinedButton(onClick = { confirmUnlink = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Unlink from SimpleFIN", color = colors.negative)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (confirmUnlink) {
        AlertDialog(
            onDismissRequest = { confirmUnlink = false },
            title = { Text("Unlink this account?") },
            text = { Text("It stops syncing. Transactions already imported stay, and you can link it again later.") },
            confirmButton = { TextButton(onClick = { confirmUnlink = false; actions.unlink() }) { Text("Unlink", color = colors.negative) } },
            dismissButton = { TextButton(onClick = { confirmUnlink = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun OptionRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleRow(checked, onChange = onChange).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = CentsibleTheme.colors.textSecondary) }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun MappingRows(title: String, mapping: FieldMapping, onChange: (FieldMapping) -> Unit) {
    StatLabel(title, Modifier.padding(top = 12.dp, bottom = 4.dp))
    FieldPicker("Date", mapping.date, FieldMapping.DATE_FIELDS) { onChange(mapping.copy(date = it)) }
    FieldPicker("Payee", mapping.payee, FieldMapping.TEXT_FIELDS) { onChange(mapping.copy(payee = it)) }
    FieldPicker("Notes", mapping.notes, FieldMapping.TEXT_FIELDS) { onChange(mapping.copy(notes = it)) }
}

@Composable
private fun FieldPicker(label: String, value: String, options: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(72.dp))
        Column {
            AssistChip(onClick = { open = true }, label = { Text(FieldMapping.label(value)) })
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { o -> DropdownMenuItem(text = { Text(FieldMapping.label(o)) }, onClick = { open = false; onPick(o) }) }
            }
        }
    }
}
