package app.centsible.core.domain

import app.centsible.core.model.BudgetMonth
import app.centsible.core.model.BudgetType
import app.centsible.core.model.Money
import app.centsible.core.model.Schedule
import java.time.LocalDate

/**
 * What the home screen widgets and Quick Settings tiles show, saved on the phone so they
 * can draw without asking the bridge. Refreshed in the background and after changes in the
 * app. [locked] (the app lock is on) means show no amounts at all.
 */
data class Glanceable(
    val budgetName: String?,
    val month: String,
    val envelope: Boolean,
    /** Envelope budgets: money not yet given a job (negative = over-assigned). */
    val toBudget: Money,
    /** Spent so far this month, as a positive amount. */
    val spent: Money,
    val budgeted: Money,
    val reviewCount: Int,
    val bills: List<Bill>,
    /** When this was read from the bridge (epoch millis). */
    val updatedAt: Long,
    val locked: Boolean = false,
) {
    data class Bill(val title: String, val amount: Money, val date: String, val whenText: String)

    /** Share of the budget spent this month, 0..1 (null when nothing is budgeted). */
    val spentShare: Float? get() = if (budgeted.minor <= 0) null else (spent.minor.toFloat() / budgeted.minor).coerceIn(0f, 1f)

    companion object {
        /** Bills shown on the widget: the next [max] in the coming [days] days, soonest first. */
        fun bills(schedules: List<Schedule>, today: LocalDate, payeeName: (Schedule) -> String?, days: Int = 14, max: Int = 5): List<Bill> =
            BillReminders.due(schedules, today, days, emptySet(), payeeName).take(max).map {
                Bill(it.title, it.amount.abs(), it.date.toString(), it.whenText)
            }

        fun from(month: BudgetMonth, budgetName: String?, reviewCount: Int, bills: List<Bill>, now: Long, monthLabel: String) = Glanceable(
            budgetName = budgetName,
            month = monthLabel,
            envelope = month.budgetType != BudgetType.Tracking,
            toBudget = month.toBudget,
            spent = month.totalSpent.abs(),
            budgeted = month.totalBudgeted,
            reviewCount = reviewCount,
            bills = bills,
            updatedAt = now,
        )
    }
}

/** Where the last [Glanceable] is kept on the phone. */
interface GlanceableStore {
    suspend fun read(): Glanceable?
    suspend fun write(value: Glanceable?)
    /** When the last bank sync started from a tile finished, and how ("3 new transactions"). */
    suspend fun lastSync(): Pair<Long, String>?
    suspend fun setLastSync(at: Long, summary: String)
}
