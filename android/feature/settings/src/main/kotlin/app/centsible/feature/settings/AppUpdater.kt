package app.centsible.feature.settings

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.content.getSystemService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Where an app update download is. */
sealed interface AppDownload {
    data object Idle : AppDownload
    data class Running(val progress: Float?) : AppDownload
    data class Ready(val uri: Uri) : AppDownload
    data class Failed(val message: String) : AppDownload
}

/**
 * Updates the app from the newest GitHub release: Android's download manager fetches the
 * APK (with its own notification), then the system installer takes over. Every release
 * is signed with the same key, so it installs over this one and keeps everything.
 */
object AppUpdater {
    fun installedVersionCode(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    fun installedVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    fun download(context: Context, url: String, version: String): Long {
        val dm = checkNotNull(context.getSystemService<DownloadManager>())
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Centsible $version")
            .setDescription("App update")
            .setMimeType(APK)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "centsible-$version.apk")
        return dm.enqueue(request)
    }

    /** Polls the download until it finishes. */
    fun progress(context: Context, id: Long): Flow<AppDownload> = flow {
        val dm = checkNotNull(context.getSystemService<DownloadManager>())
        while (true) {
            val state = dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (!c.moveToFirst()) return@use AppDownload.Failed("The download was cancelled.")
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> dm.getUriForDownloadedFile(id)?.let { AppDownload.Ready(it) } ?: AppDownload.Failed("The download finished but the file is missing.")
                    DownloadManager.STATUS_FAILED -> AppDownload.Failed("The download failed. Check your connection and try again.")
                    else -> AppDownload.Running(if (total > 0) done.toFloat() / total else null)
                }
            }
            emit(state)
            if (state !is AppDownload.Running) break
            delay(400)
        }
    }

    /** Android asks once whether Centsible may install apps; until then, it opens that setting. */
    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    fun allowInstallsIntent(context: Context) =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installIntent(uri: Uri) = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, APK)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    private const val APK = "application/vnd.android.package-archive"
}
