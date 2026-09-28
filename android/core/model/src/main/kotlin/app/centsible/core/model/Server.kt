package app.centsible.core.model

/** The household's server: versions, the newest release (with the app), one-tap updates. */
data class ServerStatus(
    val bridgeVersion: String,
    val bridgeLatest: String?,
    val bridgeUpdateAvailable: Boolean,
    val uptimeSeconds: Long,
    val actualVersion: String?,
    val actualPairedVersion: String,
    val actualCompatibility: String,
    val actualNewestRelease: String?,
    val actualConnected: Boolean,
    val app: AppRelease?,
    val updaterConfigured: Boolean,
    val lastUpdateRequestedAt: String?,
    val lastUpdateResult: String?,
    val diskFree: Long?,
    val diskTotal: Long?,
)

data class AppRelease(val version: String, val versionCode: Int?, val publishedAt: String?, val url: String, val apkUrl: String?, val notes: String?)

data class BackupOverview(
    val intervalHours: Int,
    val keep: Int,
    val intervals: List<Int>,
    val lastRunAt: String?,
    val lastError: String?,
    val nextRunAt: String?,
    val totalSize: Long,
    val items: List<Backup>,
)

data class Backup(
    val id: String,
    val createdAt: String,
    val scheduled: Boolean,
    val size: Long,
    val budgets: List<BackupBudget>,
    val skipped: List<String>,
    val household: Boolean,
)

data class BackupBudget(val budgetId: BudgetId, val name: String, val file: String, val size: Long)
