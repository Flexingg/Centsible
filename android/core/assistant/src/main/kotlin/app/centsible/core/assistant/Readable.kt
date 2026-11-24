package app.centsible.core.assistant

import app.centsible.core.model.AccountId
import app.centsible.core.model.CategoryGroupId
import app.centsible.core.model.CategoryId
import app.centsible.core.model.Money
import app.centsible.core.model.PayeeId
import app.centsible.core.model.RuleClause
import app.centsible.core.model.RuleDraft
import app.centsible.core.model.RuleValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * What the person reads: names, not ids. Ids are how Claude calls tools; on an approval card
 * or in a reply they mean nothing, so they're swapped for the name of what they point at.
 */
object Readable {
    /** Actual's ids are UUIDs, sometimes quoted. */
    private val ID = Regex("\"?\\b([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\b\"?")

    /** Every id in [text] that names something in [labels] becomes that name (quotes and all). */
    fun humanize(text: String, labels: Map<String, String>): String =
        if (labels.isEmpty() || !text.contains('-')) text else ID.replace(text) { m -> labels[m.groupValues[1].lowercase()] ?: m.value }

    /** The chat as shown: ids in replies, reasoning and cards become names. */
    fun items(items: List<ChatItem>, labels: Map<String, String>): List<ChatItem> = if (labels.isEmpty()) items else items.map { i ->
        when (i) {
            is ChatItem.Reply -> i.copy(markdown = humanize(i.markdown, labels))
            is ChatItem.Thinking -> i.copy(summary = humanize(i.summary, labels))
            is ChatItem.Change -> i.copy(change = i.change.copy(title = humanize(i.change.title, labels), details = i.change.details.map { humanize(it, labels) }))
            else -> i
        }
    }

    fun live(turn: LiveTurn, labels: Map<String, String>): LiveTurn =
        if (labels.isEmpty() || turn.blocks.isEmpty()) turn else turn.copy(blocks = turn.blocks.map { it.copy(text = humanize(it.text, labels)) })

    private val FIELDS = mapOf(
        "payee" to "merchant",
        "imported_payee" to "bank's description",
        "notes" to "notes",
        "amount" to "amount",
        "date" to "date",
        "account" to "account",
        "category" to "category",
        "category_group" to "category group",
        "cleared" to "cleared",
        "reconciled" to "reconciled",
        "transfer" to "transfer",
        "parent" to "split",
    )

    private val OPS = mapOf(
        "is" to "is",
        "isNot" to "isn't",
        "oneOf" to "is one of",
        "notOneOf" to "isn't one of",
        "contains" to "contains",
        "doesNotContain" to "doesn't contain",
        "matches" to "matches",
        "isapprox" to "is about",
        "isbetween" to "is between",
        "gt" to "is more than",
        "gte" to "is at least",
        "lt" to "is less than",
        "lte" to "is at most",
        "hasTags" to "has the tags",
    )

    fun field(f: String?) = FIELDS[f] ?: f.orEmpty().replace('_', ' ')

    /** "Costco, Target or Walmart", shortened past [max]. */
    private fun list(items: List<String>, joiner: String, max: Int = 8): String = when {
        items.isEmpty() -> "nothing"
        items.size == 1 -> items[0]
        items.size > max -> items.take(max).joinToString(", ") + " and ${items.size - max} more"
        else -> items.dropLast(1).joinToString(", ") + " $joiner " + items.last()
    }

    private suspend fun name(field: String?, id: String, names: Names): String = when (field) {
        "payee" -> names.payee(PayeeId(id))
        "account" -> names.account(AccountId(id))
        "category" -> names.category(CategoryId(id))
        "category_group" -> names.group(CategoryGroupId(id))
        else -> null
    } ?: "(something no longer here)"

    private suspend fun value(c: RuleClause, names: Names, joiner: String): String {
        val idField = c.field in setOf("payee", "account", "category", "category_group") && c.op !in setOf("contains", "doesNotContain", "matches")
        return when (val v = c.value) {
            is RuleValue.Text -> if (idField) name(c.field, v.value, names) else "“${v.value}”"
            is RuleValue.Items -> list(if (idField) v.values.map { name(c.field, it, names) } else v.values.map { "“$it”" }, joiner)
            is RuleValue.Number -> if (c.field == "amount") Money(v.value).text() else v.value.toString()
            is RuleValue.Bool -> if (v.value) "yes" else "no"
            is RuleValue.Raw -> runCatching {
                val o = Json.parseToJsonElement(v.json).jsonObject
                val a = o["num1"]?.jsonPrimitive?.longOrNull
                val b = o["num2"]?.jsonPrimitive?.longOrNull
                if (a != null && b != null) {
                    if (c.field == "amount") "${Money(a).text()} and ${Money(b).text()}" else "$a and $b"
                } else {
                    v.json
                }
            }.getOrDefault(v.json)
            RuleValue.Null -> "nothing"
        }
    }

    private suspend fun condition(c: RuleClause, names: Names): String = when (c.op) {
        "onBudget" -> "the account is on budget"
        "offBudget" -> "the account is off budget"
        else -> "${field(c.field)} ${OPS[c.op] ?: c.op} ${value(c, names, if (c.op == "notOneOf") "nor" else "or")}"
    }

    private suspend fun action(c: RuleClause, names: Names): String = when (c.op) {
        "delete-transaction" -> "Then delete the transaction"
        "set" -> "Then set the ${field(c.field)} to ${value(c, names, "and")}"
        "prepend-notes" -> "Then add ${value(c, names, "and")} to the start of the notes"
        "append-notes" -> "Then add ${value(c, names, "and")} to the end of the notes"
        "link-schedule" -> "Then link it to a recurring bill"
        "set-split-amount" -> "Then split it"
        else -> "Then ${c.op.replace('-', ' ')} ${field(c.field)} ${value(c, names, "and")}".trimEnd()
    }

    /** A rule as two or more readable lines: "If merchant is one of Costco or Target", "Then set the category to Food › Groceries". */
    suspend fun rule(r: RuleDraft, names: Names): List<String> {
        val cond = if (r.conditions.isEmpty()) "For every transaction" else "If " + r.conditions.map { condition(it, names) }.joinToString(if (r.conditionsOp == "or") ", or " else ", and ")
        return listOf(cond) + r.actions.map { action(it, names) }
    }
}
