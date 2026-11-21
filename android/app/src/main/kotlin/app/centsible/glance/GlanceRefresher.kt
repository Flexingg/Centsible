package app.centsible.glance

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.centsible.core.domain.AccountServices
import app.centsible.core.domain.AppLockSettings
import app.centsible.core.domain.BudgetEngine
import app.centsible.core.domain.Glanceable
import app.centsible.core.domain.GlanceableStore
import app.centsible.core.domain.PlanningGateway
import app.centsible.core.domain.SessionStore
import app.centsible.core.domain.TransactionTools
import app.centsible.core.model.JobStatus
import app.centsible.core.model.YearMonth
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** Reads the budget from the bridge into the widgets' copy, then redraws widgets and tiles. */
@Singleton
class GlanceRefresher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: SessionStore,
    private val engine: BudgetEngine,
    private val tools: TransactionTools,
    private val planning: PlanningGateway,
    private val appLock: AppLockSettings,
    private val store: GlanceableStore,
) {
    /** False when the bridge couldn't be reached (the old copy stays). */
    suspend fun refresh(): Boolean {
        val budget = sessions.current()?.selectedBudget
        if (budget == null) {
            store.write(null)
            redraw()
            return true
        }
        val locked = appLock.enabled.first()
        val result = runCatching {
            val today = LocalDate.now()
            val ym = YearMonth(today.toString().take(7))
            val month = engine.budgetMonth(budget, ym)
            val review = runCatching { tools.inbox(budget, limit = 1).total }.getOrDefault(0)
            val payees = runCatching { engine.payees(budget) }.getOrDefault(emptyList()).associate { it.id to it.name }
            val bills = runCatching { Glanceable.bills(planning.schedules(budget, upcoming = 3), today, { s -> s.payeeId?.let(payees::get) }) }.getOrDefault(emptyList())
            val name = runCatching { engine.budgets().firstOrNull { it.id == budget }?.name }.getOrNull()
            Glanceable.from(month, name, review, bills, System.currentTimeMillis(), today.month.getDisplayName(TextStyle.FULL, Locale.getDefault()))
                .copy(locked = locked)
        }
        result.onSuccess { store.write(it) }
        // Locking or unlocking still applies to the copy already there.
        if (result.isFailure) store.read()?.let { if (it.locked != locked) store.write(it.copy(locked = locked)) }
        redraw()
        return result.isSuccess
    }

    suspend fun redraw() {
        runCatching { BudgetWidget().updateAll(context) }
        runCatching { BillsWidget().updateAll(context) }
        for (tile in listOf(ReviewTileService::class.java, SyncTileService::class.java)) {
            runCatching { TileService.requestListeningState(context, ComponentName(context, tile)) }
        }
    }

    companion object {
        private const val PERIODIC = "glance-refresh"
        private const val NOW = "glance-refresh-now"
        private const val SYNC = "glance-sync"

        /** Every hour while online (Android may stretch it to save battery). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<GlanceRefreshWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun refreshSoon(context: Context) {
            val request = OneTimeWorkRequestBuilder<GlanceRefreshWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
        }

        /** Starts a bank sync on the bridge (from the tile), then refreshes everything. */
        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncNowWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(SYNC, ExistingWorkPolicy.KEEP, request)
        }

        suspend fun syncing(context: Context): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { WorkManager.getInstance(context).getWorkInfosForUniqueWork(SYNC).get().any { !it.state.isFinished } }.getOrDefault(false)
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface GlanceDeps {
    fun refresher(): GlanceRefresher
    fun store(): GlanceableStore
    fun sessions(): SessionStore
    fun services(): AccountServices
}

internal fun Context.glanceDeps(): GlanceDeps = EntryPointAccessors.fromApplication(applicationContext, GlanceDeps::class.java)

class GlanceRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = if (applicationContext.glanceDeps().refresher().refresh()) Result.success() else Result.retry()
}

/** "Sync banks" from the tile: starts the bridge's bank sync, waits for it, refreshes. */
class SyncNowWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val deps = applicationContext.glanceDeps()
        val budget = deps.sessions().current()?.selectedBudget
        val summary = if (budget == null) {
            "Open Centsible to set it up"
        } else {
            runCatching {
                var job = deps.services().startBankSync(budget, null)
                val deadline = System.currentTimeMillis() + 5 * 60_000
                while (job.status == JobStatus.Running && System.currentTimeMillis() < deadline) {
                    delay(2_000)
                    job = deps.services().job(job.id)
                }
                when (job.status) {
                    JobStatus.Succeeded -> when (val n = job.newTransactions ?: 0) { 0 -> "Up to date"; 1 -> "1 new transaction"; else -> "$n new transactions" }
                    JobStatus.Running -> "Still syncing on the bridge"
                    else -> job.error ?: "Sync failed"
                }
            }.getOrElse { it.message ?: "Couldn't reach the bridge" }
        }
        deps.store().setLastSync(System.currentTimeMillis(), summary)
        deps.refresher().refresh()
        return Result.success()
    }
}
