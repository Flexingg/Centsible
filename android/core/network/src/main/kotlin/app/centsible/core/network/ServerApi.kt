package app.centsible.core.network

import io.ktor.client.call.body
import io.ktor.client.request.parameter
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable

@Serializable data class ServerBridgeDto(val version: String, val latest: String? = null, val updateAvailable: Boolean = false, val uptimeSeconds: Long = 0)
@Serializable data class ServerActualDto(
    val version: String? = null,
    val pairedVersion: String = "",
    val compatibility: String = "unknown",
    val newestRelease: String? = null,
    val connected: Boolean = false,
)
@Serializable data class AppReleaseDto(
    val version: String,
    val versionCode: Int? = null,
    val publishedAt: String? = null,
    val url: String = "",
    val apkUrl: String? = null,
    val notes: String? = null,
)
@Serializable data class UpdaterDto(val configured: Boolean = false, val lastRequestedAt: String? = null, val lastUpdatedFrom: String? = null, val lastResult: String? = null)
@Serializable data class DiskDto(val free: Long, val total: Long)
@Serializable data class ServerStatusDto(
    val bridge: ServerBridgeDto,
    val actual: ServerActualDto,
    val app: AppReleaseDto? = null,
    val updater: UpdaterDto = UpdaterDto(),
    val disk: DiskDto? = null,
    val checkedAt: String? = null,
)
@Serializable data class UpdateRequestedDto(val requested: Boolean = true)

@Serializable data class BackupBudgetDto(val budgetId: String, val name: String, val file: String, val size: Long = 0)
@Serializable data class BackupSkippedDto(val budgetId: String, val name: String, val reason: String)
@Serializable data class BackupDto(
    val id: String,
    val createdAt: String,
    val trigger: String = "manual",
    val size: Long = 0,
    val budgets: List<BackupBudgetDto> = emptyList(),
    val skipped: List<BackupSkippedDto> = emptyList(),
    val household: Boolean = false,
)
@Serializable data class BackupOverviewDto(
    val intervalHours: Int = 24,
    val keep: Int = 14,
    val intervals: List<Int> = listOf(0, 6, 12, 24, 168),
    val lastRunAt: String? = null,
    val lastError: String? = null,
    val nextRunAt: String? = null,
    val totalSize: Long = 0,
    val items: List<BackupDto> = emptyList(),
)
@Serializable data class BackupSettingsDto(val intervalHours: Int? = null, val keep: Int? = null)
@Serializable data class RestoreRequestDto(val budgetId: String)
@Serializable data class RestoredDto(val budgetId: String, val name: String)

/** /v1/server: health, updates and backups. */
class ServerApi(private val client: BridgeClient) {
    suspend fun status(refresh: Boolean): ServerStatusDto = client.get("/v1/server") { if (refresh) parameter("refresh", true) }
    suspend fun update(): UpdateRequestedDto = client.execute(HttpMethod.Post, "/v1/server/update").body()
    suspend fun health(): HealthDto = client.execute(HttpMethod.Get, "/v1/health").body()
    suspend fun backups(): BackupOverviewDto = client.get("/v1/server/backups")
    suspend fun backUpNow(): BackupDto = client.execute(HttpMethod.Post, "/v1/server/backups").body()
    suspend fun backupSettings(body: BackupSettingsDto): BackupOverviewDto = client.send(HttpMethod.Put, "/v1/server/backups/settings", body)
    suspend fun deleteBackup(id: String) { client.execute(HttpMethod.Delete, "/v1/server/backups/$id") }
    suspend fun downloadBackupFile(id: String, file: String): ByteArray = client.execute(HttpMethod.Get, "/v1/server/backups/$id/files/$file").readRawBytes()
    suspend fun restore(id: String, budgetId: String): RestoredDto = client.send(HttpMethod.Post, "/v1/server/backups/$id/restore", RestoreRequestDto(budgetId))
}
