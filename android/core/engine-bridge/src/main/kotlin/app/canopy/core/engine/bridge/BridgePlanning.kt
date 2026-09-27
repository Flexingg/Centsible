package app.canopy.core.engine.bridge

import app.canopy.core.domain.AccountServices
import app.canopy.core.domain.PlanningGateway
import app.canopy.core.domain.ReportsGateway
import app.canopy.core.model.AccountId
import app.canopy.core.model.BudgetId
import app.canopy.core.model.CategoryId
import app.canopy.core.model.ImportOptions
import app.canopy.core.model.Money
import app.canopy.core.model.PayeeId
import app.canopy.core.model.ReconcileResult
import app.canopy.core.model.ReconcileStatus
import app.canopy.core.model.RuleDraft
import app.canopy.core.model.ScheduleDraft
import app.canopy.core.model.TransactionId
import app.canopy.core.model.YearMonth
import app.canopy.core.network.ImportRequestDto
import app.canopy.core.network.PlanningApi
import app.canopy.core.network.ReconcileRequestDto
import app.canopy.core.network.TagInputDto
import java.util.Base64

/**
 * Phase 2 gateways over the bridge. [onWrite] is called after every successful change
 * so open screens reload (see BudgetChanges).
 */
class BridgePlanningGateway(private val api: PlanningApi, private val onWrite: () -> Unit) : PlanningGateway {
    private suspend fun <T> write(block: suspend () -> T): T = block().also { onWrite() }

    override suspend fun schedules(budget: BudgetId, upcoming: Int) = api.schedules(budget.raw, upcoming).map { it.toModel() }
    override suspend fun createSchedule(budget: BudgetId, draft: ScheduleDraft) = write { api.createSchedule(budget.raw, draft.toDto()).toModel() }
    override suspend fun updateSchedule(budget: BudgetId, id: String, draft: ScheduleDraft) = write { api.updateSchedule(budget.raw, id, draft.toDto()).toModel() }
    override suspend fun deleteSchedule(budget: BudgetId, id: String) = write { api.deleteSchedule(budget.raw, id) }
    override suspend fun skipSchedule(budget: BudgetId, id: String) = write { api.skipSchedule(budget.raw, id).toModel() }
    override suspend fun postSchedule(budget: BudgetId, id: String) = write { api.postSchedule(budget.raw, id).toModel() }

    override suspend fun rules(budget: BudgetId) = api.rules(budget.raw).map { it.toModel() }
    override suspend fun createRule(budget: BudgetId, draft: RuleDraft) = write { api.createRule(budget.raw, draft.toDto()).toModel() }
    override suspend fun updateRule(budget: BudgetId, id: String, draft: RuleDraft) = write { api.updateRule(budget.raw, id, draft.toDto()).toModel() }
    override suspend fun deleteRule(budget: BudgetId, id: String) = write { api.deleteRule(budget.raw, id) }

    override suspend fun payeeStats(budget: BudgetId) = api.payeeStats(budget.raw).map { it.toModel() }
    override suspend fun renamePayee(budget: BudgetId, id: PayeeId, name: String) = write { api.renamePayee(budget.raw, id.raw, name) }
    override suspend fun mergePayees(budget: BudgetId, into: PayeeId, merge: List<PayeeId>) = write { api.mergePayees(budget.raw, into.raw, merge.map { it.raw }) }
    override suspend fun deletePayee(budget: BudgetId, id: PayeeId) = write { api.deletePayee(budget.raw, id.raw) }

    override suspend fun tags(budget: BudgetId) = api.tags(budget.raw).map { it.toModel() }
    override suspend fun createTag(budget: BudgetId, tag: String, color: String?) = write { api.createTag(budget.raw, TagInputDto(tag, color)).toModel() }
    override suspend fun updateTag(budget: BudgetId, id: String, tag: String?, color: String?) = write { api.updateTag(budget.raw, id, TagInputDto(tag, color)).toModel() }
    override suspend fun deleteTag(budget: BudgetId, id: String) = write { api.deleteTag(budget.raw, id) }

    override suspend fun categoryNote(budget: BudgetId, category: CategoryId) = api.categoryNote(budget.raw, category.raw).note
    override suspend fun setCategoryNote(budget: BudgetId, category: CategoryId, note: String?) = write { api.setCategoryNote(budget.raw, category.raw, note); Unit }
    override suspend fun applyTemplates(budget: BudgetId, month: YearMonth, overwrite: Boolean) =
        write { api.applyTemplates(budget.raw, month.raw, overwrite).let { it.month.toModel() to it.message } }
}

class BridgeAccountServices(private val api: PlanningApi, private val onWrite: () -> Unit) : AccountServices {
    override suspend fun startBankSync(budget: BudgetId, account: AccountId?) = api.bankSync(budget.raw, account?.raw).toModel()
    override suspend fun job(id: String) = api.job(id).toModel()

    private fun request(fileName: String, bytes: ByteArray, options: ImportOptions) =
        ImportRequestDto(fileName, Base64.getEncoder().encodeToString(bytes), options.toDto())

    override suspend fun previewImport(budget: BudgetId, account: AccountId, fileName: String, bytes: ByteArray, options: ImportOptions) =
        api.previewImport(budget.raw, account.raw, request(fileName, bytes, options)).toModel()

    override suspend fun importFile(budget: BudgetId, account: AccountId, fileName: String, bytes: ByteArray, options: ImportOptions) =
        api.importFile(budget.raw, account.raw, request(fileName, bytes, options)).toModel().also { onWrite() }

    override suspend fun reconcileStatus(budget: BudgetId, account: AccountId) =
        api.reconcileStatus(budget.raw, account.raw).let { ReconcileStatus(Money(it.clearedBalance), Money(it.unclearedBalance), Money(it.balance)) }

    override suspend fun reconcile(budget: BudgetId, account: AccountId, statementBalance: Money, createAdjustment: Boolean) =
        api.reconcile(budget.raw, account.raw, ReconcileRequestDto(statementBalance.minor, createAdjustment)).let {
            ReconcileResult(it.reconciled, Money(it.difference), it.adjustmentTransactionId?.let(::TransactionId), it.lockedCount)
        }.also { if (it.reconciled) onWrite() }
}

class BridgeReports(private val api: PlanningApi) : ReportsGateway {
    override suspend fun cashFlow(budget: BudgetId, start: YearMonth, end: YearMonth) = api.cashFlow(budget.raw, start.raw, end.raw).months.map { it.toModel() }
    override suspend fun spending(budget: BudgetId, start: YearMonth, end: YearMonth) = api.spending(budget.raw, start.raw, end.raw).toModel()
    override suspend fun netWorth(budget: BudgetId, months: Int) = api.netWorth(budget.raw, months).points.map { it.toModel() }
}
