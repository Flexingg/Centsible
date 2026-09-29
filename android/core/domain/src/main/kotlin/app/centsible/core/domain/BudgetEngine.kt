package app.centsible.core.domain

import app.centsible.core.model.Account
import app.centsible.core.model.AccountId
import app.centsible.core.model.Budget
import app.centsible.core.model.BudgetId
import app.centsible.core.model.BudgetMonth
import app.centsible.core.model.BudgetPot
import app.centsible.core.model.Capabilities
import app.centsible.core.model.CategoryGroup
import app.centsible.core.model.CategoryId
import app.centsible.core.model.Money
import app.centsible.core.model.NewTransaction
import app.centsible.core.model.Page
import app.centsible.core.model.Payee
import app.centsible.core.model.Transaction
import app.centsible.core.model.YearMonth
import app.centsible.core.model.Category
import app.centsible.core.model.CategoryGroupId
import app.centsible.core.model.Preferences
import app.centsible.core.model.TransactionId
import app.centsible.core.model.TransactionPatch

/**
 * The seam between the app and Actual. Today it is implemented over the bridge's HTTP
 * contract; later an on-device loot-core engine can implement it for full offline use
 * without any screen changing.
 *
 * Every budget-changing call returns the recomputed month from Actual, so the UI never
 * does budget math itself.
 */
interface BudgetEngine {
    suspend fun capabilities(): Capabilities
    suspend fun budgets(): List<Budget>
    /** Owners only: an empty budget, e.g. the household's first. */
    suspend fun createBudget(name: String): Budget

    suspend fun accounts(budget: BudgetId): List<Account>
    suspend fun categoryGroups(budget: BudgetId): List<CategoryGroup>
    suspend fun payees(budget: BudgetId): List<Payee>

    suspend fun transactions(budget: BudgetId, query: TransactionQuery = TransactionQuery(), cursor: String? = null): Page<Transaction>
    suspend fun createTransaction(budget: BudgetId, transaction: NewTransaction): Transaction
    suspend fun transaction(budget: BudgetId, id: TransactionId): Transaction
    suspend fun updateTransaction(budget: BudgetId, id: TransactionId, patch: TransactionPatch): Transaction
    suspend fun deleteTransaction(budget: BudgetId, id: TransactionId)

    suspend fun preferences(budget: BudgetId): Preferences

    suspend fun createAccount(budget: BudgetId, name: String, offBudget: Boolean, initialBalance: Money): Account
    suspend fun renameAccount(budget: BudgetId, id: AccountId, name: String): Account
    /** Returns null when Actual deleted the account because it had no transactions. */
    suspend fun closeAccount(budget: BudgetId, id: AccountId, moveBalanceTo: AccountId?, balanceCategory: CategoryId?): Account?
    suspend fun reopenAccount(budget: BudgetId, id: AccountId): Account

    suspend fun createCategory(budget: BudgetId, name: String, group: CategoryGroupId): Category
    suspend fun updateCategory(budget: BudgetId, id: CategoryId, name: String? = null, hidden: Boolean? = null, group: CategoryGroupId? = null): Category
    suspend fun deleteCategory(budget: BudgetId, id: CategoryId, moveTo: CategoryId?)
    suspend fun createCategoryGroup(budget: BudgetId, name: String): CategoryGroup
    suspend fun updateCategoryGroup(budget: BudgetId, id: CategoryGroupId, name: String? = null, hidden: Boolean? = null): CategoryGroup
    suspend fun deleteCategoryGroup(budget: BudgetId, id: CategoryGroupId, moveTo: CategoryId?)

    suspend fun budgetMonths(budget: BudgetId): List<YearMonth>
    suspend fun budgetMonth(budget: BudgetId, month: YearMonth): BudgetMonth
    suspend fun setBudgeted(budget: BudgetId, month: YearMonth, category: CategoryId, amount: Money): BudgetMonth
    suspend fun setCarryover(budget: BudgetId, month: YearMonth, category: CategoryId, enabled: Boolean): BudgetMonth
    suspend fun moveMoney(budget: BudgetId, month: YearMonth, from: BudgetPot, to: BudgetPot, amount: Money, idempotencyKey: String): BudgetMonth
    suspend fun holdForNextMonth(budget: BudgetId, month: YearMonth, amount: Money?): BudgetMonth
}

data class TransactionQuery(
    val accountId: AccountId? = null,
    val categoryId: CategoryId? = null,
    val payeeId: app.centsible.core.model.PayeeId? = null,
    val groupId: app.centsible.core.model.CategoryGroupId? = null,
    val since: String? = null,
    val until: String? = null,
    /** Matches payee, notes or category name. */
    val search: String? = null,
    /** Only on-budget transactions still waiting for a category. */
    val uncategorized: Boolean = false,
    val limit: Int = 100,
)
