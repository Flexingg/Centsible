package app.centsible.feature.settings

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import app.centsible.core.model.BankSyncStatus
import app.centsible.core.model.TellerAccount
import app.centsible.core.model.TellerEnrollment
import app.centsible.core.model.TellerEnrollmentResult
import app.centsible.core.model.TellerStatus

@Composable
fun TellerRoute(onBack: () -> Unit, viewModel: TellerViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TellerScreen(
        state,
        TellerActions(
            back = onBack,
            retry = { viewModel.load() },
            editSetup = viewModel::editSetup,
            onForm = viewModel::onForm,
            saveSetup = viewModel::saveSetup,
            askReset = viewModel::askReset,
            reset = { viewModel.reset() },
            connect = { viewModel.openConnect(it) },
            closeConnect = viewModel::closeConnect,
            connected = viewModel::onConnected,
            askRemove = viewModel::askRemove,
            remove = viewModel::remove,
            refreshAccounts = { viewModel.loadAccounts(true) },
            startLink = viewModel::startLink,
            linkTarget = viewModel::linkTarget,
            toggleFlip = viewModel::toggleFlip,
            link = viewModel::link,
            openOptions = viewModel::openOptions,
            setFlip = viewModel::setFlip,
            unlink = viewModel::unlink,
            messageShown = viewModel::messageShown,
        ),
    )
}

data class TellerActions(
    val back: () -> Unit = {},
    val retry: () -> Unit = {},
    val editSetup: (Boolean) -> Unit = {},
    val onForm: (TellerSetupForm) -> Unit = {},
    val saveSetup: () -> Unit = {},
    val askReset: (Boolean) -> Unit = {},
    val reset: () -> Unit = {},
    /** null adds a bank; an enrollment id reconnects that one. */
    val connect: (String?) -> Unit = {},
    val closeConnect: (String?) -> Unit = {},
    val connected: (TellerEnrollmentResult) -> Unit = {},
    val askRemove: (String?) -> Unit = {},
    val remove: () -> Unit = {},
    val refreshAccounts: () -> Unit = {},
    val startLink: (TellerAccount?) -> Unit = {},
    val linkTarget: (AccountId) -> Unit = {},
    val toggleFlip: (Boolean) -> Unit = {},
    val link: () -> Unit = {},
    val openOptions: (TellerAccount?) -> Unit = {},
    val setFlip: (Boolean) -> Unit = {},
    val unlink: () -> Unit = {},
    val messageShown: () -> Unit = {},
)

@Composable
fun TellerScreen(state: TellerUiState, actions: TellerActions) {
    val colors = CentsibleTheme.colors
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); actions.messageShown() } }
    Scaffold(
        containerColor = colors.canvas,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("Teller", style = MaterialTheme.typography.headlineSmall)
            }
        },
    ) { padding ->
        when (val s = state.status) {
            Loadable.Loading -> LoadingState(Modifier.padding(padding))
            is Loadable.Failed -> MessageState("Couldn't load Teller", s.message, emoji = "🏦", actionLabel = "Try again", onAction = actions.retry, modifier = Modifier.padding(padding))
            is Loadable.Ready -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { SetupCard(s.value, state, actions) }
                if (s.value.configured) {
                    item { BanksCard(s.value, state, actions) }
                    if (s.value.enrollments.isNotEmpty()) item { TellerAccountsCard(state, actions) }
                }
            }
        }
    }
    state.connect?.let { TellerConnectDialog(it, actions) }
    state.linking?.let { TellerLinkSheet(it, state, actions) }
    state.options?.let { TellerOptionsSheet(it, state, actions) }
    state.confirmRemove?.let { id ->
        val bank = state.ready?.enrollments?.firstOrNull { it.id == id }?.institution ?: "this bank"
        AlertDialog(
            onDismissRequest = { actions.askRemove(null) },
            title = { Text("Disconnect $bank?") },
            text = { Text("Its accounts stop syncing, in every budget, and Teller's access is revoked. Transactions already imported stay.") },
            confirmButton = { TextButton(onClick = actions.remove) { Text("Disconnect", color = colors.negative) } },
            dismissButton = { TextButton(onClick = { actions.askRemove(null) }) { Text("Cancel") } },
        )
    }
    if (state.confirmReset) {
        AlertDialog(
            onDismissRequest = { actions.askReset(false) },
            title = { Text("Remove Teller?") },
            text = { Text("The bridge forgets the certificate and every bank login, and Teller accounts stop syncing. Transactions already imported stay.") },
            confirmButton = { TextButton(onClick = actions.reset) { Text("Remove", color = colors.negative) } },
            dismissButton = { TextButton(onClick = { actions.askReset(false) }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SetupCard(s: TellerStatus, state: TellerUiState, actions: TellerActions) {
    val colors = CentsibleTheme.colors
    SectionCard("Setup") {
        if (s.configured && !state.editingSetup) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("●", color = colors.positive)
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text("Ready", style = MaterialTheme.typography.bodyLarge)
                    Text(listOfNotNull(s.environment?.let { TellerStatus.environmentLabel(it) }, s.applicationId).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                }
                if (state.isOwner) TextButton(onClick = { actions.editSetup(true) }) { Text("Change") }
            }
            if (state.isOwner) TextButton(onClick = { actions.askReset(true) }, modifier = Modifier.padding(top = 4.dp)) { Text("Remove Teller", color = colors.negative) }
            return@SectionCard
        }
        Text(
            "Teller connects banks directly, as a second option beside SimpleFIN. It's free for personal use (up to 100 bank logins) once you make a free developer account.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
        if (!state.isOwner) {
            Text("Ask the household owner to set up Teller.", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary, modifier = Modifier.padding(top = 8.dp))
            return@SectionCard
        }
        Spacer(Modifier.height(12.dp))
        TellerStep(1, "Sign up at teller.io and open the dashboard.")
        TellerStep(2, "Copy your Application ID (it starts with app_).")
        TellerStep(3, "Under Certificates, create one and download the certificate and private key. Open each file and paste its contents below.")
        val f = state.form
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(f.applicationId, { actions.onForm(f.copy(applicationId = it)) }, label = { Text("Application ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        StatLabel("Environment", Modifier.padding(top = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TellerStatus.ENVIRONMENTS.forEach { env ->
                FilterChip(selected = f.environment == env, onClick = { actions.onForm(f.copy(environment = env)) }, label = { Text(env.replaceFirstChar { it.uppercase() }) })
            }
        }
        Text(TellerStatus.environmentLabel(f.environment), style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
        if (f.environment != "sandbox") {
            val keep = s.hasCertificate
            OutlinedTextField(
                f.certificate, { actions.onForm(f.copy(certificate = it)) },
                label = { Text(if (keep) "Certificate (leave empty to keep the saved one)" else "Certificate (certificate.pem)") },
                minLines = 3, maxLines = 6,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                f.privateKey, { actions.onForm(f.copy(privateKey = it)) },
                label = { Text(if (keep) "Private key (leave empty to keep the saved one)" else "Private key (private_key.pem)") },
                minLines = 3, maxLines = 6,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Text("The key stays on your bridge, in a file only it can read. It's never shown again.", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.configured) OutlinedButton(onClick = { actions.editSetup(false) }, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(onClick = actions.saveSetup, enabled = f.applicationId.isNotBlank() && !state.busy, modifier = Modifier.weight(1f)) { Text(if (state.busy) "Saving…" else "Save") }
        }
    }
}

@Composable
private fun BanksCard(s: TellerStatus, state: TellerUiState, actions: TellerActions) {
    val colors = CentsibleTheme.colors
    SectionCard("Banks") {
        if (s.enrollments.isEmpty()) {
            Text("No banks yet. You'll sign in to your bank on Teller's page; Centsible never sees the password.", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
        s.enrollments.forEachIndexed { i, e ->
            if (i > 0) HorizontalDivider(color = colors.border)
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("●", color = if (e.status == TellerEnrollment.Status.Ok) colors.positive else colors.warning)
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
                    e.status == TellerEnrollment.Status.Disconnected && state.canEdit -> TextButton(onClick = { actions.connect(e.id) }) { Text("Reconnect") }
                    state.isOwner -> TextButton(onClick = { actions.askRemove(e.id) }) { Text("Remove") }
                }
            }
        }
        if (state.canEdit) {
            Button(onClick = { actions.connect(null) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Connect a bank") }
        }
    }
}

@Composable
private fun TellerAccountsCard(state: TellerUiState, actions: TellerActions) {
    val colors = CentsibleTheme.colors
    SectionCard("Accounts", action = "Refresh", onAction = actions.refreshAccounts) {
        when (val a = state.accounts) {
            null, Loadable.Loading -> Text("Loading accounts from Teller…", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            is Loadable.Failed -> Text(a.message, style = MaterialTheme.typography.bodyMedium, color = colors.negative)
            is Loadable.Ready -> {
                if (a.value.isEmpty()) Text("Teller doesn't see any open accounts yet.", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
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
                                listOf(acct.institution, acct.linkedAccountName?.let { "Linked to $it" } ?: "Not linked").joinToString(" · ") + if (acct.flip) " · signs reversed" else "",
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
private fun TellerLinkSheet(l: TellerLinking, state: TellerUiState, actions: TellerActions) {
    val colors = CentsibleTheme.colors
    ModalBottomSheet(onDismissRequest = { actions.startLink(null) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.card) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Text("Link ${l.account.label}", style = MaterialTheme.typography.titleLarge)
            Text(
                "Choose the account its transactions go into. If that account syncs through SimpleFIN now, it moves to Teller; what's already there is matched, not duplicated.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            if (state.linkTargets.isEmpty()) Text("Every open account already syncs through Teller.", style = MaterialTheme.typography.bodyMedium, color = colors.textTertiary)
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
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = colors.border)
            Text("Check the signs", style = MaterialTheme.typography.titleMedium)
            Text(
                "Purchases should be negative and payments or deposits positive. Some banks send card purchases the other way round; if these look backwards, reverse them.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            Row(Modifier.fillMaxWidth().toggleRow(l.flip) { actions.toggleFlip(it) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Reverse signs", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = l.flip, onCheckedChange = null)
            }
            when (val p = l.preview) {
                Loadable.Loading -> Text("Loading recent transactions…", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
                is Loadable.Failed -> Text(p.message, style = MaterialTheme.typography.bodySmall, color = colors.negative)
                is Loadable.Ready -> {
                    if (p.value.isEmpty()) Text("No transactions in the last 60 days to check against.", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
                    p.value.forEach { t ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(t.date.drop(5), style = MaterialTheme.typography.bodySmall, color = colors.textTertiary, modifier = Modifier.width(48.dp))
                            Text(t.payee, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            MoneyText(t.amount, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            Button(onClick = actions.link, enabled = l.target != null && !state.busy, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text(if (state.busy) "Linking…" else "Link and import the last 30 days")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TellerOptionsSheet(a: TellerAccount, state: TellerUiState, actions: TellerActions) {
    val colors = CentsibleTheme.colors
    ModalBottomSheet(onDismissRequest = { actions.openOptions(null) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.card) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text(a.linkedAccountName ?: a.name, style = MaterialTheme.typography.titleLarge)
            Text("Synced from ${a.label} at ${a.institution} through Teller", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            Row(Modifier.fillMaxWidth().toggleRow(a.flip, enabled = !state.busy) { actions.setFlip(it) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Reverse signs", style = MaterialTheme.typography.bodyLarge)
                    Text("For new imports. Transactions already imported keep their sign.", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                }
                Switch(checked = a.flip, onCheckedChange = null)
            }
            OutlinedButton(onClick = actions.unlink, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Unlink (transactions stay)", color = colors.negative) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TellerStep(n: Int, text: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text("$n.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Teller Connect, full screen. The page comes from the bridge (a real https origin, with the
 * Cloudflare Access headers the app already uses); its result arrives through the
 * CentsibleTeller interface, only while the bridge's page is showing. Banks that sign in
 * with OAuth open a pop-up window, shown on top until the bank closes it.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TellerConnectDialog(request: TellerConnectRequest, actions: TellerActions) {
    val colors = CentsibleTheme.colors
    val currentActions by rememberUpdatedState(actions)
    val origin = remember(request.url) { android.net.Uri.parse(request.url).let { "${it.scheme}://${it.authority}" } }
    val holder = remember { FrameHolder() }
    Dialog(onDismissRequest = { currentActions.closeConnect(null) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
                factory = { context ->
                    val main = Handler(Looper.getMainLooper())
                    val frame = FrameLayout(context)
                    holder.frame = frame
                    fun configure(w: WebView) {
                        w.settings.javaScriptEnabled = true
                        w.settings.domStorageEnabled = true
                        w.settings.setSupportMultipleWindows(true)
                        w.settings.javaScriptCanOpenWindowsAutomatically = true
                        w.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
                    }
                    val web = WebView(context)
                    configure(web)
                    web.webViewClient = WebViewClient()
                    web.webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                            val popup = WebView(context)
                            configure(popup)
                            popup.webViewClient = WebViewClient()
                            popup.webChromeClient = object : WebChromeClient() {
                                override fun onCloseWindow(window: WebView) {
                                    frame.removeView(window)
                                    window.destroy()
                                }
                            }
                            frame.addView(popup, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                            (resultMsg.obj as WebView.WebViewTransport).webView = popup
                            resultMsg.sendToTarget()
                            return true
                        }
                    }
                    web.addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun post(kind: String, json: String) {
                                main.post {
                                    // Only the bridge's own page may report a result.
                                    if (web.url?.startsWith(origin) != true) return@post
                                    when (kind) {
                                        "success" -> parseEnrollment(json)?.let(currentActions.connected) ?: currentActions.closeConnect("Teller didn't return a bank login. Try again.")
                                        "exit" -> currentActions.closeConnect(null)
                                        else -> currentActions.closeConnect(runCatching { org.json.JSONObject(json).optString("message").ifBlank { null } }.getOrNull() ?: "Teller Connect failed. Try again.")
                                    }
                                }
                            }
                        },
                        "CentsibleTeller",
                    )
                    frame.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    web.loadUrl(request.url, request.headers)
                    frame
                },
            )
            IconButton(onClick = {
                // Close an open bank pop-up first; otherwise close Teller Connect.
                val frame = holder.frame
                if (frame != null && frame.childCount > 1) {
                    val top = frame.getChildAt(frame.childCount - 1) as WebView
                    frame.removeView(top)
                    top.destroy()
                } else {
                    currentActions.closeConnect(null)
                }
            }, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(4.dp)) {
                Icon(Icons.Rounded.Close, contentDescription = "Close", tint = colors.textSecondary)
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            holder.frame?.let { frame ->
                for (i in frame.childCount - 1 downTo 0) (frame.getChildAt(i) as? WebView)?.destroy()
                frame.removeAllViews()
            }
        }
    }
}

private class FrameHolder { var frame: FrameLayout? = null }

private fun parseEnrollment(json: String): TellerEnrollmentResult? = runCatching {
    val o = org.json.JSONObject(json)
    fun str(k: String) = if (o.isNull(k)) null else o.optString(k).ifBlank { null }
    val token = str("accessToken") ?: return null
    val id = str("enrollmentId") ?: return null
    TellerEnrollmentResult(token, id, str("institution").orEmpty(), str("userId"))
}.getOrNull()
