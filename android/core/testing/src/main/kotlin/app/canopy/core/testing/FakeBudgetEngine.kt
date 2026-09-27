package app.canopy.core.testing

import app.canopy.core.domain.BudgetEngine
import app.canopy.core.domain.TransactionQuery
import app.canopy.core.model.Account
import app.canopy.core.model.Budget
import app.canopy.core.model.BudgetCategory
import app.canopy.core.model.BudgetId
import app.canopy.core.model.BudgetMonth
import app.canopy.core.model.BudgetPot
import app.canopy.core.model.BridgeStatus
import app.canopy.core.model.Capabilities
import app.canopy.core.model.Category
import app.canopy.core.model.CategoryGroup
import app.canopy.core.model.CategoryId
import app.canopy.core.model.Feature
import app.canopy.core.model.Money
import app.canopy.core.model.NewTransaction
import app.canopy.core.model.Page
import app.canopy.core.model.Payee
import app.canopy.core.model.Transaction
import app.canopy.core.model.YearMonth

/**
 * In-memory engine for tests and previews. Its budget arithmetic is deliberately
 * simplistic; real numbers always come from Actual through the bridge.
 */
class FakeBudgetEngine(
    var capabilities: Capabilities = Capabilities(
        contract = "1.0.0",
        bridgeVersion = "0.1.0",
        actualServerVersion = "26.9.0",
        actualApiVersion = "26.9.0",
        compatibility = "ok",
        status = BridgeStatus.Ok,
        features = Feature.entries.associate { it.key to (it != Feature.BudgetTracking) },
    ),
) : BudgetEngine {
    var month: BudgetMonth = SampleHousehold.budgetMonth
    val transactions = SampleHousehold.transactions.toMutableList()
    val moves = mutableListOf<Triple<BudgetPot, BudgetPot, Money>>()
    var failWith: Throwable? = null

    private fun check() = failWith?.let { throw it }

    override suspend fun capabilities() = capabilities.also { check() }
    override suspend fun budgets(): List<Budget> = listOf(SampleHousehold.budget).also { check() }
    override suspend fun accounts(budget: BudgetId): List<Account> = SampleHousehold.accounts.also { check() }

    override suspend fun categoryGroups(budget: BudgetId): List<CategoryGroup> = month.groups.map { g ->
        CategoryGroup(g.id, g.name, g.isIncome, g.hidden, g.categories.map { Category(it.id, it.name, g.id, g.isIncome, it.hidden) })
    }

    override suspend fun payees(budget: BudgetId): List<Payee> = emptyList()

    override suspend fun transactions(budget: BudgetId, query: TransactionQuery, cursor: String?): Page<Transaction> {
        check()
        val filtered = transactions.filter { t ->
            (query.accountId == null || t.accountId == query.accountId) && (query.categoryId == null || t.categoryId == query.categoryId)
        }
        return Page(filtered.take(query.limit), null)
    }

    override suspend fun createTransaction(budget: BudgetId, transaction: NewTransaction): Transaction {
        check()
        transactions.firstOrNull { it.id == transaction.id }?.let { return it }
        val t = Transaction(
            transaction.id, transaction.accountId, transaction.date, transaction.amount, transaction.payeeId,
            transaction.payeeName, transaction.categoryId, transaction.notes, transaction.cleared ?: false,
            reconciled = false, isTransfer = false, isParent = false, subtransactions = emptyList(),
        )
        transactions.add(0, t)
        return t
    }

    override suspend fun budgetMonths(budget: BudgetId) = listOf(month.month.plus(-1), month.month, month.month.plus(1))
    override suspend fun budgetMonth(budget: BudgetId, month: YearMonth) = this.month.also { check() }.copy(month = month)

    override suspend fun setBudgeted(budget: BudgetId, month: YearMonth, category: CategoryId, amount: Money): BudgetMonth {
        check()
        var delta = Money.Zero
        this.month = updateCategory(category) { c ->
            delta = amount - c.budgeted
            c.copy(budgeted = amount, balance = c.balance + (amount - c.budgeted))
        }
        this.month = this.month.copy(toBudget = this.month.toBudget - delta, totalBudgeted = this.month.totalBudgeted + delta)
        return this.month
    }

    override suspend fun setCarryover(budget: BudgetId, month: YearMonth, category: CategoryId, enabled: Boolean): BudgetMonth {
        this.month = updateCategory(category) { it.copy(carryover = enabled) }
        return this.month
    }

    override suspend fun moveMoney(budget: BudgetId, month: YearMonth, from: BudgetPot, to: BudgetPot, amount: Money, idempotencyKey: String): BudgetMonth {
        check()
        moves += Triple(from, to, amount)
        if (from is BudgetPot.Envelope) setBudgeted(budget, month, from.id, category(from.id).budgeted - amount)
        if (to is BudgetPot.Envelope) setBudgeted(budget, month, to.id, category(to.id).budgeted + amount)
        return this.month
    }

    override suspend fun holdForNextMonth(budget: BudgetId, month: YearMonth, amount: Money?): BudgetMonth {
        this.month = this.month.copy(forNextMonth = amount ?: Money.Zero)
        return this.month
    }

    fun category(id: CategoryId): BudgetCategory = month.groups.flatMap { it.categories }.first { it.id == id }

    private fun updateCategory(id: CategoryId, f: (BudgetCategory) -> BudgetCategory) = month.copy(
        groups = month.groups.map { g -> g.copy(categories = g.categories.map { if (it.id == id) f(it) else it }) },
    )
}
