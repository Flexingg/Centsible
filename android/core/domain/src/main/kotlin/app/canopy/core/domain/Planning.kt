package app.canopy.core.domain

import app.canopy.core.model.AccountId
import app.canopy.core.model.BudgetId
import app.canopy.core.model.BudgetMonth
import app.canopy.core.model.CashFlowMonth
import app.canopy.core.model.CategoryId
import app.canopy.core.model.ImportOptions
import app.canopy.core.model.ImportPreview
import app.canopy.core.model.ImportResult
import app.canopy.core.model.Job
import app.canopy.core.model.Money
import app.canopy.core.model.NetWorthPoint
import app.canopy.core.model.PayeeId
import app.canopy.core.model.PayeeStat
import app.canopy.core.model.ReconcileResult
import app.canopy.core.model.ReconcileStatus
import app.canopy.core.model.Rule
import app.canopy.core.model.RuleDraft
import app.canopy.core.model.Schedule
import app.canopy.core.model.ScheduleDraft
import app.canopy.core.model.SpendingReport
import app.canopy.core.model.Tag
import app.canopy.core.model.YearMonth

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
