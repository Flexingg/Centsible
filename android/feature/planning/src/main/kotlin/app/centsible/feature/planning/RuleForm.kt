package app.centsible.feature.planning

import app.centsible.core.model.Rule
import app.centsible.core.model.RuleClause
import app.centsible.core.model.RuleDraft
import app.centsible.core.model.RuleValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Everything Actual's rules can say, as a form. Field and operator lists follow Actual's
 * shared/rules.ts (TYPE_INFO and FIELD_INFO); split actions carry `options.splitIndex`
 * (1, 2, … for each split; 0 or none means the whole transaction).
 */
enum class ValueType { Id, Text, Number, Date, Bool }

enum class RuleField(val wire: String, val label: String, val type: ValueType, val ops: List<String>, val idKind: IdKind? = null) {
    Payee("payee", "Merchant", ValueType.Id, listOf("is", "isNot", "oneOf", "notOneOf", "contains", "doesNotContain", "matches"), IdKind.Payee),
    ImportedPayee("imported_payee", "Bank description", ValueType.Text, listOf("is", "isNot", "contains", "doesNotContain", "matches", "oneOf", "notOneOf")),
    Notes("notes", "Notes", ValueType.Text, listOf("is", "isNot", "contains", "doesNotContain", "matches", "hasTags", "hasAnyTag")),
    Amount("amount", "Amount", ValueType.Number, listOf("is", "isapprox", "isbetween", "gt", "gte", "lt", "lte")),
    Date("date", "Date", ValueType.Date, listOf("is", "isapprox", "gt", "gte", "lt", "lte")),
    Account("account", "Account", ValueType.Id, listOf("is", "isNot", "oneOf", "notOneOf", "onBudget", "offBudget"), IdKind.Account),
    Category("category", "Category", ValueType.Id, listOf("is", "isNot", "oneOf", "notOneOf"), IdKind.Category),
    CategoryGroup("category_group", "Category group", ValueType.Id, listOf("is", "isNot", "oneOf", "notOneOf"), IdKind.Group),
    Cleared("cleared", "Cleared", ValueType.Bool, listOf("is")),
    Reconciled("reconciled", "Reconciled", ValueType.Bool, listOf("is")),
    Transfer("transfer", "Is a transfer", ValueType.Bool, listOf("is")),
    Parent("parent", "Is split", ValueType.Bool, listOf("is")),
    ;

    companion object {
        fun of(wire: String?) = entries.firstOrNull { it.wire == wire }
    }
}

enum class IdKind { Payee, Category, Account, Group }

val OP_LABELS = mapOf(
    "is" to "is", "isNot" to "is not", "contains" to "contains", "doesNotContain" to "doesn't contain",
    "matches" to "matches (pattern)", "oneOf" to "is one of", "notOneOf" to "is not one of",
    "isapprox" to "is about", "isbetween" to "is between", "gt" to "is more than", "gte" to "is at least",
    "lt" to "is less than", "lte" to "is at most", "onBudget" to "is on budget", "offBudget" to "is off budget",
    "hasTags" to "has tags", "hasAnyTag" to "has any tag",
)

/** What an action can set, and with what kind of value. */
enum class SetField(val wire: String, val label: String, val type: ValueType, val idKind: IdKind? = null) {
    Category("category", "Category", ValueType.Id, IdKind.Category),
    Payee("payee", "Merchant", ValueType.Id, IdKind.Payee),
    Notes("notes", "Notes", ValueType.Text),
    Amount("amount", "Amount", ValueType.Number),
    Date("date", "Date", ValueType.Date),
    Account("account", "Account", ValueType.Id, IdKind.Account),
    Cleared("cleared", "Cleared", ValueType.Bool),
    ;

    companion object {
        fun of(wire: String?) = entries.firstOrNull { it.wire == wire }
    }
}

/** Amount conditions can look at money in, money out, or either (Actual's inflow/outflow). */
enum class AmountSign { Any, In, Out }

/** How an action's value is given. */
enum class ValueMode { Value, Formula, Template }

data class CondRow(
    val field: String,
    val op: String,
    val value: RuleValue,
    val sign: AmountSign = AmountSign.Any,
    /** Something the form can't edit (a recurring date, say): shown, kept as it is. */
    val locked: Boolean = false,
)

data class ActRow(
    val op: String,
    val field: String?,
    val value: RuleValue,
    val mode: ValueMode = ValueMode.Value,
    val formula: String = "",
    val locked: Boolean = false,
)

enum class SplitMethod(val wire: String, val label: String) {
    Fixed("fixed-amount", "Fixed amount"),
    Percent("fixed-percent", "% of the rest"),
    Remainder("remainder", "What's left"),
    Formula("formula", "Formula"),
}

data class SplitBlock(val method: SplitMethod, val amount: Long = 0, val percent: Double = 50.0, val formula: String = "", val actions: List<ActRow> = emptyList())

data class RuleFormState(
    val stage: String? = null,
    val anyOf: Boolean = false,
    val conditions: List<CondRow> = listOf(CondRow("payee", "is", RuleValue.Null)),
    val actions: List<ActRow> = listOf(ActRow("set", "category", RuleValue.Null)),
    val splits: List<SplitBlock> = emptyList(),
    /** Link-schedule and anything unknown: carried through unchanged. */
    val kept: List<RuleClause> = emptyList(),
)

private val json = Json { ignoreUnknownKeys = true }

private fun options(c: RuleClause): JsonObject? = c.optionsJson?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }

object RuleFormCodec {
    fun from(rule: Rule): RuleFormState {
        val conditions = rule.conditions.map { c ->
            val o = options(c)
            val sign = when {
                o?.get("inflow")?.jsonPrimitive?.booleanOrNull == true -> AmountSign.In
                o?.get("outflow")?.jsonPrimitive?.booleanOrNull == true -> AmountSign.Out
                else -> AmountSign.Any
            }
            val field = RuleField.of(c.field)
            val locked = field == null || c.op !in field.ops || (c.value is RuleValue.Raw && c.op != "isbetween")
            CondRow(c.field.orEmpty(), c.op, c.value, sign, locked)
        }
        val plain = mutableListOf<ActRow>()
        val kept = mutableListOf<RuleClause>()
        val splits = sortedMapOf<Int, SplitBlock>()
        for (a in rule.actions) {
            val o = options(a)
            val splitIndex = o?.get("splitIndex")?.jsonPrimitive?.intOrNull ?: 0
            if (a.op == "link-schedule") {
                kept += a
                continue
            }
            if (a.op == "set-split-amount") {
                val method = SplitMethod.entries.firstOrNull { it.wire == o?.get("method")?.jsonPrimitive?.contentOrNull } ?: SplitMethod.Remainder
                val block = splits[splitIndex] ?: SplitBlock(method)
                splits[splitIndex] = block.copy(
                    method = method,
                    amount = (a.value as? RuleValue.Number)?.value ?: 0,
                    percent = (a.value as? RuleValue.Number)?.value?.toDouble() ?: 50.0,
                    formula = o?.get("formula")?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
                continue
            }
            val row = actRow(a, o)
            if (splitIndex > 0) {
                val block = splits[splitIndex] ?: SplitBlock(SplitMethod.Remainder)
                splits[splitIndex] = block.copy(actions = block.actions + row)
            } else {
                plain += row
            }
        }
        return RuleFormState(rule.stage, rule.conditionsOp == "or", conditions, plain, splits.values.toList(), kept)
    }

    private fun actRow(a: RuleClause, o: JsonObject?): ActRow {
        val formula = o?.get("formula")?.jsonPrimitive?.contentOrNull
        val template = o?.get("template")?.jsonPrimitive?.contentOrNull
        val known = when (a.op) {
            "set" -> SetField.of(a.field) != null
            "prepend-notes", "append-notes", "delete-transaction" -> true
            else -> false
        }
        return when {
            formula != null -> ActRow(a.op, a.field, a.value, ValueMode.Formula, formula, !known)
            template != null -> ActRow(a.op, a.field, a.value, ValueMode.Template, template, !known)
            else -> ActRow(a.op, a.field, a.value, locked = !known)
        }
    }

    fun toDraft(f: RuleFormState): RuleDraft {
        val conditions = f.conditions.map { c ->
            val opts = when (c.sign) {
                AmountSign.In -> buildJsonObject { put("inflow", true) }.toString()
                AmountSign.Out -> buildJsonObject { put("outflow", true) }.toString()
                AmountSign.Any -> null
            }.takeIf { c.field == "amount" }
            RuleClause(c.field, c.op, c.value, typeOf(RuleField.of(c.field)?.type, c.op), opts)
        }
        val actions = f.actions.map { clause(it, 0) } +
            f.splits.flatMapIndexed { i, s ->
                val index = i + 1
                val amountOptions = buildJsonObject {
                    put("splitIndex", index)
                    put("method", s.method.wire)
                    if (s.method == SplitMethod.Formula) put("formula", s.formula)
                }.toString()
                val value = when (s.method) {
                    SplitMethod.Fixed -> RuleValue.Number(s.amount)
                    SplitMethod.Percent -> RuleValue.Number(s.percent.toLong())
                    else -> RuleValue.Null
                }
                listOf(RuleClause(null, "set-split-amount", value, "number", amountOptions)) + s.actions.map { clause(it, index) }
            } +
            f.kept
        return RuleDraft(f.stage, if (f.anyOf) "or" else "and", conditions, actions)
    }

    private fun clause(a: ActRow, splitIndex: Int): RuleClause {
        val type = when (a.op) {
            "set" -> typeOf(SetField.of(a.field)?.type, "is")
            else -> "string"
        }
        val options = if (splitIndex > 0 || a.mode != ValueMode.Value) {
            buildJsonObject {
                if (splitIndex > 0) put("splitIndex", splitIndex)
                when (a.mode) {
                    ValueMode.Formula -> put("formula", a.formula)
                    ValueMode.Template -> put("template", a.formula)
                    ValueMode.Value -> Unit
                }
            }.toString()
        } else {
            null
        }
        // A formula or template decides the value; Actual still wants one of the right type.
        val value = if (a.mode == ValueMode.Value) a.value else when (SetField.of(a.field)?.type) {
            ValueType.Number -> RuleValue.Number(0)
            ValueType.Bool -> RuleValue.Bool(false)
            else -> RuleValue.Text("")
        }
        return RuleClause(if (a.op == "set") a.field else null, a.op, if (a.op == "delete-transaction") RuleValue.Null else value, type, options)
    }

    private fun typeOf(t: ValueType?, op: String) = when (t) {
        ValueType.Id -> if (op in listOf("contains", "doesNotContain", "matches")) "string" else "id"
        ValueType.Text -> "string"
        ValueType.Number -> "number"
        ValueType.Date -> "date"
        ValueType.Bool -> "boolean"
        null -> null
    }

    /** What's missing before the rule can be saved, or null. */
    fun problem(f: RuleFormState): String? {
        if (f.actions.isEmpty() && f.splits.isEmpty()) return "Add something for the rule to do"
        f.conditions.forEach { c ->
            if (c.locked) return@forEach
            val needsValue = c.op !in listOf("onBudget", "offBudget") && RuleField.of(c.field)?.type != ValueType.Bool
            if (needsValue && isEmpty(c.value)) return "Fill in every condition"
        }
        (f.actions + f.splits.flatMap { it.actions }).forEach { a ->
            if (a.locked || a.op == "delete-transaction") return@forEach
            when (a.mode) {
                ValueMode.Formula -> if (!a.formula.trim().startsWith("=")) return "A formula starts with ="
                ValueMode.Template -> if (a.formula.isBlank()) return "Fill in the template"
                ValueMode.Value -> if (SetField.of(a.field)?.type != ValueType.Bool && a.field != "notes" && isEmpty(a.value) && a.op == "set") return "Fill in every action"
            }
        }
        f.splits.forEach { s -> if (s.method == SplitMethod.Formula && !s.formula.trim().startsWith("=")) return "A split formula starts with =" }
        return null
    }

    private fun isEmpty(v: RuleValue) = when (v) {
        RuleValue.Null -> true
        is RuleValue.Text -> v.value.isBlank()
        is RuleValue.Items -> v.values.isEmpty()
        else -> false
    }

    /** Between two amounts: Actual's {num1, num2}. */
    fun between(low: Long, high: Long) = RuleValue.Raw(buildJsonObject { put("num1", low); put("num2", high) }.toString())

    fun betweenOf(v: RuleValue): Pair<Long, Long> {
        val o = (v as? RuleValue.Raw)?.let { runCatching { json.parseToJsonElement(it.json).jsonObject }.getOrNull() }
        return (o?.get("num1")?.jsonPrimitive?.longOrNull ?: 0L) to (o?.get("num2")?.jsonPrimitive?.longOrNull ?: 0L)
    }
}
