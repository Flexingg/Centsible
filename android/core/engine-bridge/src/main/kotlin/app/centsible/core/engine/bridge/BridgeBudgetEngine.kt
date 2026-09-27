package app.centsible.core.engine.bridge

import app.centsible.core.domain.BudgetEngine
import app.centsible.core.domain.TransactionQuery
import app.centsible.core.model.BudgetId
import app.centsible.core.model.BudgetMonth
import app.centsible.core.model.BudgetPot
import app.centsible.core.model.CategoryId
import app.centsible.core.model.Money
import app.centsible.core.model.NewTransaction
import app.centsible.core.model.Page
import app.centsible.core.model.YearMonth
import app.centsible.core.network.BridgeApi
import app.centsible.core.network.CategoryBudgetPatchDto
import app.centsible.core.network.MoneyTransferDto
import app.centsible.core.network.NewSplitDto
import app.centsible.core.network.NewTransactionDto
import app.centsible.core.network.AccountPatchDto
import app.centsible.core.network.CategoryPatchDto
import app.centsible.core.network.CloseAccountDto
import app.centsible.core.network.GroupPatchDto
import app.centsible.core.network.NewAccountDto
import app.centsible.core.network.NewCategoryDto
import app.centsible.core.network.NewGroupDto
import app.centsible.core.model.AccountId
import app.centsible.core.model.CategoryGroupId
import app.centsible.core.model.Transaction
import app.centsible.core.model.TransactionId
import app.centsible.core.model.TransactionPatch
import javax.inject.Inject

/** [BudgetEngine] over the bridge's v1 HTTP contract. */
class BridgeBudgetEngine @Inject constructor(private val api: BridgeApi) : BudgetEngine {

    override suspend fun capabilities() = api.capabilities().toModel()
    override suspend fun budgets() = api.budgets().map { it.toModel() }
    override suspend fun accounts(budget: BudgetId) = api.accounts(budget.raw).map { it.toModel() }
    override suspend fun categoryGroups(budget: BudgetId) = api.categoryGroups(budget.raw).map { it.toModel() }
    override suspend fun payees(budget: BudgetId) = api.payees(budget.raw).map { it.toModel() }

    override suspend fun transactions(budget: BudgetId, query: TransactionQuery, cursor: String?) =
        api.transactions(budget.raw, query.accountId?.raw, query.categoryId?.raw, query.since, query.until, query.limit, cursor, query.search, query.uncategorized)
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

    override suspend fun transaction(budget: BudgetId, id: TransactionId) = api.transaction(budget.raw, id.raw).toModel()

    override suspend fun updateTransaction(budget: BudgetId, id: TransactionId, patch: TransactionPatch): Transaction =
        if (patch.isEmpty) transaction(budget, id) else api.updateTransaction(budget.raw, id.raw, patch.toJson()).toModel()

    override suspend fun deleteTransaction(budget: BudgetId, id: TransactionId) = api.deleteTransaction(budget.raw, id.raw)

    override suspend fun preferences(budget: BudgetId) = api.preferences(budget.raw).toModel()

    override suspend fun createAccount(budget: BudgetId, name: String, offBudget: Boolean, initialBalance: Money) =
        api.createAccount(budget.raw, NewAccountDto(name, offBudget, initialBalance.minor)).toModel()

    override suspend fun renameAccount(budget: BudgetId, id: AccountId, name: String) = api.updateAccount(budget.raw, id.raw, AccountPatchDto(name)).toModel()

    override suspend fun closeAccount(budget: BudgetId, id: AccountId, moveBalanceTo: AccountId?, balanceCategory: CategoryId?) =
        api.closeAccount(budget.raw, id.raw, CloseAccountDto(moveBalanceTo?.raw, balanceCategory?.raw))?.toModel()

    override suspend fun reopenAccount(budget: BudgetId, id: AccountId) = api.reopenAccount(budget.raw, id.raw).toModel()

    override suspend fun createCategory(budget: BudgetId, name: String, group: CategoryGroupId) = api.createCategory(budget.raw, NewCategoryDto(name, group.raw)).toModel()

    override suspend fun updateCategory(budget: BudgetId, id: CategoryId, name: String?, hidden: Boolean?, group: CategoryGroupId?) =
        api.updateCategory(budget.raw, id.raw, CategoryPatchDto(name, hidden, group?.raw)).toModel()

    override suspend fun deleteCategory(budget: BudgetId, id: CategoryId, moveTo: CategoryId?) = api.deleteCategory(budget.raw, id.raw, moveTo?.raw)

    override suspend fun createCategoryGroup(budget: BudgetId, name: String) = api.createGroup(budget.raw, NewGroupDto(name)).toModel()

    override suspend fun updateCategoryGroup(budget: BudgetId, id: CategoryGroupId, name: String?, hidden: Boolean?) =
        api.updateGroup(budget.raw, id.raw, GroupPatchDto(name, hidden)).toModel()

    override suspend fun deleteCategoryGroup(budget: BudgetId, id: CategoryGroupId, moveTo: CategoryId?) = api.deleteGroup(budget.raw, id.raw, moveTo?.raw)

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
