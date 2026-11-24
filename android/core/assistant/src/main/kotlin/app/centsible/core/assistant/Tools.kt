package app.centsible.core.assistant

import app.centsible.core.model.AccountId
import app.centsible.core.model.BudgetId
import app.centsible.core.model.CategoryGroupId
import app.centsible.core.model.CategoryId
import app.centsible.core.model.Money
import app.centsible.core.model.PayeeId
import app.centsible.core.model.TransactionId
import app.centsible.core.model.YearMonth
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * One thing the assistant can do. Read tools ([group] null) run as soon as they're called.
 * Write tools belong to a [ToolGroup]: they run only when that group is allowed and the
 * person approves the [preview] the app shows them.
 */
class AssistantTool(
    val name: String,
    val description: String,
    val schema: JsonObject,
    val group: ToolGroup?,
    private val previewBlock: (suspend (Args, ToolContext) -> Preview)? = null,
    private val runBlock: suspend (Args, ToolContext) -> ToolOutput,
) {
    val writes get() = group != null

    suspend fun run(args: Args, ctx: ToolContext): ToolOutput = runBlock(args, ctx)

    suspend fun preview(args: Args, ctx: ToolContext): Preview =
        previewBlock?.invoke(args, ctx) ?: Preview(name.replace('_', ' ').replaceFirstChar { it.uppercase() }, listOf(args.raw.toString()))
}

/** What a write will do, as the approval card shows it. */
data class Preview(val title: String, val details: List<String>)

/**
 * A tool's answer. [content] goes back to Claude; [summary] is what the chat shows; [undo]
 * is the call that takes a change back.
 */
data class ToolOutput(val content: JsonElement, val summary: String? = null, val undo: ToolCall? = null)

/** Something wrong with what the assistant asked for: goes back to it as an error to fix. */
class ToolInputException(message: String) : Exception(message)

/** Everything a tool call needs: the budget it works on, and name lookups (fresh each round). */
class ToolContext(val budget: BudgetId, val names: Names, val now: java.time.LocalDate = java.time.LocalDate.now())

// ── Money and numbers, as the assistant sees them: decimal currency units ──

fun Money.decimal(): JsonPrimitive = JsonPrimitive(BigDecimal.valueOf(minor).movePointLeft(2))
fun Money?.decimalOrNull(): JsonElement = this?.decimal() ?: JsonNull

/** "$1,234.50" style text for the approval cards. */
fun Money.text(): String {
    val v = BigDecimal.valueOf(kotlin.math.abs(minor)).movePointLeft(2)
    val s = java.text.NumberFormat.getNumberInstance(java.util.Locale.US).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }.format(v)
    return (if (minor < 0) "-" else "") + "$" + s
}

fun moneyOf(value: Double): Money = Money(BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact())

// ── Arguments ──

/** A tool call's input, with typed getters that explain what's wrong instead of crashing. */
class Args(val raw: JsonObject) {
    private fun prim(key: String): JsonPrimitive? = (raw[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }

    fun has(key: String) = raw.containsKey(key) && raw[key] !is JsonNull
    fun isNull(key: String) = raw.containsKey(key) && raw[key] is JsonNull

    fun string(key: String): String = optString(key) ?: throw ToolInputException("\"$key\" is required")
    fun optString(key: String): String? = prim(key)?.let { if (it.isString) it.content else it.contentOrNull }?.takeIf { it.isNotBlank() }

    fun int(key: String): Int = optInt(key) ?: throw ToolInputException("\"$key\" is required (a whole number)")
    fun optInt(key: String): Int? = prim(key)?.let { it.longOrNull?.toInt() ?: it.doubleOrNull?.toInt() ?: throw ToolInputException("\"$key\" should be a whole number") }

    fun double(key: String): Double = optDouble(key) ?: throw ToolInputException("\"$key\" is required (a number)")
    fun optDouble(key: String): Double? = prim(key)?.let { it.doubleOrNull ?: throw ToolInputException("\"$key\" should be a number") }

    fun bool(key: String, default: Boolean): Boolean = prim(key)?.let { it.booleanOrNull ?: throw ToolInputException("\"$key\" should be true or false") } ?: default
    fun optBool(key: String): Boolean? = prim(key)?.let { it.booleanOrNull ?: throw ToolInputException("\"$key\" should be true or false") }

    /** An amount in currency units (12.5 is $12.50). */
    fun money(key: String): Money = optMoney(key) ?: throw ToolInputException("\"$key\" is required (an amount, like 12.50)")
    fun optMoney(key: String): Money? = optDouble(key)?.let(::moneyOf)

    fun month(key: String): YearMonth = optMonth(key) ?: throw ToolInputException("\"$key\" is required (YYYY-MM)")
    fun optMonth(key: String): YearMonth? = optString(key)?.let {
        runCatching { YearMonth(it.trim()) }.getOrElse { throw ToolInputException("\"$key\" should be a month like 2026-11") }
    }

    fun date(key: String): String = optDate(key) ?: throw ToolInputException("\"$key\" is required (YYYY-MM-DD)")
    fun optDate(key: String): String? = optString(key)?.trim()?.also {
        runCatching { java.time.LocalDate.parse(it) }.getOrElse { throw ToolInputException("\"$key\" should be a date like 2026-11-24") }
    }

    fun category(key: String) = CategoryId(string(key))
    fun optCategory(key: String) = optString(key)?.let(::CategoryId)
    fun group(key: String) = CategoryGroupId(string(key))
    fun account(key: String) = AccountId(string(key))
    fun optAccount(key: String) = optString(key)?.let(::AccountId)
    fun payee(key: String) = PayeeId(string(key))
    fun optPayee(key: String) = optString(key)?.let(::PayeeId)
    fun transaction(key: String) = TransactionId(string(key))

    fun strings(key: String): List<String> = optStrings(key) ?: throw ToolInputException("\"$key\" is required (a list)")
    fun optStrings(key: String): List<String>? = (raw[key] as? JsonArray)?.map { el ->
        (el as? JsonPrimitive)?.contentOrNull ?: throw ToolInputException("\"$key\" should be a list of strings")
    } ?: raw[key]?.takeIf { it !is JsonNull }?.let { throw ToolInputException("\"$key\" should be a list") }

    fun objects(key: String): List<Args> = optObjects(key) ?: throw ToolInputException("\"$key\" is required (a list of objects)")
    fun optObjects(key: String): List<Args>? = (raw[key] as? JsonArray)?.map { el ->
        Args(el as? JsonObject ?: throw ToolInputException("\"$key\" should be a list of objects"))
    }

    fun obj(key: String): Args? = (raw[key] as? JsonObject)?.let(::Args)

    fun <E : Enum<E>> enum(key: String, values: Array<E>, wire: (E) -> String = { it.name.lowercase() }): E =
        optEnum(key, values, wire) ?: throw ToolInputException("\"$key\" is required: one of ${values.joinToString { wire(it) }}")

    fun <E : Enum<E>> optEnum(key: String, values: Array<E>, wire: (E) -> String = { it.name.lowercase() }): E? =
        optString(key)?.let { v -> values.firstOrNull { wire(it).equals(v.trim(), ignoreCase = true) } ?: throw ToolInputException("\"$key\" should be one of ${values.joinToString { wire(it) }}") }
}

// ── Schemas ──

/** Builds a tool's input schema: `schema { string("month", "...", required = true) }`. */
class SchemaBuilder {
    private val props = linkedMapOf<String, JsonObject>()
    private val required = mutableListOf<String>()

    private fun add(name: String, required: Boolean, body: JsonObject) {
        props[name] = body
        if (required) this.required += name
    }

    fun string(name: String, description: String, required: Boolean = false) = add(name, required, buildJsonObject { put("type", "string"); put("description", description) })
    fun number(name: String, description: String, required: Boolean = false) = add(name, required, buildJsonObject { put("type", "number"); put("description", description) })
    fun integer(name: String, description: String, required: Boolean = false, min: Int? = null, max: Int? = null) = add(
        name,
        required,
        buildJsonObject {
            put("type", "integer")
            put("description", description)
            min?.let { put("minimum", it) }
            max?.let { put("maximum", it) }
        },
    )
    fun boolean(name: String, description: String, required: Boolean = false) = add(name, required, buildJsonObject { put("type", "boolean"); put("description", description) })
    fun nullableString(name: String, description: String, required: Boolean = false) = add(
        name,
        required,
        buildJsonObject {
            put("type", buildJsonArray { add(JsonPrimitive("string")); add(JsonPrimitive("null")) })
            put("description", description)
        },
    )
    fun enum(name: String, values: List<String>, description: String, required: Boolean = false) = add(
        name,
        required,
        buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
            put("description", description)
        },
    )
    fun strings(name: String, description: String, required: Boolean = false) = add(
        name,
        required,
        buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject { put("type", "string") })
            put("description", description)
        },
    )
    fun objects(name: String, description: String, required: Boolean = false, item: SchemaBuilder.() -> Unit) = add(
        name,
        required,
        buildJsonObject {
            put("type", "array")
            put("items", schema(item))
            put("description", description)
        },
    )
    fun obj(name: String, description: String, required: Boolean = false, body: SchemaBuilder.() -> Unit) =
        add(name, required, JsonObject(schema(body) + ("description" to JsonPrimitive(description))))
    /** Any JSON (for shapes too varied to spell out, described in words). */
    fun any(name: String, description: String, required: Boolean = false) = add(name, required, buildJsonObject { put("description", description) })

    fun build(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(props))
        if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        put("additionalProperties", false)
    }
}

fun schema(body: SchemaBuilder.() -> Unit = {}): JsonObject = SchemaBuilder().apply(body).build()

/** Common parameters, described the same way everywhere. */
fun SchemaBuilder.month(name: String = "month", required: Boolean = false, what: String = "The budget month, YYYY-MM. Defaults to this month.") = string(name, what, required)
fun SchemaBuilder.amount(name: String, what: String, required: Boolean = false) =
    number(name, "$what In currency units (12.5 is 12.50); negative is money out where the field is a transaction amount.", required)
