package app.centsible.core.domain

import app.centsible.core.model.Money
import app.centsible.core.model.Schedule
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.Flow

/** One bill worth a notification. [key] makes sure it's sent once per due date. */
data class BillReminder(val scheduleId: String, val date: LocalDate, val title: String, val amount: Money, val daysUntil: Int) {
    val key: String get() = "$scheduleId@$date"

    val whenText: String
        get() = when (daysUntil) {
            0 -> "due today"
            1 -> "due tomorrow"
            else -> "due in $daysUntil days"
        }
}

object BillReminders {
    /** Lead times offered in Settings. */
    val LEAD_DAYS = listOf(0, 1, 3)

    /**
     * Bills due between today and [daysAhead] days out that haven't been announced yet.
     * Schedules that post themselves still get a heads-up: the money leaves either way.
     */
    fun due(
        schedules: List<Schedule>,
        today: LocalDate,
        daysAhead: Int,
        alreadySent: Set<String>,
        payeeName: (Schedule) -> String?,
    ): List<BillReminder> = schedules.asSequence()
        .filter { !it.completed }
        .flatMap { s ->
            (s.upcoming.ifEmpty { listOfNotNull(s.nextDate) }).asSequence().mapNotNull { raw ->
                val date = runCatching { LocalDate.parse(raw) }.getOrNull() ?: return@mapNotNull null
                val days = ChronoUnit.DAYS.between(today, date).toInt()
                if (days !in 0..daysAhead) return@mapNotNull null
                BillReminder(s.id, date, s.name?.takeIf { it.isNotBlank() } ?: payeeName(s) ?: "A scheduled bill", s.amount, days)
            }
        }
        .filter { it.key !in alreadySent }
        .distinctBy { it.key }
        .sortedWith(compareBy({ it.date }, { it.title }))
        .toList()

    /** Keeps the sent-keys list from growing forever: drops entries for dates long gone. */
    fun prune(sent: Set<String>, today: LocalDate, keepDays: Long = 14): Set<String> = sent.filterTo(mutableSetOf()) { key ->
        val date = runCatching { LocalDate.parse(key.substringAfterLast('@')) }.getOrNull()
        // Spending alerts can stay current for a whole month; remember them longer so they're sent once.
        val keep = if (key.startsWith(ALERT_PREFIX)) 45L else keepDays
        date != null && !date.isBefore(today.minusDays(keep))
    }

    const val ALERT_PREFIX = "alert:"

    /**
     * Periods that finished in the last three days, among [periods] ("week", "month",
     * "quarter", "year"): the review of each is ready. Returns (period, first day).
     */
    fun wrappedPeriods(today: LocalDate, periods: Set<String>): List<Pair<String, LocalDate>> = periods.mapNotNull { p ->
        val start = when (p) {
            "week" -> today.with(java.time.DayOfWeek.MONDAY).minusWeeks(1)
            "month" -> today.withDayOfMonth(1).minusMonths(1)
            "quarter" -> today.withDayOfMonth(1).withMonth(((today.monthValue - 1) / 3) * 3 + 1).minusMonths(3)
            "year" -> today.withDayOfYear(1).minusYears(1)
            else -> return@mapNotNull null
        }
        val end = when (p) {
            "week" -> start.plusDays(6)
            "month" -> start.plusMonths(1).minusDays(1)
            "quarter" -> start.plusMonths(3).minusDays(1)
            else -> start.plusYears(1).minusDays(1)
        }
        (p to start).takeIf { java.time.temporal.ChronoUnit.DAYS.between(end, today) in 1..3 }
    }

    fun reviewKey(period: String, start: LocalDate, today: LocalDate) = "review:$period:$start@$today"
    fun reviewSent(sent: Set<String>, period: String, start: LocalDate) = sent.any { it.startsWith("review:$period:$start@") }

    /** Warnings worth a notification that haven't been sent yet (keys are "alert:<id>@<sent date>"). */
    fun newAlerts(alerts: List<app.centsible.core.model.Insight>, sent: Set<String>): List<app.centsible.core.model.Insight> =
        alerts.filter { a -> a.severity == app.centsible.core.model.Insight.Severity.Warning && sent.none { it.startsWith("$ALERT_PREFIX${a.id}@") } }

    fun alertKey(alert: app.centsible.core.model.Insight, today: LocalDate) = "$ALERT_PREFIX${alert.id}@$today"
}

/** "Remind me about bills": kept on the phone, per device. */
interface ReminderSettings {
    val enabled: Flow<Boolean>
    val daysAhead: Flow<Int>
    /** Spending alerts (categories well over usual, unusual charges, price increases). */
    val alerts: Flow<Boolean>
    /** Periods whose review sends a notification when it wraps up: "week", "month", "quarter", "year". */
    val reviews: Flow<Set<String>>
    suspend fun setReviews(periods: Set<String>)
    suspend fun setEnabled(enabled: Boolean)
    suspend fun setAlerts(enabled: Boolean)
    suspend fun setDaysAhead(days: Int)
    suspend fun sent(): Set<String>
    suspend fun markSent(keys: Collection<String>, today: LocalDate)
}

/** Turns the daily background check on or off. */
interface ReminderScheduler {
    fun apply(enabled: Boolean)
}
