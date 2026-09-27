package app.centsible.core.domain

import app.centsible.core.model.AccountId
import app.centsible.core.model.BudgetId
import app.centsible.core.model.BudgetMonth
import app.centsible.core.model.CashFlowMonth
import app.centsible.core.model.CategoryId
import app.centsible.core.model.ImportOptions
import app.centsible.core.model.ImportPreview
import app.centsible.core.model.ImportResult
import app.centsible.core.model.Job
import app.centsible.core.model.Money
import app.centsible.core.model.NetWorthPoint
import app.centsible.core.model.PayeeId
import app.centsible.core.model.PayeeStat
import app.centsible.core.model.ReconcileResult
import app.centsible.core.model.ReconcileStatus
import app.centsible.core.model.Rule
import app.centsible.core.model.RuleDraft
import app.centsible.core.model.Schedule
import app.centsible.core.model.ScheduleDraft
import app.centsible.core.model.SpendingReport
import app.centsible.core.model.Tag
import app.centsible.core.model.YearMonth

/** Recurring schedules, rules, merchants, tags, notes and goal templates. */
interface PlanningGateway {
    suspend fun schedules(budget: BudgetId, upcoming: Int = 3): List<Schedule>
    suspend fun createSchedule(budget: BudgetId, draft: ScheduleDraft): Schedule
    suspend fun updateSchedule(budget: BudgetId, id: String, draft: ScheduleDraft): Schedule
    suspend fun deleteSchedule(budget: BudgetId, id: String)
    suspend fun skipSchedule(budget: BudgetId, id: String): Schedule
    suspend fun postSchedule(budget: BudgetId, id: String): Schedule

    suspend fun rules(budget: BudgetId): List<Rule>
    suspend fun createRule(budget: BudgetId, draft: RuleDraft): Rule
    suspend fun updateRule(budget: BudgetId, id: String, draft: RuleDraft): Rule
    suspend fun deleteRule(budget: BudgetId, id: String)

    suspend fun payeeStats(budget: BudgetId): List<PayeeStat>
    suspend fun renamePayee(budget: BudgetId, id: PayeeId, name: String)
    suspend fun mergePayees(budget: BudgetId, into: PayeeId, merge: List<PayeeId>)
    suspend fun deletePayee(budget: BudgetId, id: PayeeId)

    suspend fun tags(budget: BudgetId): List<Tag>
    suspend fun createTag(budget: BudgetId, tag: String, color: String?): Tag
    suspend fun updateTag(budget: BudgetId, id: String, tag: String?, color: String?): Tag
    suspend fun deleteTag(budget: BudgetId, id: String)

    suspend fun categoryNote(budget: BudgetId, category: CategoryId): String?
    suspend fun setCategoryNote(budget: BudgetId, category: CategoryId, note: String?)
    suspend fun applyTemplates(budget: BudgetId, month: YearMonth, overwrite: Boolean): Pair<BudgetMonth, String>
}

/** Bank sync, statement import and reconciliation. */
interface AccountServices {
    suspend fun startBankSync(budget: BudgetId, account: AccountId?): Job
    suspend fun job(id: String): Job
    suspend fun previewImport(budget: BudgetId, account: AccountId, fileName: String, bytes: ByteArray, options: ImportOptions): ImportPreview
    suspend fun importFile(budget: BudgetId, account: AccountId, fileName: String, bytes: ByteArray, options: ImportOptions): ImportResult
    suspend fun reconcileStatus(budget: BudgetId, account: AccountId): ReconcileStatus
    suspend fun reconcile(budget: BudgetId, account: AccountId, statementBalance: Money, createAdjustment: Boolean): ReconcileResult
}

interface ReportsGateway {
    suspend fun cashFlow(budget: BudgetId, start: YearMonth, end: YearMonth): List<CashFlowMonth>
    suspend fun spending(budget: BudgetId, start: YearMonth, end: YearMonth): SpendingReport
    suspend fun netWorth(budget: BudgetId, months: Int): List<NetWorthPoint>
}
