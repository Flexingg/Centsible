package app.centsible.core.engine.bridge

import app.centsible.core.model.AccountId
import app.centsible.core.model.AmountOp
import app.centsible.core.model.CashFlowMonth
import app.centsible.core.model.CategoryId
import app.centsible.core.model.CsvMapping
import app.centsible.core.model.EndMode
import app.centsible.core.model.Frequency
import app.centsible.core.model.ImportOptions
import app.centsible.core.model.ImportPreview
import app.centsible.core.model.ImportResult
import app.centsible.core.model.ImportRow
import app.centsible.core.model.Job
import app.centsible.core.model.JobStatus
import app.centsible.core.model.Money
import app.centsible.core.model.NetWorthPoint
import app.centsible.core.model.PayeeId
import app.centsible.core.model.PayeeStat
import app.centsible.core.model.Recurrence
import app.centsible.core.model.Rule
import app.centsible.core.model.RuleClause
import app.centsible.core.model.RuleDraft
import app.centsible.core.model.RuleValue
import app.centsible.core.model.Schedule
import app.centsible.core.model.ScheduleDraft
import app.centsible.core.model.SpendingCategory
import app.centsible.core.model.SpendingReport
import app.centsible.core.model.Tag
import app.centsible.core.model.YearMonth
import app.centsible.core.network.CashFlowMonthDto
import app.centsible.core.network.CsvMappingDto
import app.centsible.core.network.ImportOptionsDto
import app.centsible.core.network.ImportPreviewDto
import app.centsible.core.network.ImportResultDto
import app.centsible.core.network.JobDto
import app.centsible.core.network.NetWorthPointDto
import app.centsible.core.network.PayeeStatDto
import app.centsible.core.network.RecurrenceDto
import app.centsible.core.network.RuleDto
import app.centsible.core.network.RuleInputDto
import app.centsible.core.network.RuleItemDto
import app.centsible.core.network.ScheduleDto
import app.centsible.core.network.ScheduleInputDto
import app.centsible.core.network.SpendingDto
import app.centsible.core.network.TagDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull

private val jsonParser = Json { ignoreUnknownKeys = true }

internal fun RecurrenceDto.toModel() = Recurrence(
    frequency = when (frequency) {
        "daily" -> Frequency.Daily
        "weekly" -> Frequency.Weekly
        "yearly" -> Frequency.Yearly
        else -> Frequency.Monthly
    },
    interval = interval ?: 1,
    start = start,
    endMode = when (endMode) {
        "after_n_occurrences" -> EndMode.AfterOccurrences
        "on_date" -> EndMode.OnDate
        else -> EndMode.Never
    },
    endOccurrences = endOccurrences,
    endDate = endDate,
    skipWeekend = skipWeekend ?: false,
    patternsJson = patterns?.takeIf { it.isNotEmpty() }?.toString(),
)

internal fun Recurrence.toDto() = RecurrenceDto(
    frequency = frequency.name.lowercase(),
    interval = interval,
    start = start,
    endMode = when (endMode) {
        EndMode.Never -> "never"
        EndMode.AfterOccurrences -> "after_n_occurrences"
        EndMode.OnDate -> "on_date"
    },
    endOccurrences = endOccurrences,
    endDate = endDate,
    skipWeekend = skipWeekend,
    patterns = patternsJson?.let { jsonParser.parseToJsonElement(it).jsonArray },
)

private fun String.toAmountOp() = when (this) {
    "isapprox" -> AmountOp.IsApprox
    "isbetween" -> AmountOp.IsBetween
    else -> AmountOp.Is
}

private fun AmountOp.wire() = when (this) {
    AmountOp.Is -> "is"
    AmountOp.IsApprox -> "isapprox"
    AmountOp.IsBetween -> "isbetween"
}

internal fun ScheduleDto.toModel() = Schedule(
    id = id,
    name = name,
    nextDate = nextDate,
    completed = completed,
    postsTransaction = postsTransaction,
    payeeId = payeeId?.let(::PayeeId),
    accountId = accountId?.let(::AccountId),
    amount = Money(amount),
    amountMax = amountMax?.let(::Money),
    amountOp = amountOp.toAmountOp(),
    recurrence = recurrence?.toModel(),
    date = date,
    upcoming = upcoming,
)

internal fun ScheduleDraft.toDto() = ScheduleInputDto(
    name = name,
    payeeId = payeeId?.raw,
    payeeName = payeeName.takeIf { payeeId == null },
    accountId = accountId?.raw,
    amount = amount.minor,
    amountMax = amountMax?.minor,
    amountOp = amountOp.wire(),
    recurrence = recurrence?.toDto(),
    date = date.takeIf { recurrence == null },
    postsTransaction = postsTransaction,
)

internal fun JsonElement?.toRuleValue(): RuleValue = when (this) {
    null, JsonNull -> RuleValue.Null
    is JsonPrimitive -> when {
        isString -> RuleValue.Text(content)
        booleanOrNull != null -> RuleValue.Bool(booleanOrNull!!)
        longOrNull != null -> RuleValue.Number(longOrNull!!)
        else -> RuleValue.Raw(toString())
    }
    is JsonArray -> if (all { it is JsonPrimitive && it.isString }) RuleValue.Items(map { (it as JsonPrimitive).content }) else RuleValue.Raw(toString())
    is JsonObject -> RuleValue.Raw(toString())
}

internal fun RuleValue.toJson(): JsonElement = when (this) {
    is RuleValue.Text -> JsonPrimitive(value)
    is RuleValue.Number -> JsonPrimitive(value)
    is RuleValue.Bool -> JsonPrimitive(value)
    is RuleValue.Items -> JsonArray(values.map(::JsonPrimitive))
    is RuleValue.Raw -> jsonParser.parseToJsonElement(json)
    RuleValue.Null -> JsonNull
}

internal fun RuleItemDto.toModel() = RuleClause(field, op, value.toRuleValue(), type, options?.toString())

internal fun RuleClause.toDto() = RuleItemDto(
    field = field,
    op = op,
    value = value.toJson(),
    type = type,
    options = optionsJson?.let { (jsonParser.parseToJsonElement(it) as? JsonObject) },
)

internal fun RuleDto.toModel() = Rule(id, stage, conditionsOp, conditions.map { it.toModel() }, actions.map { it.toModel() }, scheduleId)
internal fun RuleDraft.toDto() = RuleInputDto(stage, conditionsOp, conditions.map { it.toDto() }, actions.map { it.toDto() })

internal fun PayeeStatDto.toModel() = PayeeStat(PayeeId(id), name, transferAccountId?.let(::AccountId), transactionCount, ruleCount)
internal fun TagDto.toModel() = Tag(id, tag, color, description)

internal fun JobDto.toModel() = Job(
    id = id,
    status = when (status) {
        "running" -> JobStatus.Running
        "succeeded" -> JobStatus.Succeeded
        "failed" -> JobStatus.Failed
        else -> JobStatus.Unknown
    },
    error = error,
    newTransactions = ((result as? JsonObject)?.get("newTransactions") as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
)

internal fun CsvMappingDto.toModel() = CsvMapping(date, payee, amount, inflow, outflow, notes)
internal fun CsvMapping.toDto() = CsvMappingDto(date, payee, amount, inflow, outflow, notes)
internal fun ImportOptions.toDto() = ImportOptionsDto(dateFormat, hasHeaderRow, invertAmounts, csvMapping?.toDto())

internal fun ImportPreviewDto.toModel() = ImportPreview(
    rows = rows.map { ImportRow(it.date, Money(it.amount), it.payeeName, it.notes) },
    errors = errors,
    columns = columns,
    mapping = mapping?.toModel(),
    newCount = newCount,
    matchedCount = matchedCount,
)

internal fun ImportResultDto.toModel() = ImportResult(added, updated, errors)
internal fun CashFlowMonthDto.toModel() = CashFlowMonth(YearMonth(month), Money(income), Money(expenses), Money(net))
internal fun SpendingDto.toModel() = SpendingReport(
    Money(total),
    categories.map { SpendingCategory(it.categoryId?.let(::CategoryId), it.name, it.groupName, Money(it.amount)) },
)
internal fun NetWorthPointDto.toModel() = NetWorthPoint(YearMonth(month), Money(assets), Money(liabilities), Money(netWorth))
