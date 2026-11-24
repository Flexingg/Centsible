package app.centsible.core.assistant

import app.centsible.core.model.Account
import app.centsible.core.model.AccountId
import app.centsible.core.model.Automation
import app.centsible.core.model.BudgetMonth
import app.centsible.core.model.CategoryGroupId
import app.centsible.core.model.CategoryId
import app.centsible.core.model.Money
import app.centsible.core.model.PayeeId
import app.centsible.core.model.Recurrence
import app.centsible.core.model.Rule
import app.centsible.core.model.RuleClause
import app.centsible.core.model.RuleValue
import app.centsible.core.model.Schedule
import app.centsible.core.model.Transaction
import app.centsible.core.model.TransactionId
import app.centsible.core.model.YearMonth
import app.centsible.core.model.rollups
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Builds a JSON object for a tool result, leaving out nulls (they cost tokens and say
 * nothing). Money becomes a decimal amount; ids become their raw strings.
 */
fun j(vararg pairs: Pair<String, Any?>): JsonObject =
    JsonObject(pairs.mapNotNull { (k, v) -> toJson(v).takeIf { it !is JsonNull || v === ExplicitNull }?.let { k to it } }.toMap())

/** A null that [j] keeps: "set this back to nothing", unlike a missing value. */
object ExplicitNull

fun toJson(v: Any?): JsonElement = when (v) {
    null, ExplicitNull -> JsonNull
    is JsonElement -> v
    is Money -> v.decimal()
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is CategoryId -> JsonPrimitive(v.raw)
    is AccountId -> JsonPrimitive(v.raw)
    is PayeeId -> JsonPrimitive(v.raw)
    is CategoryGroupId -> JsonPrimitive(v.raw)
    is TransactionId -> JsonPrimitive(v.raw)
    is YearMonth -> JsonPrimitive(v.raw)
    is Enum<*> -> JsonPrimitive(v.name.lowercase())
    is Iterable<*> -> JsonArray(v.map(::toJson))
    is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to toJson(x) })
    else -> JsonPrimitive(v.toString())
}

val compactJson = Json { encodeDefaults = false; explicitNulls = false }

// ── Domain → JSON ──

suspend fun Names.encode(t: Transaction): JsonObject = j(
    "id" to t.id,
    "date" to t.date,
    "amount" to t.amount,
    "account" to account(t.accountId),
    "account_id" to t.accountId,
    "payee" to (payee(t.payeeId) ?: t.payeeName),
    "payee_id" to t.payeeId,
    "category" to category(t.categoryId),
    "category_id" to t.categoryId,
    "notes" to t.notes,
    "cleared" to t.cleared.takeIf { !it },
    "reconciled" to t.reconciled.takeIf { it },
    "transfer" to t.isTransfer.takeIf { it },
    "splits" to t.subtransactions.takeIf { it.isNotEmpty() }?.map { s ->
        j("id" to s.id, "amount" to s.amount, "category" to category(s.categoryId), "category_id" to s.categoryId, "notes" to s.notes)
    },
)

fun Account.encode(): JsonObject = j(
    "id" to id,
    "name" to name,
    "balance" to balance,
    "type" to type?.api,
    "on_budget" to !offBudget,
    "closed" to closed.takeIf { it },
    "bank_sync" to syncSource,
    "last_sync" to lastSync,
)

/** A month of the budget, groups with their categories (sub-categories nested, totals rolled up). */
suspend fun Names.encode(month: BudgetMonth, includeHidden: Boolean): JsonObject {
    val snap = get()
    return j(
        "month" to month.month,
        "budget_type" to month.budgetType,
        "to_budget" to month.toBudget,
        "income" to month.totalIncome,
        "budgeted" to month.totalBudgeted,
        "spent" to month.totalSpent,
        "balance" to month.totalBalance,
        "from_last_month" to month.fromLastMonth,
        "overspent_last_month" to month.lastMonthOverspent.takeIf { !it.isZero },
        "held_for_next_month" to month.forNextMonth.takeIf { !it.isZero },
        "groups" to month.groups.filter { includeHidden || !it.hidden }.map { g ->
            j(
                "id" to g.id,
                "name" to g.name,
                "income" to g.isIncome.takeIf { it },
                "hidden" to g.hidden.takeIf { it },
                "budgeted" to g.budgeted.takeIf { !g.isIncome },
                "spent" to g.spent.takeIf { !g.isIncome },
                "balance" to g.balance.takeIf { !g.isIncome },
                "received" to g.received.takeIf { g.isIncome },
                "categories" to g.rollups().filter { includeHidden || !it.own.hidden }.map { r ->
                    j(
                        "id" to r.id,
                        "name" to r.name,
                        "level" to r.level,
                        "parent_id" to r.own.parentId,
                        "hidden" to r.own.hidden.takeIf { it },
                        "transfer_off_books" to (r.id in snap.offBooks).takeIf { it },
                        "budgeted" to r.own.budgeted.takeIf { !g.isIncome },
                        "spent" to r.own.spent.takeIf { !g.isIncome },
                        "balance" to r.own.balance.takeIf { !g.isIncome },
                        "received" to r.own.received.takeIf { g.isIncome },
                        "rollover" to r.own.carryover.takeIf { it },
                        // A parent's own figures, then its branch's (its sub-categories added).
                        "branch_budgeted" to r.budgeted.takeIf { r.hasChildren && !g.isIncome },
                        "branch_spent" to r.spent.takeIf { r.hasChildren && !g.isIncome },
                        "branch_balance" to r.balance.takeIf { r.hasChildren && !g.isIncome },
                    )
                },
            )
        },
    )
}

fun Recurrence.encode(): JsonObject = j(
    "frequency" to frequency,
    "interval" to interval.takeIf { it != 1 },
    "start" to start,
    "end" to when (endMode) {
        app.centsible.core.model.EndMode.Never -> null
        app.centsible.core.model.EndMode.AfterOccurrences -> "after $endOccurrences"
        app.centsible.core.model.EndMode.OnDate -> "on $endDate"
    },
    "skip_weekend" to skipWeekend.takeIf { it },
    "weekend_moves_earlier" to weekendBefore.takeIf { skipWeekend },
    "advanced_pattern" to (patternsJson != null).takeIf { it },
)

suspend fun Names.encode(s: Schedule): JsonObject = j(
    "id" to s.id,
    "name" to s.name,
    "payee" to payee(s.payeeId),
    "payee_id" to s.payeeId,
    "account" to account(s.accountId),
    "account_id" to s.accountId,
    "amount" to s.amount,
    "amount_rule" to s.amountOp,
    "amount_max" to s.amountMax,
    "next_date" to s.nextDate,
    "upcoming" to s.upcoming.takeIf { it.isNotEmpty() },
    "repeats" to s.recurrence?.encode(),
    "date" to s.date.takeIf { s.recurrence == null },
    "posts_automatically" to s.postsTransaction.takeIf { it },
    "completed" to s.completed.takeIf { it },
)

fun RuleValue.encode(): JsonElement = when (this) {
    is RuleValue.Text -> JsonPrimitive(value)
    is RuleValue.Number -> JsonPrimitive(value)
    is RuleValue.Bool -> JsonPrimitive(value)
    is RuleValue.Items -> JsonArray(values.map(::JsonPrimitive))
    is RuleValue.Raw -> runCatching { Json.parseToJsonElement(json) }.getOrElse { JsonPrimitive(json) }
    RuleValue.Null -> JsonNull
}

fun RuleClause.encode(): JsonObject = j("field" to field, "op" to op, "value" to value.encode(), "type" to type)

fun Rule.encode(): JsonObject = j(
    "id" to id,
    "stage" to stage,
    "conditions_op" to conditionsOp,
    "conditions" to conditions.map { it.encode() },
    "actions" to actions.map { it.encode() },
    "schedule_id" to scheduleId,
)

fun Automation.encode(): JsonObject = when (this) {
    is Automation.Fixed -> j("type" to "fixed", "priority" to priority, "monthly" to monthly, "cap" to cap?.encode(), "description" to description)
    is Automation.Periodic -> j("type" to "periodic", "priority" to priority, "amount" to amount, "every" to count, "unit" to unit, "starting" to starting, "cap" to cap?.encode(), "description" to description)
    is Automation.SaveBy -> j(
        "type" to "save_by", "priority" to priority, "amount" to amount, "month" to month,
        "repeat_yearly" to repeat?.yearly, "repeat_every" to repeat?.count, "spend_from" to spendFrom, "description" to description,
    )
    is Automation.CoverSchedule -> j("type" to "cover_schedule", "priority" to priority, "schedule" to schedule, "full" to full, "adjustment" to adjustment?.encode(), "description" to description)
    is Automation.Average -> j("type" to "average", "priority" to priority, "months" to months, "adjustment" to adjustment?.encode(), "description" to description)
    is Automation.Copy -> j("type" to "copy", "priority" to priority, "months_ago" to monthsAgo, "description" to description)
    is Automation.PercentOfIncome -> j("type" to "percent_of_income", "priority" to priority, "percent" to percent, "of" to of, "previous_month" to previousMonth, "description" to description)
    is Automation.Refill -> j("type" to "refill", "priority" to priority, "cap" to cap.encode(), "description" to description)
    is Automation.Remainder -> j("type" to "remainder", "weight" to weight, "cap" to cap?.encode(), "description" to description)
    is Automation.Goal -> j("type" to "goal", "amount" to amount, "description" to description)
    is Automation.Unreadable -> j("type" to "unreadable", "line" to line, "error" to error)
}

fun Automation.Cap.encode() = j("amount" to amount, "hold" to hold.takeIf { it }, "period" to period, "start" to start)
fun Automation.Adjustment.encode() = when (this) {
    is Automation.Adjustment.Percent -> j("percent" to percent)
    is Automation.Adjustment.Fixed -> j("amount" to amount)
}

/** Caps a tool result so one call can't flood the conversation. */
fun JsonElement.limited(maxChars: Int = 60_000): JsonElement {
    val text = toString()
    if (text.length <= maxChars) return this
    return j(
        "truncated" to true,
        "note" to "This result was ${text.length} characters, too long to return whole. Narrow it down (a shorter date range, a filter, a smaller limit) and call again.",
        "start_of_result" to text.take(maxChars),
    )
}
