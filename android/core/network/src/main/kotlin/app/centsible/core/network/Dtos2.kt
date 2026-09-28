package app.centsible.core.network

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
/** `note` is required and may be null (clears it), so it's a JsonElement: JsonNull is always sent. */
@Serializable data class NoteInputDto(val note: JsonElement)
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

@Serializable data class SimpleFinStatusDto(val configured: Boolean, val requestsToday: Int = 0, val dailyQuota: Int = 24)
@Serializable data class SyncRunResultDto(val newTransactions: Int = 0, val accounts: Int = 0, val errors: List<String> = emptyList(), val skipped: String? = null)
@Serializable data class ScheduleStateDto(val intervalHours: Int, val lastRunAt: String? = null, val nextRunAt: String? = null, val lastResult: SyncRunResultDto? = null)
@Serializable data class BankSyncOverviewDto(val simplefin: SimpleFinStatusDto, val schedule: ScheduleStateDto, val intervals: List<Int> = listOf(0, 2, 4, 6, 12, 24))
@Serializable data class SetupTokenDto(val setupToken: String)
@Serializable data class ScheduleInputDtoBankSync(val intervalHours: Int)
@Serializable data class ExternalAccountDto(
    val id: String,
    val name: String,
    val institution: String? = null,
    val balance: Long,
    val currency: String = "USD",
    val linkedAccountId: String? = null,
    val linkedAccountName: String? = null,
)
@Serializable data class LinkRequestDto(val externalId: String, val accountId: String? = null, val offBudget: Boolean? = null)
@Serializable data class LinkedDto(val accountId: String)
@Serializable data class FieldMappingDto(val date: String, val payee: String, val notes: String)
@Serializable data class SyncMappingsDto(val payment: FieldMappingDto, val deposit: FieldMappingDto)
@Serializable data class BankSyncSettingsDto(
    val importTransactions: Boolean,
    val importPending: Boolean,
    val importNotes: Boolean,
    val reimportDeleted: Boolean,
    val updateDates: Boolean,
    val mapping: SyncMappingsDto,
)
@Serializable data class AccountSyncResultDto(val accountId: String, val name: String, val newTransactions: Int = 0, val matchedTransactions: Int = 0, val error: String? = null, val status: String? = null)
@Serializable data class SyncSummaryDto(val accounts: Int = 0, val newTransactions: Int = 0, val results: List<AccountSyncResultDto> = emptyList(), val simplefinRequests: Int = 0)
