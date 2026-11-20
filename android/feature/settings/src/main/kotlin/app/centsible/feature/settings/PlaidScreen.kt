package app.centsible.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.AccountId
import app.centsible.core.model.BankSyncStatus
import app.centsible.core.model.PlaidAccount
import app.centsible.core.model.PlaidItem
import app.centsible.core.model.PlaidLinkResult
import app.centsible.core.model.PlaidStatus
import com.plaid.link.OpenPlaidLink
import com.plaid.link.Plaid
import com.plaid.link.configuration.LinkTokenConfiguration
import com.plaid.link.result.LinkExit
import com.plaid.link.result.LinkSuccess

@Composable
fun PlaidRoute(onBack: () -> Unit, viewModel: PlaidViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PlaidScreen(
        state,
        PlaidActions(
            back = onBack,
            retry = { viewModel.load() },
            wizardTo = viewModel::wizardTo,
            editSetup = viewModel::editSetup,
            onForm = viewModel::onForm,
            saveSetup = { viewModel.saveSetup(fromWizard = it) },
            askReset = viewModel::askReset,
            reset = { viewModel.reset() },
            connect = { viewModel.connect(it) },
            launched = viewModel::launched,
            linkResult = viewModel::onLinkResult,
            askRemove = viewModel::askRemove,
            remove = viewModel::remove,
            refreshAccounts = { viewModel.loadAccounts(true) },
            startLink = viewModel::startLink,
            linkTarget = viewModel::linkTarget,
            link = viewModel::link,
            openOptions = viewModel::openOptions,
            unlink = viewModel::unlink,
            messageShown = viewModel::messageShown,
        ),
    )
}

data class PlaidActions(
    val back: () -> Unit = {},
    val retry: () -> Unit = {},
    val wizardTo: (PlaidStep?) -> Unit = {},
    val editSetup: (Boolean) -> Unit = {},
    val onForm: (PlaidSetupForm) -> Unit = {},
    /** true: saved from the guide (which then moves on). */
    val saveSetup: (Boolean) -> Unit = {},
    val askReset: (Boolean) -> Unit = {},
    val reset: () -> Unit = {},
    /** null adds a bank; an item id signs in to that one again. */
    val connect: (String?) -> Unit = {},
    val launched: () -> Unit = {},
    /** Plaid Link's outcome: a result, or null with why it closed (null when the person just closed it). */
    val linkResult: (PlaidLinkResult?, String?) -> Unit = { _, _ -> },
    val askRemove: (String?) -> Unit = {},
    val remove: () -> Unit = {},
    val refreshAccounts: () -> Unit = {},
    val startLink: (PlaidAccount?) -> Unit = {},
    val linkTarget: (AccountId) -> Unit = {},
    val link: () -> Unit = {},
    val openOptions: (PlaidAccount?) -> Unit = {},
    val unlink: () -> Unit = {},
    val messageShown: () -> Unit = {},
)

@Composable
fun PlaidScreen(state: PlaidUiState, actions: PlaidActions) {
    val colors = CentsibleTheme.colors
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); actions.messageShown() } }
    PlaidLinkLauncher(state.launch, actions)
    Scaffold(
        containerColor = colors.canvas,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text(if (state.wizard != null) "Set up Plaid" else "Plaid", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                if (state.wizard != null) TextButton(onClick = { actions.wizardTo(null); if (state.ready?.configured != true) actions.editSetup(true) }) { Text("Skip guide") }
            }
        },
    ) { padding ->
        when (val s = state.status) {
            Loadable.Loading -> LoadingState(Modifier.padding(padding))
            is Loadable.Failed -> MessageState("Couldn't load Plaid", s.message, emoji = "🏦", actionLabel = "Try again", onAction = actions.retry, modifier = Modifier.padding(padding))
            is Loadable.Ready -> if (state.wizard != null) PlaidWizard(state.wizard, s.value, state, actions, Modifier.padding(padding)) else LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { SetupCard(s.value, state, actions) }
                if (s.value.configured) {
                    item { BanksCard(s.value, state, actions) }
                    if (s.value.items.isNotEmpty()) item { PlaidAccountsCard(state, actions) }
                }
            }
        }
    }
    state.linking?.let { PlaidLinkSheet(it, state, actions) }
    state.options?.let { PlaidOptionsSheet(it, state, actions) }
    state.confirmRemove?.let { id ->
        val bank = state.ready?.items?.firstOrNull { it.id == id }?.institution ?: "this bank"
        AlertDialog(
            onDismissRequest = { actions.askRemove(null) },
            title = { Text("Disconnect $bank?") },
            text = { Text("Its accounts stop syncing, in every budget, and Plaid's access ends. Transactions already imported stay.") },
            confirmButton = { TextButton(onClick = actions.remove) { Text("Disconnect", color = colors.negative) } },
            dismissButton = { TextButton(onClick = { actions.askRemove(null) }) { Text("Cancel") } },
        )
    }
    if (state.confirmReset) {
        AlertDialog(
            onDismissRequest = { actions.askReset(false) },
            title = { Text("Remove Plaid?") },
            text = { Text("The bridge forgets the keys and every bank login, and Plaid accounts stop syncing. Transactions already imported stay.") },
            confirmButton = { TextButton(onClick = actions.reset) { Text("Remove", color = colors.negative) } },
            dismissButton = { TextButton(onClick = { actions.askReset(false) }) { Text("Cancel") } },
        )
    }
}

/**
 * Opens Plaid Link (Plaid's own sign-in screens, from its Android SDK) when [launch] is set.
 * Banks that sign in through their own app or website (Wells Fargo, Chase, …) hand back to
 * Centsible through the package name allowed in the Plaid dashboard.
 */
@Composable
private fun PlaidLinkLauncher(launch: PlaidLaunch?, actions: PlaidActions) {
    val context = LocalContext.current
    val current by rememberUpdatedState(actions)
    val repairing by rememberUpdatedState(launch?.itemId)
    val launcher = rememberLauncherForActivityResult(OpenPlaidLink()) { result ->
        when (result) {
            is LinkSuccess -> current.linkResult(
                PlaidLinkResult(
                    publicToken = if (repairing == null) result.publicToken else null,
                    institution = result.metadata.institution?.name,
                    itemId = repairing,
                ),
                null,
            )
            is LinkExit -> current.linkResult(null, result.error?.let { it.displayMessage?.ifBlank { null } ?: it.errorMessage.ifBlank { null } ?: "Plaid couldn't connect the bank. Try again." })
            else -> current.linkResult(null, null)
        }
    }
    LaunchedEffect(launch) {
        val l = launch ?: return@LaunchedEffect
        actions.launched()
        runCatching {
            launcher.launch(Plaid.createPlaidLinkSession(context, LinkTokenConfiguration.Builder().token(l.linkToken).build()))
        }.onFailure { e -> current.linkResult(null, "Couldn't open Plaid: ${e.message ?: e::class.simpleName}") }
    }
}

@Composable
private fun SetupCard(s: PlaidStatus, state: PlaidUiState, actions: PlaidActions) {
    val colors = CentsibleTheme.colors
    SectionCard("Setup") {
        if (s.configured && !state.editingSetup) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("●", color = colors.positive)
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text("Ready", style = MaterialTheme.typography.bodyLarge)
                    Text(listOfNotNull(s.environment?.let { PlaidStatus.environmentLabel(it) }, s.clientId).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (state.isOwner) TextButton(onClick = { actions.editSetup(true) }) { Text("Change") }
            }
            if (state.isOwner) {
                TextButton(onClick = { actions.wizardTo(PlaidStep.AllowApp) }) { Text("Bank sign-in doesn't come back to the app?") }
                TextButton(onClick = { actions.askReset(true) }) { Text("Remove Plaid", color = colors.negative) }
            }
            return@SectionCard
        }
        Text(
            "Plaid connects banks directly, as a second option beside SimpleFIN. Plaid's free Trial plan covers up to 10 bank logins with real banks, including Wells Fargo, Chase and Discover.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
        if (!state.isOwner) {
            Text("Ask the household owner to set up Plaid.", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary, modifier = Modifier.padding(top = 8.dp))
            return@SectionCard
        }
        PlaidKeysForm(s, state, actions)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.configured) OutlinedButton(onClick = { actions.editSetup(false) }, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(onClick = { actions.saveSetup(false) }, enabled = state.form.clientId.isNotBlank() && (s.configured || state.form.secret.isNotBlank()) && !state.busy, modifier = Modifier.weight(1f)) {
                Text(if (state.busy) "Checking with Plaid…" else "Save")
            }
        }
    }
}

/** Client id, secret and environment; used by the setup card and the guide. */
@Composable
internal fun PlaidKeysForm(s: PlaidStatus, state: PlaidUiState, actions: PlaidActions) {
    val colors = CentsibleTheme.colors
    val f = state.form
    OutlinedTextField(
        f.clientId, { actions.onForm(f.copy(clientId = it.trim())) },
        label = { Text("client_id") }, singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    OutlinedTextField(
        f.secret, { actions.onForm(f.copy(secret = it.trim())) },
        label = { Text(if (s.configured) "Secret (leave empty to keep the saved one)" else "Secret") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    StatLabel("Environment", Modifier.padding(top = 12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PlaidStatus.ENVIRONMENTS.forEach { env ->
            FilterChip(selected = f.environment == env, onClick = { actions.onForm(f.copy(environment = env)) }, label = { Text(env.replaceFirstChar { it.uppercase() }) })
        }
    }
    Text(
        PlaidStatus.environmentLabel(f.environment) + ". Each environment has its own secret in the dashboard.",
        style = MaterialTheme.typography.bodySmall,
        color = colors.textTertiary,
    )
    Text("The secret stays on your bridge, in a file only it can read. It's never shown again.", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun BanksCard(s: PlaidStatus, state: PlaidUiState, actions: PlaidActions) {
    val colors = CentsibleTheme.colors
    SectionCard("Banks") {
        if (s.items.isEmpty()) {
            Text("No banks yet. You'll sign in to your bank in Plaid's window; Centsible never sees the password.", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
        s.items.forEachIndexed { i, e ->
            if (i > 0) HorizontalDivider(color = colors.border)
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("●", color = if (e.status == PlaidItem.Status.Ok) colors.positive else colors.warning)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.institution, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        e.message ?: when (e.linkedAccounts) { 0 -> "Connected · no accounts linked yet"; 1 -> "Connected · 1 account linked"; else -> "Connected · ${e.linkedAccounts} accounts linked" },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (e.message != null) colors.warning else colors.textSecondary,
                    )
                }
                when {
                    e.status == PlaidItem.Status.Reauth && state.canEdit -> TextButton(onClick = { actions.connect(e.id) }, enabled = !state.busy) { Text("Reconnect") }
                    state.isOwner -> TextButton(onClick = { actions.askRemove(e.id) }) { Text("Remove") }
                }
            }
        }
        if (state.canEdit) {
            Button(onClick = { actions.connect(null) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(if (state.busy && state.launch == null) "Opening Plaid…" else "Connect a bank")
            }
        }
    }
}

@Composable
private fun PlaidAccountsCard(state: PlaidUiState, actions: PlaidActions) {
    val colors = CentsibleTheme.colors
    SectionCard("Accounts", action = "Refresh", onAction = actions.refreshAccounts) {
        when (val a = state.accounts) {
            null, Loadable.Loading -> Text("Loading accounts from Plaid…", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            is Loadable.Failed -> Text(a.message, style = MaterialTheme.typography.bodyMedium, color = colors.negative)
            is Loadable.Ready -> {
                if (a.value.isEmpty()) Text("Plaid doesn't see any accounts yet.", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                a.value.forEachIndexed { i, acct ->
                    if (i > 0) HorizontalDivider(color = colors.border)
                    val local = state.localAccounts.firstOrNull { it.id == acct.linkedAccountId }
                    val problem = BankSyncStatus.describe(local?.bankSyncStatus)
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = state.canEdit) { if (acct.linkedAccountId != null) actions.openOptions(acct) else actions.startLink(acct) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(acct.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(acct.institution, acct.linkedAccountName?.let { "Linked to $it" } ?: "Not linked").joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (acct.linkedAccountId != null) colors.textSecondary else colors.textTertiary,
                            )
                            problem?.let { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = colors.warning) }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            acct.balance?.let { MoneyText(it, style = MaterialTheme.typography.bodyMedium) }
                            if (state.canEdit) Text(if (acct.linkedAccountId != null) "Options" else "Link", style = MaterialTheme.typography.labelMedium, color = colors.accent)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaidLinkSheet(l: PlaidLinking, state: PlaidUiState, actions: PlaidActions) {
    val colors = CentsibleTheme.colors
    ModalBottomSheet(onDismissRequest = { actions.startLink(null) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.card) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Text("Link ${l.account.label}", style = MaterialTheme.typography.titleLarge)
            Text(
                "Choose the account its transactions go into. If that account syncs through SimpleFIN now, it moves to Plaid; what's already there is matched, not duplicated.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            if (state.linkTargets.isEmpty()) Text("Every open account already syncs through Plaid.", style = MaterialTheme.typography.bodyMedium, color = colors.textTertiary)
            state.linkTargets.forEach { a ->
                Row(Modifier.fillMaxWidth().selectable(l.target == a.id, role = Role.RadioButton) { actions.linkTarget(a.id) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = l.target == a.id, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(a.name, style = MaterialTheme.typography.bodyLarge)
                        if (a.syncSource != null) Text("Syncs through ${if (a.syncSource == "simpleFin") "SimpleFIN" else a.syncSource} now", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
                    }
                }
            }
            Text(
                "Purchases come in negative and payments or deposits positive, for cards too. Pending transactions wait until they post.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textTertiary,
                modifier = Modifier.padding(top = 12.dp),
            )
            Button(onClick = actions.link, enabled = l.target != null && !state.busy, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text(if (state.busy) "Linking…" else "Link and import the last 30 days")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaidOptionsSheet(a: PlaidAccount, state: PlaidUiState, actions: PlaidActions) {
    val colors = CentsibleTheme.colors
    ModalBottomSheet(onDismissRequest = { actions.openOptions(null) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.card) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text(a.linkedAccountName ?: a.name, style = MaterialTheme.typography.titleLarge)
            Text("Synced from ${a.label} at ${a.institution} through Plaid", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, modifier = Modifier.padding(bottom = 16.dp))
            OutlinedButton(onClick = actions.unlink, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Unlink (transactions stay)", color = colors.negative) }
            Spacer(Modifier.height(24.dp))
        }
    }
}
