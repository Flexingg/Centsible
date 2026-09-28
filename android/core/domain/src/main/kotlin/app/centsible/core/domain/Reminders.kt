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
        date != null && !date.isBefore(today.minusDays(keepDays))
    }
}

/** "Remind me about bills": kept on the phone, per device. */
interface ReminderSettings {
    val enabled: Flow<Boolean>
    val daysAhead: Flow<Int>
    suspend fun setEnabled(enabled: Boolean)
    suspend fun setDaysAhead(days: Int)
    suspend fun sent(): Set<String>
    suspend fun markSent(keys: Collection<String>, today: LocalDate)
}

/** Turns the daily background check on or off. */
interface ReminderScheduler {
    fun apply(enabled: Boolean)
}
