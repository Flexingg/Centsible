package app.canopy.core.engine.bridge

import app.canopy.core.domain.BudgetEngine
import app.canopy.core.domain.TransactionQuery
import app.canopy.core.model.BudgetId
import app.canopy.core.model.BudgetMonth
import app.canopy.core.model.BudgetPot
import app.canopy.core.model.CategoryId
import app.canopy.core.model.Money
import app.canopy.core.model.NewTransaction
import app.canopy.core.model.Page
import app.canopy.core.model.YearMonth
import app.canopy.core.network.BridgeApi
import app.canopy.core.network.CategoryBudgetPatchDto
import app.canopy.core.network.MoneyTransferDto
import app.canopy.core.network.NewSplitDto
import app.canopy.core.network.NewTransactionDto
import javax.inject.Inject

/** [BudgetEngine] over the bridge's v1 HTTP contract. */
class BridgeBudgetEngine @Inject constructor(private val api: BridgeApi) : BudgetEngine {

    override suspend fun capabilities() = api.capabilities().toModel()
    override suspend fun budgets() = api.budgets().map { it.toModel() }
    override suspend fun accounts(budget: BudgetId) = api.accounts(budget.raw).map { it.toModel() }
    override suspend fun categoryGroups(budget: BudgetId) = api.categoryGroups(budget.raw).map { it.toModel() }
    override suspend fun payees(budget: BudgetId) = api.payees(budget.raw).map { it.toModel() }

    override suspend fun transactions(budget: BudgetId, query: TransactionQuery, cursor: String?) =
        api.transactions(budget.raw, query.accountId?.raw, query.categoryId?.raw, query.since, query.until, query.limit, cursor)
            .let { page -> Page(page.items.map { it.toModel() }, page.nextCursor) }

    override suspend fun createTransaction(budget: BudgetId, transaction: NewTransaction) = api.createTransaction(
        budget.raw,
        NewTransactionDto(
            id = transaction.id.raw,
            accountId = transaction.accountId.raw,
            date = transaction.date,
            amount = transaction.amount.minor,
            payeeId = transaction.payeeId?.raw,
            payeeName = transaction.payeeName,
            categoryId = transaction.categoryId?.raw,
            notes = transaction.notes,
            cleared = transaction.cleared,
            subtransactions = transaction.splits.takeIf { it.isNotEmpty() }?.map { NewSplitDto(it.amount.minor, it.categoryId?.raw, it.notes) },
        ),
    ).toModel()

    override suspend fun budgetMonths(budget: BudgetId) = api.months(budget.raw).map(::YearMonth)
    override suspend fun budgetMonth(budget: BudgetId, month: YearMonth) = api.month(budget.raw, month.raw).toModel()

    override suspend fun setBudgeted(budget: BudgetId, month: YearMonth, category: CategoryId, amount: Money): BudgetMonth =
        api.updateCategoryBudget(budget.raw, month.raw, category.raw, CategoryBudgetPatchDto(budgeted = amount.minor)).toModel()

    override suspend fun setCarryover(budget: BudgetId, month: YearMonth, category: CategoryId, enabled: Boolean): BudgetMonth =
        api.updateCategoryBudget(budget.raw, month.raw, category.raw, CategoryBudgetPatchDto(carryover = enabled)).toModel()

    override suspend fun moveMoney(budget: BudgetId, month: YearMonth, from: BudgetPot, to: BudgetPot, amount: Money, idempotencyKey: String) =
        api.moveMoney(budget.raw, month.raw, MoneyTransferDto(from.wire(), to.wire(), amount.minor), idempotencyKey).toModel()

    override suspend fun holdForNextMonth(budget: BudgetId, month: YearMonth, amount: Money?) =
        (if (amount == null) api.resetHold(budget.raw, month.raw) else api.hold(budget.raw, month.raw, amount.minor)).toModel()

    private fun BudgetPot.wire() = when (this) {
        BudgetPot.ToBudget -> "to-budget"
        is BudgetPot.Envelope -> id.raw
    }
}
