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
import kotlinx.coroutines.flow.first
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

/**
 * A tile showing one budget figure, chosen in Settings → Widgets & tiles (long-press the
 * tile to get there): left to spend today, left this month or week, spent today, to
 * budget, or one category's balance. The title is the figure's name, the amount under it.
 */
abstract class FigureTile(private val slot: Int) : CentsibleTile() {
    override fun onStartListening() {
        scope.launch {
            val deps = glanceDeps()
            val config = deps.widgetSettings().tiles.first()[slot]
            val data = deps.store().read()
            val tile = qsTile ?: return@launch
            val today = java.time.LocalDate.now()
            val reading = data?.let { app.centsible.core.domain.TileMetrics.read(config, it, today.dayOfMonth, today.lengthOfMonth()) }
            tile.label = reading?.title ?: config.metric.title
            val amount = reading?.amount
            when {
                data == null -> tile.show(Tile.STATE_INACTIVE, "Open Centsible")
                data.locked -> tile.show(Tile.STATE_INACTIVE, "App lock is on")
                amount == null -> tile.show(Tile.STATE_INACTIVE, "—")
                else -> tile.show(
                    if (reading.negative) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE,
                    app.centsible.core.designsystem.component.MoneyFormat.format(amount, showCents = false),
                )
            }
        }
    }

    override fun onClick() {
        scope.launch {
            val config = glanceDeps().widgetSettings().tiles.first()[slot]
            open(
                if (config.metric == app.centsible.core.domain.TileMetric.Category && config.categoryId != null) {
                    "category/${config.categoryId}?name=${java.net.URLEncoder.encode(config.categoryName ?: "Category", "UTF-8")}"
                } else {
                    "budget"
                },
            )
        }
    }
}

class BudgetTileService : FigureTile(0)
class BudgetTile2Service : FigureTile(1)
