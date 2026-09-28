package app.centsible.core.model

enum class Frequency { Daily, Weekly, Monthly, Yearly }
enum class EndMode { Never, AfterOccurrences, OnDate }
enum class AmountOp { Is, IsApprox, IsBetween }

/** How a schedule repeats (Actual's RecurConfig). Unknown extras are kept verbatim. */
data class Recurrence(
    val frequency: Frequency,
    val interval: Int = 1,
    val start: String,
    val endMode: EndMode = EndMode.Never,
    val endOccurrences: Int? = null,
    val endDate: String? = null,
    val skipWeekend: Boolean = false,
    /** Advanced patterns ("2nd Tuesday") set in Actual's web app; kept, not edited here. */
    val patternsJson: String? = null,
)

data class Schedule(
    val id: String,
    val name: String?,
    val nextDate: String?,
    val completed: Boolean,
    val postsTransaction: Boolean,
    val payeeId: PayeeId?,
    val accountId: AccountId?,
    val amount: Money,
    val amountMax: Money?,
    val amountOp: AmountOp,
    val recurrence: Recurrence?,
    val date: String?,
    val upcoming: List<String>,
)

data class ScheduleDraft(
    val name: String?,
    val payeeId: PayeeId? = null,
    val payeeName: String? = null,
    val accountId: AccountId?,
    val amount: Money,
    val amountOp: AmountOp = AmountOp.IsApprox,
    val amountMax: Money? = null,
    val recurrence: Recurrence? = null,
    val date: String? = null,
    val postsTransaction: Boolean = false,
)

/** A rule value. Ids (payee, category, account) arrive as [Text]. */
sealed interface RuleValue {
    data class Text(val value: String) : RuleValue
    data class Number(val value: Long) : RuleValue
    data class Bool(val value: Boolean) : RuleValue
    data class Items(val values: List<String>) : RuleValue
    /** Anything else (date configs, ranges): kept as JSON so edits elsewhere survive. */
    data class Raw(val json: String) : RuleValue
    data object Null : RuleValue
}

data class RuleClause(
    val field: String?,
    val op: String,
    val value: RuleValue,
    val type: String? = null,
    val optionsJson: String? = null,
)

data class Rule(
    val id: String,
    val stage: String?,
    val conditionsOp: String,
    val conditions: List<RuleClause>,
    val actions: List<RuleClause>,
    /** Set for the hidden rule behind a schedule; manage it through the schedule. */
    val scheduleId: String?,
)

data class RuleDraft(
    val stage: String? = null,
    val conditionsOp: String = "and",
    val conditions: List<RuleClause>,
    val actions: List<RuleClause>,
)

data class PayeeStat(
    val id: PayeeId,
    val name: String,
    val transferAccountId: AccountId?,
    val transactionCount: Int,
    val ruleCount: Int,
)

data class Tag(val id: String, val tag: String, val color: String?, val description: String?)

enum class JobStatus { Running, Succeeded, Failed, Unknown }

data class Job(val id: String, val status: JobStatus, val error: String?, val newTransactions: Int?, val results: List<AccountSyncResult> = emptyList())

data class CsvMapping(
    val date: String? = null,
    val payee: String? = null,
    val amount: String? = null,
    val inflow: String? = null,
    val outflow: String? = null,
    val notes: String? = null,
)

data class ImportOptions(
    val dateFormat: String? = null,
    val hasHeaderRow: Boolean = true,
    val invertAmounts: Boolean = false,
    val csvMapping: CsvMapping? = null,
)

data class ImportRow(val date: String, val amount: Money, val payeeName: String?, val notes: String?)

data class ImportPreview(
    val rows: List<ImportRow>,
    val errors: List<String>,
    val columns: List<String>,
    val mapping: CsvMapping?,
    val newCount: Int,
    val matchedCount: Int,
)

data class ImportResult(val added: Int, val updated: Int, val errors: List<String>)

data class ReconcileStatus(val cleared: Money, val uncleared: Money, val balance: Money)

data class ReconcileResult(val reconciled: Boolean, val difference: Money, val adjustmentId: TransactionId?, val lockedCount: Int)

data class CashFlowMonth(val month: YearMonth, val income: Money, val expenses: Money, val net: Money)

data class SpendingCategory(val categoryId: CategoryId?, val name: String, val groupName: String?, val amount: Money)

data class SpendingReport(val total: Money, val categories: List<SpendingCategory>)

data class NetWorthPoint(val month: YearMonth, val assets: Money, val liabilities: Money, val netWorth: Money)
