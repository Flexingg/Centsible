package app.centsible

import android.app.Application
import app.centsible.core.domain.ReminderScheduler
import app.centsible.core.domain.ReminderSettings
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltAndroidApp
class CentsibleApplication : Application() {
    @Inject lateinit var reminders: ReminderSettings
    @Inject lateinit var reminderScheduler: ReminderScheduler
    @Inject lateinit var changes: app.centsible.core.domain.BudgetChanges
    @Inject lateinit var appLock: app.centsible.core.domain.AppLockSettings

    override fun onCreate() {
        super.onCreate()
        BillReminderWorker.createChannel(this)
        // Re-register the daily check (a no-op if it's already queued).
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch { reminderScheduler.apply(reminders.enabled.first() || reminders.alerts.first() || reminders.reviews.first().isNotEmpty()) }
        // Widgets and tiles follow what happens in the app: a change made here (a transaction
        // added, a category funded) refreshes them a few seconds later, and the app lock
        // hides or shows their amounts right away.
        scope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            changes.changes.debounce(3_000).collect { app.centsible.glance.GlanceRefresher.refreshSoon(this@CentsibleApplication) }
        }
        scope.launch { appLock.enabled.drop(1).collect { app.centsible.glance.GlanceRefresher.refreshSoon(this@CentsibleApplication) } }
        app.centsible.glance.GlanceRefresher.refreshSoon(this)
    }
}
