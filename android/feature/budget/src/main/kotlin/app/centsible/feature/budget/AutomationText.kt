package app.centsible.feature.budget

import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.model.Automation
import app.centsible.core.model.YearMonth
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The eight kinds Actual's automation editor offers, in its order, with plain names. */
internal enum class AutomationKind(val title: String, val emoji: String, val explain: String) {
    Fixed("Fixed amount", "📌", "The same amount every month, or every few days, weeks or months."),
    SaveBy("Save by date", "🎯", "Save a total by a month, a little each month. Can repeat every year."),
    Schedule("Cover schedule", "📅", "Budget for a recurring bill: save ahead, or cover each one when it's due."),
    History("From history", "🕰️", "What you spent on average lately, or what you budgeted a while ago."),
    Percent("% of income", "💯", "A share of this or last month's income, or of what's left to budget."),
    Refill("Refill to cap", "🔄", "Top the category up to a balance each month."),
    Remainder("Whatever is left", "🧺", "Share out what's still left to budget once everything else has run."),
    Goal("Long-term goal", "🏔️", "A balance to build up to. Shows progress; doesn't budget by itself."),
    ;

    companion object {
        fun of(a: Automation) = when (a) {
            is Automation.Fixed, is Automation.Periodic -> Fixed
            is Automation.SaveBy -> SaveBy
            is Automation.CoverSchedule -> Schedule
            is Automation.Average, is Automation.Copy -> History
            is Automation.PercentOfIncome -> Percent
            is Automation.Refill -> Refill
            is Automation.Remainder -> Remainder
            is Automation.Goal, is Automation.Unreadable -> Goal
        }
    }
}

/** A sensible new automation of each kind. */
internal fun AutomationKind.starter(month: YearMonth, schedules: List<String>): Automation = when (this) {
    AutomationKind.Fixed -> Automation.Fixed(priority = 0, monthly = null, cap = null)
    AutomationKind.SaveBy -> Automation.SaveBy(priority = 0, amount = app.centsible.core.model.Money.Zero, month = month.plus(11), repeat = null, spendFrom = null)
    AutomationKind.Schedule -> Automation.CoverSchedule(priority = 0, schedule = schedules.firstOrNull().orEmpty(), full = false, adjustment = null)
    AutomationKind.History -> Automation.Average(priority = 0, months = 3, adjustment = null)
    AutomationKind.Percent -> Automation.PercentOfIncome(priority = 0, percent = 10.0, of = Automation.ALL_INCOME, previousMonth = false)
    AutomationKind.Refill -> Automation.Refill(priority = 0, cap = Automation.Cap(app.centsible.core.model.Money.Zero))
    AutomationKind.Remainder -> Automation.Remainder(weight = 1.0, cap = null)
    AutomationKind.Goal -> Automation.Goal(app.centsible.core.model.Money.Zero)
}

private val MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())
private val DAY = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())

internal fun monthLabel(m: YearMonth): String = LocalDate.of(m.year, m.month, 1).format(MONTH)
internal fun dayLabel(iso: String): String = runCatching { LocalDate.parse(iso).format(DAY) }.getOrDefault(iso)

private fun money(m: app.centsible.core.model.Money) = MoneyFormat.format(m, showCents = m.minor % 100 != 0L)

internal fun capText(c: Automation.Cap): String {
    val per = when (c.period) {
        Automation.CapPeriod.Daily -> " a day"
        Automation.CapPeriod.Weekly -> " a week"
        Automation.CapPeriod.Monthly -> ""
    }
    return "up to ${money(c.amount)}$per" + if (c.hold) ", keeping extra" else ""
}

private fun adjustmentText(a: Automation.Adjustment?): String = when (a) {
    null -> ""
    is Automation.Adjustment.Percent -> " ${if (a.percent >= 0) "+" else "−"}${trim(kotlin.math.abs(a.percent))}%"
    is Automation.Adjustment.Fixed -> " ${if (a.amount.minor >= 0) "+" else "−"}${money(a.amount.abs())}"
}

private fun trim(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

private fun unit(u: Automation.PeriodUnit, n: Int) = when (u) {
    Automation.PeriodUnit.Day -> if (n == 1) "day" else "$n days"
    Automation.PeriodUnit.Week -> if (n == 1) "week" else "$n weeks"
    Automation.PeriodUnit.Month -> if (n == 1) "month" else "$n months"
    Automation.PeriodUnit.Year -> if (n == 1) "year" else "$n years"
}

/** One line saying what the automation does. */
internal fun Automation.summary(incomeNames: Map<String, String> = emptyMap()): String = when (this) {
    is Automation.Fixed -> listOfNotNull(monthly?.let { "${money(it)} a month" }, cap?.let(::capText)).joinToString(" · ").ifEmpty { "Fixed amount" }
    is Automation.Periodic -> "${money(amount)} every ${unit(unit, count)} from ${dayLabel(starting)}" + (cap?.let { " · ${capText(it)}" } ?: "")
    is Automation.SaveBy -> buildString {
        append("${money(amount)} by ${monthLabel(month)}")
        repeat?.let { append(" · every ${if (it.yearly) unit(Automation.PeriodUnit.Year, it.count) else unit(Automation.PeriodUnit.Month, it.count)}") }
        spendFrom?.let { append(" · spend from ${monthLabel(it)}") }
    }
    is Automation.CoverSchedule -> "Covers ${schedule.ifBlank { "a schedule" }}${if (full) " when due" else ", saving ahead"}${adjustmentText(adjustment)}"
    is Automation.Average -> "Average of the last ${if (months == 1) "month" else "$months months"}${adjustmentText(adjustment)}"
    is Automation.Copy -> "Same as ${if (monthsAgo == 1) "last month" else "$monthsAgo months ago"}"
    is Automation.PercentOfIncome -> {
        val what = when (of) {
            Automation.ALL_INCOME -> if (previousMonth) "last month's income" else "this month's income"
            Automation.AVAILABLE_FUNDS -> "what's left to budget"
            else -> (incomeNames[of] ?: of) + if (previousMonth) " (last month)" else ""
        }
        "${trim(percent)}% of $what"
    }
    is Automation.Refill -> "Refill to ${money(cap.amount)}" + when (cap.period) {
        Automation.CapPeriod.Daily -> " a day"
        Automation.CapPeriod.Weekly -> " a week"
        Automation.CapPeriod.Monthly -> ""
    }
    is Automation.Remainder -> "Share of what's left" + (if (weight != 1.0) " ×${trim(weight)}" else "") + (cap?.let { " · ${capText(it)}" } ?: "")
    is Automation.Goal -> "Goal: build up to ${money(amount)}"
    is Automation.Unreadable -> "Couldn't read “${line.trim()}”"
}
