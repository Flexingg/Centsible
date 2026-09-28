package app.centsible.core.domain

import app.centsible.core.model.Backup
import app.centsible.core.model.BackupOverview
import app.centsible.core.model.BudgetId
import app.centsible.core.model.ServerStatus

/** Server health, one-tap updates and backups (owners, except the status). */
interface ServerGateway {
    suspend fun status(refresh: Boolean = false): ServerStatus
    /** Asks the updater to update the bridge, Actual and the tunnel; the bridge restarts. */
    suspend fun update()
    /** The running bridge's version, straight from /v1/health (used to see an update land). */
    suspend fun bridgeVersion(): String?
    suspend fun backups(): BackupOverview
    suspend fun backUpNow(): Backup
    suspend fun setBackupSchedule(intervalHours: Int? = null, keep: Int? = null): BackupOverview
    suspend fun deleteBackup(id: String)
    suspend fun downloadBackupFile(id: String, file: String): ByteArray
    /** Imports a budget from a backup as a new budget; returns its id and name. */
    suspend fun restore(id: String, budget: BudgetId): Pair<BudgetId, String>
}
