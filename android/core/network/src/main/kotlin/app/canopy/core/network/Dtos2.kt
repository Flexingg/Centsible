package app.canopy.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Phase 2 wire types (contract/openapi.yaml).

@Serializable data class RecurrenceDto(
    val frequency: String,
    val interval: Int? = null,
    val start: String,
    val endMode: String? = null,
    val endOccurrences: Int? = null,
    val endDate: String? = null,
    val skipWeekend: Boolean? = null,
    val weekendSolveMode: String? = null,
    val patterns: JsonArray? = null,
)

@Serializable data class ScheduleDto(
    val id: String,
    val name: String? = null,
    val nextDate: String? = null,
    val completed: Boolean = false,
    val postsTransaction: Boolean = false,
    val payeeId: String? = null,
    val accountId: String? = null,
    val amount: Long = 0,
    val amountMax: Long? = null,
    val amountOp: String = "is",
    val recurrence: RecurrenceDto? = null,
    val date: String? = null,
    val upcoming: List<String> = emptyList(),
)

@Serializable data class ScheduleInputDto(
    val name: String? = null,
    val payeeId: String? = null,
    val payeeName: String? = null,
    val accountId: String? = null,
    val amount: Long? = null,
    val amountMax: Long? = null,
    val amountOp: String? = null,
    val recurrence: RecurrenceDto? = null,
    val date: String? = null,
    val postsTransaction: Boolean? = null,
)

@Serializable data class RuleItemDto(
    val field: String? = null,
    val op: String,
    val value: JsonElement? = null,
    val type: String? = null,
    val options: JsonObject? = null,
)

@Serializable data class RuleDto(
    val id: String,
    val stage: String? = null,
    val conditionsOp: String = "and",
    val conditions: List<RuleItemDto> = emptyList(),
    val actions: List<RuleItemDto> = emptyList(),
    val scheduleId: String? = null,
)

@Serializable data class RuleInputDto(
    val stage: String? = null,
    val conditionsOp: String = "and",
    val conditions: List<RuleItemDto>,
    val actions: List<RuleItemDto>,
)

@Serializable data class PayeeStatDto(val id: String, val name: String, val transferAccountId: String? = null, val transactionCount: Int = 0, val ruleCount: Int = 0)
@Serializable data class RenameDto(val name: String)
@Serializable data class MergeDto(val mergeIds: List<String>)
@Serializable data class TagDto(val id: String, val tag: String, val color: String? = null, val description: String? = null)
@Serializable data class TagInputDto(val tag: String? = null, val color: String? = null)
@Serializable data class CategoryNoteDto(val categoryId: String, val note: String? = null)
@Serializable data class NoteInputDto(val note: String?)
@Serializable data class ApplyTemplatesDto(val overwrite: Boolean)
@Serializable data class TemplatesResultDto(val month: BudgetMonthDto, val message: String)

@Serializable data class JobDto(
    val id: String,
    val kind: String = "",
    val status: String,
    val error: String? = null,
    val result: JsonElement? = null,
)

@Serializable data class CsvMappingDto(
    val date: String? = null,
    val payee: String? = null,
    val amount: String? = null,
    val inflow: String? = null,
    val outflow: String? = null,
    val notes: String? = null,
)

@Serializable data class ImportOptionsDto(
    val dateFormat: String? = null,
    val hasHeaderRow: Boolean? = null,
    val invertAmounts: Boolean? = null,
    val csvMapping: CsvMappingDto? = null,
)

@Serializable data class ImportRequestDto(val fileName: String, val contentBase64: String, val options: ImportOptionsDto? = null)
@Serializable data class ImportRowDto(val date: String, val amount: Long, val payeeName: String? = null, val notes: String? = null, val importedId: String? = null)
@Serializable data class ImportPreviewDto(
    val rows: List<ImportRowDto> = emptyList(),
    val errors: List<String> = emptyList(),
    val columns: List<String> = emptyList(),
    val mapping: CsvMappingDto? = null,
    val newCount: Int = 0,
    val matchedCount: Int = 0,
)
@Serializable data class ImportResultDto(val added: Int = 0, val updated: Int = 0, val errors: List<String> = emptyList())
@Serializable data class ReconcileStatusDto(val clearedBalance: Long, val unclearedBalance: Long, val balance: Long)
@Serializable data class ReconcileRequestDto(val statementBalance: Long, val createAdjustment: Boolean)
@Serializable data class ReconcileResultDto(val reconciled: Boolean, val difference: Long, val adjustmentTransactionId: String? = null, val lockedCount: Int = 0)

@Serializable data class CashFlowMonthDto(val month: String, val income: Long, val expenses: Long, val net: Long)
@Serializable data class CashFlowDto(val months: List<CashFlowMonthDto>)
@Serializable data class SpendingCategoryDto(val categoryId: String? = null, val name: String, val groupId: String? = null, val groupName: String? = null, val amount: Long)
@Serializable data class SpendingDto(val start: String, val end: String, val total: Long, val categories: List<SpendingCategoryDto>)
@Serializable data class NetWorthPointDto(val month: String, val assets: Long, val liabilities: Long, val netWorth: Long)
@Serializable data class NetWorthDto(val points: List<NetWorthPointDto>)
