package app.centsible.feature.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.component.LoadingState
import app.centsible.core.designsystem.component.MessageState
import app.centsible.core.designsystem.component.SectionCard
import app.centsible.core.designsystem.component.StatLabel
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.Backup
import app.centsible.core.model.BackupOverview
import app.centsible.core.model.ServerStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ServerRoute(onBack: () -> Unit, viewModel: ServerViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pending by remember { mutableStateOf<PendingSave?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val p = pending
        if (uri != null && p != null) viewModel.save(p, uri)
        pending = null
    }
    ServerScreen(
        state,
        ServerActions(
            back = onBack,
            refresh = { viewModel.load(true) },
            downloadApp = viewModel::downloadApp,
            installApp = { uri ->
                // Android asks once whether Centsible may install updates.
                if (AppUpdater.canInstall(context)) context.startActivity(AppUpdater.installIntent(uri))
                else context.startActivity(AppUpdater.allowInstallsIntent(context))
            },
            askUpdate = viewModel::askUpdate,
            updateServer = viewModel::updateServer,
            setSchedule = { h, k -> viewModel.setSchedule(h, k) },
            backUpNow = { viewModel.backUpNow() },
            openBackup = viewModel::openBackup,
            deleteBackup = { viewModel.delete(it) },
            save = { p -> pending = p; saver.launch(p.suggestedName) },
            askRestore = viewModel::askRestore,
            restore = viewModel::restore,
            messageShown = viewModel::messageShown,
        ),
    )
}

data class ServerActions(
    val back: () -> Unit = {},
    val refresh: () -> Unit = {},
    val downloadApp: () -> Unit = {},
    val installApp: (Uri) -> Unit = {},
    val askUpdate: (Boolean) -> Unit = {},
    val updateServer: () -> Unit = {},
    val setSchedule: (Int?, Int?) -> Unit = { _, _ -> },
    val backUpNow: () -> Unit = {},
    val openBackup: (Backup?) -> Unit = {},
    val deleteBackup: (Backup) -> Unit = {},
    val save: (PendingSave) -> Unit = {},
    val askRestore: (Pair<Backup, app.centsible.core.model.BackupBudget>?) -> Unit = {},
    val restore: () -> Unit = {},
    val messageShown: () -> Unit = {},
)

private val WHEN = DateTimeFormatter.ofPattern("MMM d, h:mm a")
internal fun localTime(iso: String?): String? = iso?.let { runCatching { Instant.parse(it).atZone(ZoneId.systemDefault()).format(WHEN) }.getOrNull() }
internal fun size(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1e3)
    else -> "$bytes B"
}

@Composable
fun ServerScreen(state: ServerUiState, actions: ServerActions) {
    val colors = CentsibleTheme.colors
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); actions.messageShown() } }
    Scaffold(
        containerColor = colors.canvas,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("Server", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = actions.refresh) { Text("Check now") }
            }
        },
    ) { padding ->
        when (val s = state.status) {
            Loadable.Loading -> LoadingState(Modifier.padding(padding))
            is Loadable.Failed -> MessageState("Couldn't reach the server", s.message, emoji = "🖥️", actionLabel = "Try again", onAction = actions.refresh, modifier = Modifier.padding(padding))
            is Loadable.Ready -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { AppCard(s.value, state, actions) }
                item { ServerCard(s.value, state, actions) }
                state.backups?.let { b -> item { BackupsCard(b, state, actions) } }
            }
        }
    }
    state.openBackup?.let { BackupSheet(it, state, actions) }
    if (state.confirmUpdate) {
        val s = (state.status as? Loadable.Ready)?.value
        AlertDialog(
            onDismissRequest = { actions.askUpdate(false) },
            title = { Text("Update the server?") },
            text = {
                Text(
                    "The bridge goes from ${s?.bridgeVersion} to ${s?.bridgeLatest}" +
                        (if (s != null && s.actualVersion != s.actualPairedVersion) " and Actual to ${s.actualPairedVersion}" else "") +
                        ". Everyone's app pauses for about a minute while it restarts. Budgets aren't touched, and a backup is a tap away below if you'd like one first.",
                )
            },
            confirmButton = { TextButton(onClick = actions.updateServer) { Text("Update") } },
            dismissButton = { TextButton(onClick = { actions.askUpdate(false) }) { Text("Not now") } },
        )
    }
    state.confirmRestore?.let { (backup, budget) ->
        AlertDialog(
            onDismissRequest = { actions.askRestore(null) },
            title = { Text("Restore ${budget.name}?") },
            text = { Text("It's added as a new budget, \"${budget.name} (restored ${backup.createdAt.take(10)})\", next to the current one. Nothing existing changes.") },
            confirmButton = { TextButton(onClick = actions.restore) { Text("Restore a copy") } },
            dismissButton = { TextButton(onClick = { actions.askRestore(null) }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Row2(label: String, value: String, warn: Boolean = false) {
    val colors = CentsibleTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = if (warn) colors.warning else colors.textPrimary)
    }
}

@Composable
private fun AppCard(s: ServerStatus, state: ServerUiState, actions: ServerActions) {
    val colors = CentsibleTheme.colors
    SectionCard("This app") {
        Row2("Installed", state.installedVersion)
        val update = state.appUpdate
        if (update == null) {
            Row2("Newest", s.app?.version ?: "Couldn't check")
            Text("You're up to date.", style = MaterialTheme.typography.bodySmall, color = colors.positive, modifier = Modifier.padding(top = 4.dp))
            return@SectionCard
        }
        Row2("Available", update.version)
        when (val d = state.download) {
            AppDownload.Idle -> Button(onClick = actions.downloadApp, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Download update") }
            is AppDownload.Running -> {
                Text("Downloading…", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, modifier = Modifier.padding(top = 8.dp))
                if (d.progress != null) {
                    LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), color = colors.accent, trackColor = colors.border)
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), color = colors.accent, trackColor = colors.border)
                }
            }
            is AppDownload.Ready -> {
                Button(onClick = { actions.installApp(d.uri) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Install ${update.version}") }
                Text(
                    "The first time, Android asks you to allow Centsible to install updates. Allow it, come back, and tap Install again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            is AppDownload.Failed -> {
                Text(d.message, style = MaterialTheme.typography.bodySmall, color = colors.negative, modifier = Modifier.padding(top = 8.dp))
                OutlinedButton(onClick = actions.downloadApp, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
            }
        }
    }
}

@Composable
private fun ServerCard(s: ServerStatus, state: ServerUiState, actions: ServerActions) {
    val colors = CentsibleTheme.colors
    SectionCard("Bridge and Actual") {
        Row2("Bridge", s.bridgeVersion)
        Row2("Actual server", s.actualVersion ?: "Not reachable", warn = !s.actualConnected)
        if (s.actualCompatibility != "ok" && s.actualVersion != null) {
            Text(
                "Actual ${s.actualVersion} doesn't match what this bridge is built for (${s.actualPairedVersion}). Updating the server brings them in line.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.warning,
            )
        }
        Row2("Running for", uptime(s.uptimeSeconds))
        val free = s.diskFree
        val total = s.diskTotal
        if (free != null && total != null) Row2("Free space", "${size(free)} of ${size(total)}", warn = free < 1_000_000_000)
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = colors.border)
        when {
            state.updatingFrom != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp, color = colors.accent)
                Spacer(Modifier.width(12.dp))
                Text("Updating… the server restarts, so this takes a minute or two.", style = MaterialTheme.typography.bodyMedium)
            }
            s.bridgeUpdateAvailable -> {
                Text("Update available: ${s.bridgeLatest}", style = MaterialTheme.typography.bodyLarge)
                if (!state.isOwner) {
                    Text("The household owner can install it.", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                } else if (s.updaterConfigured) {
                    Button(onClick = { actions.askUpdate(true) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Update server") }
                } else {
                    Text(
                        "For one-tap updates, replace docker-compose.yml on the server with the one from the newest release, then run: docker compose up -d. Until then: docker compose pull && docker compose up -d",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
            }
            else -> Text("The server is up to date.", style = MaterialTheme.typography.bodySmall, color = colors.positive)
        }
        if (s.actualNewestRelease != null && s.actualNewestRelease != s.actualPairedVersion) {
            Text(
                "Actual ${s.actualNewestRelease} is out; Centsible moves to it once it's been tested.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textTertiary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        s.lastUpdateResult?.takeIf { it != "done" && state.updatingFrom == null }?.let {
            Text("Last update: $it", style = MaterialTheme.typography.bodySmall, color = colors.warning, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

private fun uptime(seconds: Long) = when {
    seconds >= 86_400 -> "${seconds / 86_400} days"
    seconds >= 3_600 -> "${seconds / 3_600} hours"
    else -> "${(seconds / 60).coerceAtLeast(1)} minutes"
}

@Composable
private fun BackupsCard(b: Loadable<BackupOverview>, state: ServerUiState, actions: ServerActions) {
    val colors = CentsibleTheme.colors
    SectionCard("Backups") {
        when (b) {
            Loadable.Loading -> Text("Loading…", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            is Loadable.Failed -> Text(b.message, style = MaterialTheme.typography.bodyMedium, color = colors.negative)
            is Loadable.Ready -> {
                val o = b.value
                Text(
                    "Every budget, as the same file Actual's web app exports and imports, plus the household's members and devices. Kept on the server in bridge-data/backups.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
                StatLabel("Automatically", Modifier.padding(top = 12.dp, bottom = 4.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    o.intervals.forEach { h ->
                        FilterChip(
                            selected = o.intervalHours == h,
                            onClick = { actions.setSchedule(h, null) },
                            enabled = !state.busy,
                            label = { Text(when (h) { 0 -> "Off"; 24 -> "Daily"; 168 -> "Weekly"; else -> "Every ${h}h" }) },
                        )
                    }
                }
                StatLabel("Keep", Modifier.padding(top = 8.dp, bottom = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(7, 14, 30).forEach { k ->
                        FilterChip(selected = o.keep == k, onClick = { actions.setSchedule(null, k) }, enabled = !state.busy, label = { Text("$k") })
                    }
                }
                Text(
                    listOfNotNull(
                        localTime(o.lastRunAt)?.let { "Last $it" },
                        localTime(o.nextRunAt)?.let { "next $it" },
                        o.items.size.takeIf { it > 0 }?.let { "${it} kept, ${size(o.totalSize)}" },
                    ).joinToString(" · ").ifEmpty { "No backups yet" },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
                o.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.warning) }
                Button(onClick = actions.backUpNow, enabled = !state.busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(if (state.busy) "Working…" else "Back up now")
                }
                o.items.forEachIndexed { i, item ->
                    if (i == 0) Spacer(Modifier.height(4.dp))
                    HorizontalDivider(color = colors.border)
                    Row(
                        Modifier.fillMaxWidth().clickable { actions.openBackup(item) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(localTime(item.createdAt) ?: item.createdAt, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${if (item.scheduled) "Scheduled" else "Manual"} · ${item.budgets.size} budget${if (item.budgets.size == 1) "" else "s"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textTertiary,
                            )
                        }
                        Text(size(item.size), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupSheet(b: Backup, state: ServerUiState, actions: ServerActions) {
    val colors = CentsibleTheme.colors
    var confirmDelete by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { actions.openBackup(null) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.card) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Text("Backup from ${localTime(b.createdAt) ?: b.createdAt}", style = MaterialTheme.typography.titleLarge)
            Text(size(b.size), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            b.budgets.forEach { budget ->
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = colors.border)
                Text(budget.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${size(budget.size)} · opens in Actual's web app (Import → Actual)", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    OutlinedButton(onClick = { actions.save(PendingSave(b.id, budget.file, "${budget.name}-${b.createdAt.take(10)}.zip")) }, enabled = !state.busy) { Text("Save to phone") }
                    OutlinedButton(onClick = { actions.askRestore(b to budget) }, enabled = !state.busy) { Text("Restore a copy") }
                }
            }
            b.skipped.forEach { Text("Skipped $it", style = MaterialTheme.typography.bodySmall, color = colors.warning, modifier = Modifier.padding(top = 8.dp)) }
            if (b.household) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = colors.border)
                Text("Household", style = MaterialTheme.typography.titleMedium)
                Text("Members, devices and bridge settings", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
                OutlinedButton(onClick = { actions.save(PendingSave(b.id, "household.sqlite", "centsible-household-${b.createdAt.take(10)}.sqlite")) }, enabled = !state.busy, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Save to phone")
                }
            }
            TextButton(onClick = { confirmDelete = true }, enabled = !state.busy, modifier = Modifier.padding(top = 12.dp)) { Text("Delete this backup", color = colors.negative) }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this backup?") },
            text = { Text("It's removed from the server. Your budgets aren't affected.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; actions.deleteBackup(b) }) { Text("Delete", color = colors.negative) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
