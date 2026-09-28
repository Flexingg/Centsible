package app.centsible

import android.app.Application
import app.centsible.core.domain.ReminderScheduler
import app.centsible.core.domain.ReminderSettings
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltAndroidApp
class CentsibleApplication : Application() {
    @Inject lateinit var reminders: ReminderSettings
    @Inject lateinit var reminderScheduler: ReminderScheduler

    override fun onCreate() {
        super.onCreate()
        BillReminderWorker.createChannel(this)
        // Re-register the daily check (a no-op if it's already queued).
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { reminderScheduler.apply(reminders.enabled.first() || reminders.alerts.first()) }
    }
}
