package app.centsible.core.engine.bridge

import app.centsible.core.domain.AccountServices
import app.centsible.core.domain.PlanningGateway
import app.centsible.core.domain.ReportsGateway
import app.centsible.core.model.AccountId
import app.centsible.core.model.BudgetId
import app.centsible.core.model.CategoryId
import app.centsible.core.model.ImportOptions
import app.centsible.core.model.Money
import app.centsible.core.model.PayeeId
import app.centsible.core.model.ReconcileResult
import app.centsible.core.model.ReconcileStatus
import app.centsible.core.model.RuleDraft
import app.centsible.core.model.ScheduleDraft
import app.centsible.core.model.TransactionId
import app.centsible.core.model.YearMonth
import app.centsible.core.network.ImportRequestDto
import app.centsible.core.network.PlanningApi
import app.centsible.core.network.ReconcileRequestDto
import app.centsible.core.network.TagInputDto
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

class BridgeBankSync(private val api: PlanningApi, private val onWrite: () -> Unit) : app.centsible.core.domain.BankSyncGateway {
    override suspend fun overview() = api.bankSyncOverview().toModel()
    override suspend fun connect(setupToken: String) = api.connectSimpleFin(setupToken.trim()).toModel()
    override suspend fun disconnect() = api.resetSimpleFin()
    override suspend fun setSchedule(intervalHours: Int) = api.setSyncSchedule(intervalHours).toModel()
    override suspend fun externalAccounts(budget: BudgetId, refresh: Boolean) = api.externalAccounts(budget.raw, refresh).map {
        app.centsible.core.model.ExternalAccount(it.id, it.name, it.institution, Money(it.balance), it.linkedAccountId?.let(::AccountId), it.linkedAccountName)
    }
    override suspend fun link(budget: BudgetId, externalId: String, existing: AccountId?, offBudget: Boolean) =
        AccountId(api.linkSimpleFin(budget.raw, app.centsible.core.network.LinkRequestDto(externalId, existing?.raw, offBudget.takeIf { existing == null })).accountId).also { onWrite() }
    override suspend fun unlink(budget: BudgetId, account: AccountId) = api.unlink(budget.raw, account.raw).also { onWrite() }
    override suspend fun settings(budget: BudgetId, account: AccountId) = api.bankSyncSettings(budget.raw, account.raw).toModel()
    override suspend fun updateSettings(budget: BudgetId, account: AccountId, settings: app.centsible.core.model.BankSyncSettings) =
        api.updateBankSyncSettings(budget.raw, account.raw, settings.toDto()).toModel()
}

private fun app.centsible.core.network.BankSyncOverviewDto.toModel() =
    app.centsible.core.model.BankSyncOverview(simplefin.configured, simplefin.requestsToday, simplefin.dailyQuota, schedule.toModel(), intervals)

private fun app.centsible.core.network.ScheduleStateDto.toModel() = app.centsible.core.model.SyncSchedule(
    intervalHours, lastRunAt, nextRunAt,
    lastResult?.let { app.centsible.core.model.SyncRunResult(it.newTransactions, it.accounts, it.errors, it.skipped) },
)

private fun app.centsible.core.network.FieldMappingDto.toModel() = app.centsible.core.model.FieldMapping(date, payee, notes)
private fun app.centsible.core.model.FieldMapping.toDto() = app.centsible.core.network.FieldMappingDto(date, payee, notes)

private fun app.centsible.core.network.BankSyncSettingsDto.toModel() = app.centsible.core.model.BankSyncSettings(
    importTransactions, importPending, importNotes, reimportDeleted, updateDates, mapping.payment.toModel(), mapping.deposit.toModel(),
)

private fun app.centsible.core.model.BankSyncSettings.toDto() = app.centsible.core.network.BankSyncSettingsDto(
    importTransactions, importPending, importNotes, reimportDeleted, updateDates,
    app.centsible.core.network.SyncMappingsDto(payment.toDto(), deposit.toDto()),
)
