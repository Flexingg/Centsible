package app.centsible.glance

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Quick Settings tiles (pull down the notification shade, edit, drag them in). They read the
 * widgets' copy of the budget, so they show instantly and never wait on the bridge.
 */
abstract class CentsibleTile : TileService() {
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onTileAdded() {
        super.onTileAdded()
        GlanceRefresher.schedule(this)
        GlanceRefresher.refreshSoon(this)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Opens a screen in the app and closes the shade (asks to unlock the phone first if it's locked). */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    protected fun open(path: String) {
        val intent = openIntent(this, path)
        val go = {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, path.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
        if (isLocked) unlockAndRun(go) else go()
    }

    protected fun Tile.show(state: Int, subtitle: String?) {
        this.state = state
        if (Build.VERSION.SDK_INT >= 29) this.subtitle = subtitle
        updateTile()
    }
}

/** "Add transaction": straight to a new transaction. */
class AddTransactionTileService : CentsibleTile() {
    override fun onStartListening() {
        qsTile?.show(Tile.STATE_INACTIVE, null)
    }

    override fun onClick() = open("new-transaction")
}

/** "Review": how many transactions wait for a look (hidden with the app lock on); opens the inbox. */
class ReviewTileService : CentsibleTile() {
    override fun onStartListening() {
        scope.launch {
            val data = glanceDeps().store().read()
            val tile = qsTile ?: return@launch
            when {
                data == null -> tile.show(Tile.STATE_INACTIVE, null)
                data.locked -> tile.show(Tile.STATE_INACTIVE, null)
                data.reviewCount == 0 -> tile.show(Tile.STATE_INACTIVE, "All caught up")
                else -> tile.show(Tile.STATE_ACTIVE, if (data.reviewCount == 1) "1 to review" else "${data.reviewCount} to review")
            }
        }
    }

    override fun onClick() = open("review")
}

/** "Sync banks": starts a bank sync on the bridge without opening the app. */
class SyncTileService : CentsibleTile() {
    override fun onStartListening() {
        scope.launch {
            val tile = qsTile ?: return@launch
            if (GlanceRefresher.syncing(this@SyncTileService)) return@launch tile.show(Tile.STATE_ACTIVE, "Syncing…")
            val last = glanceDeps().store().lastSync()
            tile.show(Tile.STATE_INACTIVE, last?.let { (at, summary) -> "${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))} · $summary" })
        }
    }

    override fun onClick() {
        scope.launch {
            if (GlanceRefresher.syncing(this@SyncTileService)) return@launch
            // KEEP policy: a second tap while one runs is ignored anyway.
            GlanceRefresher.syncNow(this@SyncTileService)
            qsTile?.show(Tile.STATE_ACTIVE, "Syncing…")
        }
    }
}
