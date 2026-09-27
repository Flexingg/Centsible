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
import app.canopy.core.domain.BridgeException
import app.canopy.core.model.AccountId
import app.canopy.core.model.BudgetType
import app.canopy.core.model.CategoryGroupId
import app.canopy.core.model.Preferences
import app.canopy.core.model.TransactionId
import app.canopy.core.model.TransactionPatch
import app.canopy.core.model.Update

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
    override suspend fun accounts(budget: BudgetId): List<Account> = accounts.toList().also { check() }

    override suspend fun categoryGroups(budget: BudgetId): List<CategoryGroup> = month.groups.map { g ->
        CategoryGroup(g.id, g.name, g.isIncome, g.hidden, g.categories.map { Category(it.id, it.name, g.id, g.isIncome, it.hidden) })
    }

    override suspend fun payees(budget: BudgetId): List<Payee> = emptyList()

    override suspend fun transactions(budget: BudgetId, query: TransactionQuery, cursor: String?): Page<Transaction> {
        check()
        val filtered = transactions.filter { t ->
            (query.accountId == null || t.accountId == query.accountId) &&
                (query.categoryId == null || t.categoryId == query.categoryId) &&
                (!query.uncategorized || (t.categoryId == null && !t.isTransfer && !t.isParent)) &&
                (query.search.isNullOrBlank() || listOfNotNull(t.payeeName, t.notes).any { it.contains(query.search!!, ignoreCase = true) })
        }
        return Page(filtered.take(query.limit), null)
    }

    override suspend fun createTransaction(budget: BudgetId, transaction: NewTransaction): Transaction {
        check()
        transactions.firstOrNull { it.id == transaction.id }?.let { return it }
        val t = Transaction(
            transaction.id, transaction.accountId, transaction.date, transaction.amount, transaction.payeeId,
            transaction.payeeName, transaction.categoryId, transaction.notes, transaction.cleared ?: false,
            reconciled = false, transferId = null, isParent = false, subtransactions = emptyList(),
        )
        transactions.add(0, t)
        return t
    }

    var accounts = SampleHousehold.accounts.toMutableList()
    val deleted = mutableListOf<TransactionId>()
    val patches = mutableListOf<Pair<TransactionId, TransactionPatch>>()
    val extraCategories = mutableListOf<Category>()

    override suspend fun transaction(budget: BudgetId, id: TransactionId): Transaction {
        check()
        return transactions.firstOrNull { it.id == id } ?: throw BridgeException.NotFound("Transaction ${id.raw} not found")
    }

    override suspend fun updateTransaction(budget: BudgetId, id: TransactionId, patch: TransactionPatch): Transaction {
        check()
        patches += id to patch
        val i = transactions.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: throw BridgeException.NotFound("Transaction ${id.raw} not found")
        var t = transactions[i]
        fun <T> Update<T>.or(v: T): T = if (this is Update.Set) value else v
        t = t.copy(
            accountId = patch.accountId.or(t.accountId),
            date = patch.date.or(t.date),
            amount = patch.amount.or(t.amount),
            payeeName = patch.payeeName.or(t.payeeName ?: ""),
            categoryId = patch.categoryId.or(t.categoryId),
            notes = patch.notes.or(t.notes),
            cleared = patch.cleared.or(t.cleared),
        )
        val splits = patch.splits
        if (splits is Update.Set) {
            t = t.copy(
                isParent = splits.value.isNotEmpty(),
                categoryId = if (splits.value.isNotEmpty()) null else t.categoryId,
                subtransactions = splits.value.mapIndexed { n, s ->
                    t.copy(id = s.id ?: TransactionId("${id.raw}-s$n"), amount = s.amount, categoryId = s.categoryId, notes = s.notes, isParent = false, subtransactions = emptyList())
                },
            )
        }
        transactions[i] = t
        return t
    }

    override suspend fun deleteTransaction(budget: BudgetId, id: TransactionId) {
        check()
        deleted += id
        transactions.removeAll { it.id == id }
    }

    override suspend fun preferences(budget: BudgetId) =
        Preferences(BudgetType.Envelope, "USD", "comma-dot", "MM/dd/yyyy", 0, false)

    override suspend fun createAccount(budget: BudgetId, name: String, offBudget: Boolean, initialBalance: Money): Account {
        check()
        return Account(AccountId("acc-${accounts.size}"), name, offBudget, false, initialBalance).also { accounts += it }
    }

    override suspend fun renameAccount(budget: BudgetId, id: AccountId, name: String) = replaceAccount(id) { it.copy(name = name) }

    override suspend fun closeAccount(budget: BudgetId, id: AccountId, moveBalanceTo: AccountId?, balanceCategory: CategoryId?): Account? {
        check()
        if (!accounts.first { it.id == id }.balance.isZero && moveBalanceTo == null) throw BridgeException.Validation("Choose an account to move the balance to")
        return replaceAccount(id) { it.copy(closed = true, balance = Money.Zero) }
    }

    override suspend fun reopenAccount(budget: BudgetId, id: AccountId) = replaceAccount(id) { it.copy(closed = false) }

    override suspend fun createCategory(budget: BudgetId, name: String, group: CategoryGroupId) =
        Category(CategoryId("cat-${extraCategories.size}"), name, group, false, false).also { extraCategories += it }

    override suspend fun updateCategory(budget: BudgetId, id: CategoryId, name: String?, hidden: Boolean?, group: CategoryGroupId?): Category {
        month = updateCategory(id) { it.copy(name = name ?: it.name, hidden = hidden ?: it.hidden) }
        val c = category(id)
        return Category(c.id, c.name, group ?: month.groups.first { g -> g.categories.any { it.id == id } }.id, false, c.hidden)
    }

    override suspend fun deleteCategory(budget: BudgetId, id: CategoryId, moveTo: CategoryId?) {
        month = month.copy(groups = month.groups.map { g -> g.copy(categories = g.categories.filter { it.id != id }) })
    }

    override suspend fun createCategoryGroup(budget: BudgetId, name: String) =
        CategoryGroup(CategoryGroupId("grp-$name"), name, false, false, emptyList())

    override suspend fun updateCategoryGroup(budget: BudgetId, id: CategoryGroupId, name: String?, hidden: Boolean?): CategoryGroup {
        month = month.copy(groups = month.groups.map { if (it.id == id) it.copy(name = name ?: it.name, hidden = hidden ?: it.hidden) else it })
        return categoryGroups(SampleHousehold.budget.id).first { it.id == id }
    }

    override suspend fun deleteCategoryGroup(budget: BudgetId, id: CategoryGroupId, moveTo: CategoryId?) {
        month = month.copy(groups = month.groups.filter { it.id != id })
    }

    private fun replaceAccount(id: AccountId, f: (Account) -> Account): Account {
        val i = accounts.indexOfFirst { it.id == id }
        return f(accounts[i]).also { accounts[i] = it }
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

class FakeBudgetChanges : app.canopy.core.domain.BudgetChanges {
    val flow = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val changes: kotlinx.coroutines.flow.Flow<Unit> = flow
}
