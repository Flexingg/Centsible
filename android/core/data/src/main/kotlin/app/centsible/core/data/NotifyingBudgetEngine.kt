package app.centsible.core.data

import app.centsible.core.domain.BudgetChanges
import app.centsible.core.domain.BudgetEngine
import app.centsible.core.engine.bridge.BridgeBudgetEngine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Wraps the real engine and announces successful writes, so every screen showing the
 * same budget reloads. Reads pass straight through (delegation).
 */
@Singleton
class NotifyingBudgetEngine @Inject constructor(
    private val inner: BridgeBudgetEngine,
) : BudgetEngine by inner, BudgetChanges {
    private val events = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val changes: SharedFlow<Unit> = events

    private suspend fun <T> write(block: suspend () -> T): T = block().also { events.emit(Unit) }

    /** For the other gateways' writes (planning, import, reconcile). */
    fun notifyChanged() {
        events.tryEmit(Unit)
    }

    override suspend fun createTransaction(budget: app.centsible.core.model.BudgetId, transaction: app.centsible.core.model.NewTransaction) =
        write { inner.createTransaction(budget, transaction) }
    override suspend fun updateTransaction(budget: app.centsible.core.model.BudgetId, id: app.centsible.core.model.TransactionId, patch: app.centsible.core.model.TransactionPatch) =
        write { inner.updateTransaction(budget, id, patch) }
    override suspend fun deleteTransaction(budget: app.centsible.core.model.BudgetId, id: app.centsible.core.model.TransactionId) =
        write { inner.deleteTransaction(budget, id) }
    override suspend fun setBudgeted(budget: app.centsible.core.model.BudgetId, month: app.centsible.core.model.YearMonth, category: app.centsible.core.model.CategoryId, amount: app.centsible.core.model.Money) =
        write { inner.setBudgeted(budget, month, category, amount) }
    override suspend fun setCarryover(budget: app.centsible.core.model.BudgetId, month: app.centsible.core.model.YearMonth, category: app.centsible.core.model.CategoryId, enabled: Boolean) =
        write { inner.setCarryover(budget, month, category, enabled) }
    override suspend fun moveMoney(
        budget: app.centsible.core.model.BudgetId,
        month: app.centsible.core.model.YearMonth,
        from: app.centsible.core.model.BudgetPot,
        to: app.centsible.core.model.BudgetPot,
        amount: app.centsible.core.model.Money,
        idempotencyKey: String,
    ) = write { inner.moveMoney(budget, month, from, to, amount, idempotencyKey) }
    override suspend fun holdForNextMonth(budget: app.centsible.core.model.BudgetId, month: app.centsible.core.model.YearMonth, amount: app.centsible.core.model.Money?) =
        write { inner.holdForNextMonth(budget, month, amount) }
    override suspend fun createAccount(budget: app.centsible.core.model.BudgetId, name: String, offBudget: Boolean, initialBalance: app.centsible.core.model.Money) =
        write { inner.createAccount(budget, name, offBudget, initialBalance) }
    override suspend fun renameAccount(budget: app.centsible.core.model.BudgetId, id: app.centsible.core.model.AccountId, name: String) =
        write { inner.renameAccount(budget, id, name) }
    override suspend fun closeAccount(budget: app.centsible.core.model.BudgetId, id: app.centsible.core.model.AccountId, moveBalanceTo: app.centsible.core.model.AccountId?, balanceCategory: app.centsible.core.model.CategoryId?) =
        write { inner.closeAccount(budget, id, moveBalanceTo, balanceCategory) }
    override suspend fun reopenAccount(budget: app.centsible.core.model.BudgetId, id: app.centsible.core.model.AccountId) =
        write { inner.reopenAccount(budget, id) }
    override suspend fun createCategory(budget: app.centsible.core.model.BudgetId, name: String, group: app.centsible.core.model.CategoryGroupId) =
        write { inner.createCategory(budget, name, group) }
    override suspend fun updateCategory(budget: app.centsible.core.model.BudgetId, id: app.centsible.core.model.CategoryId, name: String?, hidden: Boolean?, group: app.centsible.core.model.CategoryGroupId?) =
        write { inner.updateCategory(budget, id, name, hidden, group) }
    override suspend fun deleteCategory(budget: app.centsible.core.model.BudgetId, id: app.centsible.core.model.CategoryId, moveTo: app.centsible.core.model.CategoryId?) =
        write { inner.deleteCategory(budget, id, moveTo) }
    override suspend fun createCategoryGroup(budget: app.centsible.core.model.BudgetId, name: String) =
        write { inner.createCategoryGroup(budget, name) }
    override suspend fun updateCategoryGroup(budget: app.centsible.core.model.BudgetId, id: app.centsible.core.model.CategoryGroupId, name: String?, hidden: Boolean?) =
        write { inner.updateCategoryGroup(budget, id, name, hidden) }
    override suspend fun deleteCategoryGroup(budget: app.centsible.core.model.BudgetId, id: app.centsible.core.model.CategoryGroupId, moveTo: app.centsible.core.model.CategoryId?) =
        write { inner.deleteCategoryGroup(budget, id, moveTo) }
}
