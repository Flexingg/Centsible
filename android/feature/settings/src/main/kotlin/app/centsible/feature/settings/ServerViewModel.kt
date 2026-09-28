package app.centsible.feature.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.domain.ServerGateway
import app.centsible.core.domain.SessionStore
import app.centsible.core.domain.userMessage
import app.centsible.core.model.Backup
import app.centsible.core.model.BackupBudget
import app.centsible.core.model.BackupOverview
import app.centsible.core.model.Role
import app.centsible.core.model.ServerStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A file from a backup waiting for the person to pick where to save it. */
data class PendingSave(val backupId: String, val file: String, val suggestedName: String)

data class ServerUiState(
    val status: Loadable<ServerStatus> = Loadable.Loading,
    val backups: Loadable<BackupOverview>? = null,
    val isOwner: Boolean = false,
    val installedVersion: String = "",
    val installedCode: Long = 0,
    val download: AppDownload = AppDownload.Idle,
    /** Set while the server updates: the bridge version it's updating from. */
    val updatingFrom: String? = null,
    val confirmUpdate: Boolean = false,
    val openBackup: Backup? = null,
    val confirmRestore: Pair<Backup, BackupBudget>? = null,
    val busy: Boolean = false,
    val message: String? = null,
) {
    val appUpdate get() = (status as? Loadable.Ready)?.value?.app?.takeIf { (it.versionCode ?: 0) > installedCode && it.apkUrl != null }
}

@HiltViewModel
class ServerViewModel @Inject constructor(
    private val server: ServerGateway,
    private val sessions: SessionStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val state = MutableStateFlow(
        ServerUiState(installedVersion = AppUpdater.installedVersionName(context), installedCode = AppUpdater.installedVersionCode(context)),
    )
    val uiState: StateFlow<ServerUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            val owner = sessions.current()?.member?.role == Role.Owner
            state.update { it.copy(isOwner = owner, backups = if (owner) Loadable.Loading else null) }
            load(refresh = false)
        }
    }

    fun load(refresh: Boolean = true) = viewModelScope.launch {
        val status = runCatching { server.status(refresh) }
        state.update { it.copy(status = status.fold({ s -> Loadable.Ready(s) }, { e -> Loadable.Failed(e.userMessage()) })) }
        if (state.value.isOwner) loadBackups()
    }

    private suspend fun loadBackups() {
        val r = runCatching { server.backups() }
        state.update { it.copy(backups = r.fold({ b -> Loadable.Ready(b) }, { e -> Loadable.Failed(e.userMessage()) })) }
    }

    // ── App ──

    fun downloadApp() {
        val release = state.value.appUpdate ?: return
        val id = runCatching { AppUpdater.download(context, release.apkUrl!!, release.version) }
            .getOrElse { e -> state.update { it.copy(download = AppDownload.Failed(e.message ?: "Couldn't start the download.")) }; return }
        viewModelScope.launch {
            AppUpdater.progress(context, id).collect { d -> state.update { it.copy(download = d) } }
        }
    }

    // ── Server ──

    fun askUpdate(ask: Boolean) = state.update { it.copy(confirmUpdate = ask) }

    /** Asks for the update, then watches the bridge come back with a new version (up to 5 minutes). */
    fun updateServer() {
        val from = (state.value.status as? Loadable.Ready)?.value?.bridgeVersion ?: return
        state.update { it.copy(confirmUpdate = false, updatingFrom = from) }
        viewModelScope.launch {
            runCatching { server.update() }.onFailure { e ->
                state.update { it.copy(updatingFrom = null, message = e.userMessage()) }
                return@launch
            }
            val deadline = System.currentTimeMillis() + 5 * 60_000
            while (System.currentTimeMillis() < deadline) {
                delay(5_000)
                val now = server.bridgeVersion()
                if (now != null && now != from) {
                    state.update { it.copy(updatingFrom = null, message = "Server updated to $now") }
                    load(refresh = false)
                    return@launch
                }
            }
            state.update { it.copy(updatingFrom = null, message = "Still on $from. The update may need more time, or check the updater's logs on the server.") }
            load(refresh = false)
        }
    }

    // ── Backups ──

    fun setSchedule(intervalHours: Int? = null, keep: Int? = null) = act(null) {
        val o = server.setBackupSchedule(intervalHours, keep)
        state.update { it.copy(backups = Loadable.Ready(o)) }
    }

    fun backUpNow() = act("Backed up") {
        server.backUpNow()
        loadBackups()
    }

    fun openBackup(b: Backup?) = state.update { it.copy(openBackup = b) }

    fun delete(b: Backup) = act("Backup deleted") {
        server.deleteBackup(b.id)
        state.update { it.copy(openBackup = null) }
        loadBackups()
    }

    fun askRestore(pair: Pair<Backup, BackupBudget>?) = state.update { it.copy(confirmRestore = pair) }

    fun restore() {
        val (backup, budget) = state.value.confirmRestore ?: return
        state.update { it.copy(confirmRestore = null) }
        act(null) {
            val (_, name) = server.restore(backup.id, budget.budgetId)
            state.update { it.copy(openBackup = null, message = "Restored as \"$name\". Switch to it in Settings → Budget.") }
        }
    }

    /** Writes a backup file to where the person chose (the system's save dialog). */
    fun save(pending: PendingSave, to: Uri) = act("Saved") {
        val bytes = server.downloadBackupFile(pending.backupId, pending.file)
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(to)?.use { it.write(bytes) } ?: error("Couldn't write the file")
        }
    }

    fun messageShown() = state.update { it.copy(message = null) }

    private fun act(success: String?, block: suspend () -> Unit) = viewModelScope.launch {
        state.update { it.copy(busy = true) }
        runCatching { block() }
            .onSuccess { state.update { it.copy(busy = false, message = success ?: it.message) } }
            .onFailure { e -> state.update { it.copy(busy = false, message = e.userMessage()) } }
    }
}
