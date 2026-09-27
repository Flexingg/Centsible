package app.centsible.feature.planning

import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.model.Frequency
import app.centsible.core.model.Money
import app.centsible.core.model.Recurrence
import app.centsible.core.model.Rule
import app.centsible.core.model.RuleClause
import app.centsible.core.model.RuleValue
import app.centsible.core.model.Schedule
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Plain-language text for schedules and rules, so nobody has to read Actual's JSON. */
object Describe {
    fun recurrence(r: Recurrence?, oneTimeDate: String?): String {
        if (r == null) return oneTimeDate?.let { "Once on ${shortDate(it)}" } ?: "Once"
        val every = r.interval.coerceAtLeast(1)
        val start = runCatching { LocalDate.parse(r.start) }.getOrNull()
        val base = when (r.frequency) {
            Frequency.Daily -> if (every == 1) "Daily" else "Every $every days"
            Frequency.Weekly -> when (every) {
                1 -> "Weekly" + (start?.let { " on ${it.dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase)}" } ?: "")
                2 -> "Every 2 weeks"
                else -> "Every $every weeks"
            }
            Frequency.Monthly -> (if (every == 1) "Monthly" else "Every $every months") + (start?.let { " on the ${ordinal(it.dayOfMonth)}" } ?: "")
            Frequency.Yearly -> if (every == 1) "Yearly" + (start?.let { " on ${it.format(DateTimeFormatter.ofPattern("MMM d"))}" } ?: "") else "Every $every years"
        }
        return if (r.patternsJson != null) "$base (custom pattern)" else base
    }

    fun due(date: String?, today: LocalDate = LocalDate.now()): String {
        val d = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return "—"
        val days = ChronoUnit.DAYS.between(today, d)
        return when {
            days < 0 -> "${-days}d overdue"
            days == 0L -> "Today"
            days == 1L -> "Tomorrow"
            days < 7 -> "In $days days"
            else -> shortDate(date)
        }
    }

    fun shortDate(date: String): String = runCatching { LocalDate.parse(date).format(DateTimeFormatter.ofPattern("MMM d")) }.getOrDefault(date)

    fun ordinal(n: Int) = n.toString() + when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }

    fun scheduleTitle(s: Schedule, payeeNames: Map<String, String>) = s.name ?: s.payeeId?.let { payeeNames[it.raw] } ?: "Scheduled transaction"

    /** Names used to show ids: payees, categories, accounts. */
    data class Names(val payees: Map<String, String>, val categories: Map<String, String>, val accounts: Map<String, String>)

    private val fieldNames = mapOf(
        "payee" to "merchant", "imported_payee" to "bank description", "notes" to "notes", "amount" to "amount",
        "account" to "account", "category" to "category", "date" to "date", "category_group" to "category group",
        "cleared" to "cleared", "reconciled" to "reconciled", "transfer" to "transfer", "saved" to "saved",
    )
    private val opNames = mapOf(
        "is" to "is", "isNot" to "is not", "contains" to "contains", "doesNotContain" to "doesn't contain",
        "oneOf" to "is one of", "notOneOf" to "is not one of", "matches" to "matches", "gt" to "is more than",
        "gte" to "is at least", "lt" to "is less than", "lte" to "is at most", "isapprox" to "is about",
        "isbetween" to "is between", "hasTags" to "has tags", "onBudget" to "is on budget", "offBudget" to "is off budget",
    )

    fun value(field: String?, v: RuleValue, names: Names): String = when (v) {
        is RuleValue.Text -> when (field) {
            "payee" -> names.payees[v.value] ?: v.value
            "category" -> names.categories[v.value] ?: v.value
            "account" -> names.accounts[v.value] ?: v.value
            else -> "\"${v.value}\""
        }
        is RuleValue.Number -> if (field == "amount") MoneyFormat.format(Money(v.value)) else v.value.toString()
        is RuleValue.Bool -> if (v.value) "yes" else "no"
        is RuleValue.Items -> v.values.joinToString(", ") { value(field, RuleValue.Text(it), names).trim('"') }
        is RuleValue.Raw -> "(custom)"
        RuleValue.Null -> "nothing"
    }

    fun condition(c: RuleClause, names: Names): String {
        val field = fieldNames[c.field] ?: c.field ?: "?"
        val op = opNames[c.op] ?: c.op
        return if (c.op == "onBudget" || c.op == "offBudget") "$field $op" else "$field $op ${value(c.field, c.value, names)}"
    }

    fun action(a: RuleClause, names: Names): String = when (a.op) {
        "set" -> "set ${fieldNames[a.field] ?: a.field} to ${value(a.field, a.value, names)}"
        "append-notes" -> "add ${value(null, a.value, names)} to the end of notes"
        "prepend-notes" -> "add ${value(null, a.value, names)} to the start of notes"
        "set-split-amount" -> "split the transaction"
        "delete-transaction" -> "delete the transaction"
        "link-schedule" -> "link to a recurring schedule"
        else -> a.op
    }

    fun rule(r: Rule, names: Names): Pair<String, String> {
        val joiner = if (r.conditionsOp == "or") " or " else " and "
        val ifText = if (r.conditions.isEmpty()) "Every transaction" else "If " + r.conditions.joinToString(joiner) { condition(it, names) }
        val thenText = r.actions.joinToString(", ") { action(it, names) }.replaceFirstChar(Char::uppercase)
        return ifText to thenText
    }
}
