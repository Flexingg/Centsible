package app.centsible.core.engine.bridge

import app.centsible.core.domain.ServerGateway
import app.centsible.core.model.AppRelease
import app.centsible.core.model.Backup
import app.centsible.core.model.BackupBudget
import app.centsible.core.model.BackupOverview
import app.centsible.core.model.BudgetId
import app.centsible.core.model.ServerStatus
import app.centsible.core.network.BackupDto
import app.centsible.core.network.BackupOverviewDto
import app.centsible.core.network.BackupSettingsDto
import app.centsible.core.network.ServerApi

class BridgeServer(private val api: ServerApi) : ServerGateway {
    override suspend fun status(refresh: Boolean): ServerStatus {
        val d = api.status(refresh)
        return ServerStatus(
            d.bridge.version, d.bridge.latest, d.bridge.updateAvailable, d.bridge.uptimeSeconds,
            d.actual.version, d.actual.pairedVersion, d.actual.compatibility, d.actual.newestRelease, d.actual.connected,
            d.app?.let { AppRelease(it.version, it.versionCode, it.publishedAt, it.url, it.apkUrl, it.notes) },
            d.updater.configured, d.updater.lastRequestedAt, d.updater.lastResult,
            d.disk?.free, d.disk?.total,
        )
    }

    override suspend fun update() { api.update() }
    override suspend fun bridgeVersion() = runCatching { api.health().version }.getOrNull()
    override suspend fun backups() = api.backups().toModel()
    override suspend fun backUpNow() = api.backUpNow().toModel()
    override suspend fun setBackupSchedule(intervalHours: Int?, keep: Int?) = api.backupSettings(BackupSettingsDto(intervalHours, keep)).toModel()
    override suspend fun deleteBackup(id: String) = api.deleteBackup(id)
    override suspend fun downloadBackupFile(id: String, file: String) = api.downloadBackupFile(id, file)
    override suspend fun restore(id: String, budget: BudgetId) = api.restore(id, budget.raw).let { BudgetId(it.budgetId) to it.name }
}

private fun BackupOverviewDto.toModel() = BackupOverview(intervalHours, keep, intervals, lastRunAt, lastError, nextRunAt, totalSize, items.map { it.toModel() })

private fun BackupDto.toModel() = Backup(
    id, createdAt, trigger == "scheduled", size,
    budgets.map { BackupBudget(BudgetId(it.budgetId), it.name, it.file, it.size) },
    skipped.map { "${it.name}: ${it.reason}" },
    household,
)
