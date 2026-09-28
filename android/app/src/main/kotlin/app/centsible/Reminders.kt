package app.centsible

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.domain.BillReminders
import app.centsible.core.domain.BudgetEngine
import app.centsible.core.domain.PlanningGateway
import app.centsible.core.domain.ReminderScheduler
import app.centsible.core.domain.ReminderSettings
import app.centsible.core.domain.SessionStore
import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

internal const val BILLS_CHANNEL = "bills"
internal const val EXTRA_OPEN = "open"
internal const val OPEN_RECURRING = "recurring"
internal const val OPEN_INSIGHTS = "insights"
internal const val ALERTS_CHANNEL = "alerts"

/** Once a day (around 8am, when the phone has a network), checks for bills coming up. */
class BillReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun sessions(): SessionStore
        fun planning(): PlanningGateway
        fun engine(): BudgetEngine
        fun reminders(): ReminderSettings
        fun insights(): app.centsible.core.domain.InsightsGateway
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val settings = deps.reminders()
        val bills = settings.enabled.first()
        val alerts = settings.alerts.first()
        if (!bills && !alerts) return Result.success()
        val budget = deps.sessions().current()?.selectedBudget ?: return Result.success()
        val notifications = NotificationManagerCompat.from(applicationContext)
        if (!notifications.areNotificationsEnabled()) return Result.success()
        if (alerts) notifyAlerts(deps, budget, notifications)
        if (!bills) return Result.success()

        val today = LocalDate.now()
        val schedules = runCatching { deps.planning().schedules(budget, upcoming = 3) }.getOrElse { return Result.retry() }
        val payees = runCatching { deps.engine().payees(budget) }.getOrDefault(emptyList()).associate { it.id to it.name }
        val due = BillReminders.due(schedules, today, settings.daysAhead.first(), settings.sent()) { s -> s.payeeId?.let(payees::get) }
        if (due.isEmpty()) return Result.success()

        due.forEach { bill ->
            val notification = NotificationCompat.Builder(applicationContext, BILLS_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("${bill.title} ${bill.whenText}")
                .setContentText(MoneyFormat.format(bill.amount.abs()) + " on " + bill.date)
                .setContentIntent(openRecurring(applicationContext))
                .setAutoCancel(true)
                .setGroup(BILLS_CHANNEL)
                .build()
            @Suppress("MissingPermission") // checked with areNotificationsEnabled above
            notifications.notify(bill.key.hashCode(), notification)
        }
        settings.markSent(due.map { it.key }, today)
        return Result.success()
    }

    /** At most three new warnings a day, each sent once. */
    private suspend fun notifyAlerts(deps: Deps, budget: app.centsible.core.model.BudgetId, notifications: NotificationManagerCompat) {
        val settings = deps.reminders()
        val insights = runCatching { deps.insights().insights(budget) }.getOrNull() ?: return
        val fresh = BillReminders.newAlerts(insights.alerts, settings.sent()).take(3)
        if (fresh.isEmpty()) return
        val today = LocalDate.now()
        fresh.forEach { alert ->
            val notification = NotificationCompat.Builder(applicationContext, ALERTS_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(alert.title)
                .setContentText(alert.detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(alert.detail))
                .setContentIntent(open(applicationContext, OPEN_INSIGHTS, 2))
                .setAutoCancel(true)
                .setGroup(ALERTS_CHANNEL)
                .build()
            @Suppress("MissingPermission") // checked with areNotificationsEnabled before this runs
            notifications.notify(alert.id.hashCode(), notification)
        }
        settings.markSent(fresh.map { BillReminders.alertKey(it, today) }, today)
    }

    companion object {
        fun open(context: Context, screen: String, requestCode: Int): PendingIntent = PendingIntent.getActivity(
            context, requestCode,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN, screen).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun openRecurring(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN, OPEN_RECURRING).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun createChannel(context: Context) {
            val channel = NotificationChannel(BILLS_CHANNEL, "Bill reminders", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Upcoming bills from your recurring schedules" }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            val alerts = NotificationChannel(ALERTS_CHANNEL, "Spending alerts", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Spending well above usual, unusual charges and price increases" }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(alerts)
        }
    }
}

@Singleton
class WorkManagerReminderScheduler @Inject constructor(@ApplicationContext private val context: Context) : ReminderScheduler {
    override fun apply(enabled: Boolean) {
        val work = WorkManager.getInstance(context)
        if (!enabled) {
            work.cancelUniqueWork(WORK_NAME)
            return
        }
        val now = LocalDateTime.now()
        val nextMorning = now.toLocalDate().atTime(8, 0).let { if (it.isAfter(now)) it else it.plusDays(1) }
        val request = PeriodicWorkRequestBuilder<BillReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(Duration.between(now, nextMorning).toMinutes(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        // KEEP: opening the app again mustn't push the next check back a day.
        work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private companion object {
        const val WORK_NAME = "bill-reminders"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ReminderModule {
    @Binds abstract fun scheduler(impl: WorkManagerReminderScheduler): ReminderScheduler
}
