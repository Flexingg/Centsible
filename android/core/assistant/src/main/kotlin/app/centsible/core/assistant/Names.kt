package app.centsible.core.assistant

import app.centsible.core.domain.BudgetEngine
import app.centsible.core.domain.PlanAheadGateway
import app.centsible.core.model.Account
import app.centsible.core.model.AccountId
import app.centsible.core.model.BudgetId
import app.centsible.core.model.CategoryGroup
import app.centsible.core.model.CategoryGroupId
import app.centsible.core.model.CategoryId
import app.centsible.core.model.CategoryTree
import app.centsible.core.model.Payee
import app.centsible.core.model.PayeeId
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The budget's accounts, categories and payees, read once and reused to put names next to
 * ids (in tool results and approval cards) and to check ids the assistant passes. Reload
 * after a change with [invalidate].
 */
class Names(private val engine: BudgetEngine, private val planAhead: PlanAheadGateway, private val budget: BudgetId) {
    class Snapshot(
        val accounts: List<Account>,
        val groups: List<CategoryGroup>,
        val payees: List<Payee>,
        val offBooks: Set<CategoryId>,
    ) {
        val tree = CategoryTree.of(groups)
        val accountById = accounts.associateBy { it.id }
        val payeeById = payees.associateBy { it.id }
        val groupById = groups.associateBy { it.id }
        val groupOfCategory = groups.flatMap { g -> g.categories.map { it.id to g } }.toMap()
    }

    private val lock = Mutex()
    private var cached: Snapshot? = null

    suspend fun get(): Snapshot = lock.withLock {
        cached ?: coroutineScope {
            val accounts = async { engine.accounts(budget) }
            val groups = async { engine.categoryGroups(budget) }
            val payees = async { engine.payees(budget) }
            val off = async { runCatching { planAhead.offBooks(budget) }.getOrDefault(emptySet()) }
            Snapshot(accounts.await(), groups.await(), payees.await(), off.await())
        }.also { cached = it }
    }

    fun invalidate() {
        cached = null
    }

    suspend fun account(id: AccountId?): String? = id?.let { get().accountById[it]?.name }
    suspend fun payee(id: PayeeId?): String? = id?.let { get().payeeById[it]?.name }
    suspend fun group(id: CategoryGroupId?): String? = id?.let { get().groupById[it]?.name }

    /** "Food › Groceries", or the id itself when it's not a category we know. */
    suspend fun category(id: CategoryId?): String? = id?.let { c -> get().tree.pathLabel(c).ifEmpty { null } }

    suspend fun requireAccount(id: AccountId): Account =
        get().accountById[id] ?: throw ToolInputException("No account with id ${id.raw}. Call list_accounts for ids.")

    suspend fun requireCategory(id: CategoryId): String =
        category(id) ?: throw ToolInputException("No category with id ${id.raw}. Call get_categories for ids.")

    suspend fun requireGroup(id: CategoryGroupId): CategoryGroup =
        get().groupById[id] ?: throw ToolInputException("No category group with id ${id.raw}. Call get_categories for ids.")

    suspend fun requirePayee(id: PayeeId): String =
        payee(id) ?: throw ToolInputException("No merchant (payee) with id ${id.raw}. Call list_merchants for ids.")
}
