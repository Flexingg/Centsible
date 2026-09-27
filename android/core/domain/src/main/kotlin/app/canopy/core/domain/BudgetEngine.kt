package app.canopy.core.domain

import app.canopy.core.model.Account
import app.canopy.core.model.AccountId
import app.canopy.core.model.Budget
import app.canopy.core.model.BudgetId
import app.canopy.core.model.BudgetMonth
import app.canopy.core.model.BudgetPot
import app.canopy.core.model.Capabilities
import app.canopy.core.model.CategoryGroup
import app.canopy.core.model.CategoryId
import app.canopy.core.model.Money
import app.canopy.core.model.NewTransaction
import app.canopy.core.model.Page
import app.canopy.core.model.Payee
import app.canopy.core.model.Transaction
import app.canopy.core.model.YearMonth

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

    suspend fun accounts(budget: BudgetId): List<Account>
    suspend fun categoryGroups(budget: BudgetId): List<CategoryGroup>
    suspend fun payees(budget: BudgetId): List<Payee>

    suspend fun transactions(budget: BudgetId, query: TransactionQuery = TransactionQuery(), cursor: String? = null): Page<Transaction>
    suspend fun createTransaction(budget: BudgetId, transaction: NewTransaction): Transaction

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
    val since: String? = null,
    val until: String? = null,
    val limit: Int = 100,
)
