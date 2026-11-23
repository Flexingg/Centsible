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
    /** Day-by-day spending for the charts; null on a bridge without the daily report. */
    val trend: Trend? = null,
    /** This month's spending categories, most spent first (visible ones, including those with nothing spent yet). */
    val categories: List<CategorySlice> = emptyList(),
    /** What's left in the budget's spending categories this month (their balances, with rollover). */
    val leftInBudget: Money = Money.Zero,
    /** Income and spending for the last six months, oldest first (this month last). */
    val months: List<MonthFlow> = emptyList(),
    /** Net worth at the end of each of the last twelve months, oldest first. */
    val netWorth: List<NetWorthPoint> = emptyList(),
    /** This month's money flow, every level (the Sankey chart shows it shallower if asked); null on an older bridge. */
    val sankey: app.centsible.core.model.SankeyGraph? = null,
) {
    data class Bill(val title: String, val amount: Money, val date: String, val whenText: String)

    /** [balance] is what's left in the category this month; [id] opens its transactions. */
    data class CategorySlice(val name: String, val spent: Money, val budgeted: Money, val id: String? = null, val balance: Money = Money.Zero)

    /** One month's money in and out ([month] is yyyy-MM). Both positive. */
    data class MonthFlow(val month: String, val income: Money, val spending: Money)

    data class NetWorthPoint(val month: String, val value: Money)

    /** Spent today (from the day-by-day trend), when the bridge reports it. */
    val spentToday: Money? get() = trend?.monthToDate?.let { m -> if (m.isEmpty()) null else Money(m.last() - (m.getOrNull(m.size - 2) ?: 0)) }

    /**
     * Running totals for the month so far and all of last month (cents, by day), and this
     * week Monday to Sunday (days still ahead are 0), to draw the pace and week charts.
     */
    data class Trend(
        val monthToDate: List<Long>,
        val lastMonth: List<Long>,
        val daysInMonth: Int,
        val week: List<Long>,
        /** Which day of [week] is today (0 = Monday). */
        val weekdayToday: Int,
        val lastWeekTotal: Long,
        /** What can be spent in a week to stay on budget (the month's budget over its days); null without a budget. */
        val weekAllowance: Long?,
    ) {
        val spentThisMonth: Long get() = monthToDate.lastOrNull() ?: 0
        /** Last month's spending by the same day of the month. */
        val lastMonthByNow: Long get() = lastMonth.getOrNull(monthToDate.size - 1) ?: lastMonth.lastOrNull() ?: 0
        val weekTotal: Long get() = week.sum()

        companion object {
            /**
             * From [days] (date to amount spent) covering at least the start of last month
             * through [today].
             */
            fun from(days: Map<LocalDate, Long>, today: LocalDate, monthBudget: Long?): Trend {
                val first = today.withDayOfMonth(1)
                val lastFirst = first.minusMonths(1)
                fun cumulative(start: LocalDate, count: Int): List<Long> {
                    var run = 0L
                    return (0 until count).map { run += days[start.plusDays(it.toLong())] ?: 0; run }
                }
                val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
                val week = (0 until 7).map { i -> monday.plusDays(i.toLong()).let { d -> if (d.isAfter(today)) 0L else days[d] ?: 0L } }
                val lastWeek = (1..7).sumOf { days[monday.minusDays(it.toLong())] ?: 0L }
                return Trend(
                    monthToDate = cumulative(first, today.dayOfMonth),
                    lastMonth = cumulative(lastFirst, lastFirst.lengthOfMonth()),
                    daysInMonth = today.lengthOfMonth(),
                    week = week,
                    weekdayToday = today.dayOfWeek.value - 1,
                    lastWeekTotal = lastWeek,
                    weekAllowance = monthBudget?.takeIf { it > 0 }?.let { it * 7 / today.lengthOfMonth() },
                )
            }
        }
    }

    /** Share of the budget spent this month, 0..1 (null when nothing is budgeted). */
    val spentShare: Float? get() = if (budgeted.minor <= 0) null else (spent.minor.toFloat() / budgeted.minor).coerceIn(0f, 1f)

    companion object {
        /** Bills shown on the widget: the next [max] in the coming [days] days, soonest first. */
        fun bills(schedules: List<Schedule>, today: LocalDate, payeeName: (Schedule) -> String?, days: Int = 14, max: Int = 5): List<Bill> =
            BillReminders.due(schedules, today, days, emptySet(), payeeName).take(max).map {
                Bill(it.title, it.amount.abs(), it.date.toString(), it.whenText)
            }

        /** The [max] categories with the most spent this month. */
        fun topCategories(month: BudgetMonth, max: Int = 5): List<CategorySlice> = allCategories(month).filter { it.spent.minor > 0 }.take(max)

        /** Every visible spending category this month, most spent first. */
        fun allCategories(month: BudgetMonth): List<CategorySlice> = month.expenseGroups
            .filter { !it.hidden }
            .flatMap { it.categories }
            .filter { !it.hidden }
            .sortedBy { it.spent.minor }
            .map { CategorySlice(it.name, Money(maxOf(0, -it.spent.minor)), it.budgeted, it.id.raw, it.balance) }

        /** What's left across the visible spending categories (negative balances count against it). */
        fun leftInBudget(month: BudgetMonth): Money = Money(month.expenseGroups.filter { !it.hidden }.flatMap { it.categories }.filter { !it.hidden }.sumOf { it.balance.minor })

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
