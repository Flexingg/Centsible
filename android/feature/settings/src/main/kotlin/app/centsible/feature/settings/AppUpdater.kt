package app.centsible.feature.settings

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.content.getSystemService
import app.centsible.core.model.AppRelease
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Where an app update download is. */
sealed interface AppDownload {
    data object Idle : AppDownload
    data class Running(val progress: Float?) : AppDownload
    data class Ready(val uri: Uri) : AppDownload
    data class Failed(val message: String) : AppDownload
}

/**
 * Updates the app directly from the newest GitHub release: queries GitHub's Releases API,
 * downloads the signed APK via Android's DownloadManager (with its own notification), then
 * hands off to Android's package installer. Every CI release is signed with the same key,
 * so it installs cleanly over the current app and preserves all data.
 */
object AppUpdater {
    const val REPO = "Flexingg/Centsible"
    const val RELEASES_URL = "https://github.com/$REPO/releases"

    fun installedVersionCode(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    fun installedVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    /** Queries GitHub's latest release endpoint directly from the phone. */
    suspend fun fetchLatestGitHubRelease(repo: String = REPO): AppRelease? = withContext(Dispatchers.IO) {
        val conn = (URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Centsible-Android")
        }
        try {
            if (conn.responseCode !in 200..299) return@withContext null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parseGitHubReleaseJson(body)
        } finally {
            conn.disconnect()
        }
    }

    internal fun parseGitHubReleaseJson(rawJson: String): AppRelease? {
        val obj = runCatching { JSONObject(rawJson) }.getOrNull() ?: return null
        val tag = obj.optString("tag_name").trim().ifEmpty { return null }
        val version = tag.removePrefix("v").removePrefix("V")
        val url = obj.optString("html_url").trim().ifEmpty { "$RELEASES_URL/tag/$tag" }
        val publishedAt = obj.optString("published_at").trim().ifEmpty { null }
        val notes = obj.optString("body").trim().ifEmpty { null }
        var apkUrl: String? = null
        val assets = obj.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name")
                val dl = asset.optString("browser_download_url")
                if (name.endsWith(".apk", ignoreCase = true) && dl.isNotBlank()) {
                    apkUrl = dl
                    break
                }
            }
        }
        return AppRelease(
            version = version,
            versionCode = parseVersionCode(version),
            publishedAt = publishedAt,
            url = url,
            apkUrl = apkUrl,
            notes = notes,
        )
    }

    /** Extracts the CI run number / patch build code from versions like "0.2.127". */
    internal fun parseVersionCode(version: String): Int? {
        val clean = version.trim().removePrefix("v").removePrefix("V").substringBefore('-')
        val parts = clean.split('.').mapNotNull { it.toIntOrNull() }
        return if (parts.size >= 3) parts[2] else null
    }

    /** Compares dotted numeric versions (e.g. 0.2.128 vs 0.2.127 or 0.3.0 vs 0.2.127). */
    internal fun compareSemver(a: String, b: String): Int {
        fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val pa = parts(a)
        val pb = parts(b)
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val diff = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    /** True when [release] is strictly newer than the installed APK and has a downloadable APK. */
    fun isUpdateAvailable(release: AppRelease, installedCode: Long, installedName: String): Boolean {
        if (release.apkUrl.isNullOrBlank()) return false
        val semverDiff = compareSemver(release.version, installedName)
        if (semverDiff != 0) return semverDiff > 0
        val remoteCode = release.versionCode ?: return false
        return remoteCode > installedCode
    }

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
