package app.canopy.core.data

import app.canopy.core.domain.BudgetChanges
import app.canopy.core.domain.BudgetEngine
import app.canopy.core.engine.bridge.BridgeBudgetEngine
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

    override suspend fun createTransaction(budget: app.canopy.core.model.BudgetId, transaction: app.canopy.core.model.NewTransaction) =
        write { inner.createTransaction(budget, transaction) }
    override suspend fun updateTransaction(budget: app.canopy.core.model.BudgetId, id: app.canopy.core.model.TransactionId, patch: app.canopy.core.model.TransactionPatch) =
        write { inner.updateTransaction(budget, id, patch) }
    override suspend fun deleteTransaction(budget: app.canopy.core.model.BudgetId, id: app.canopy.core.model.TransactionId) =
        write { inner.deleteTransaction(budget, id) }
    override suspend fun setBudgeted(budget: app.canopy.core.model.BudgetId, month: app.canopy.core.model.YearMonth, category: app.canopy.core.model.CategoryId, amount: app.canopy.core.model.Money) =
        write { inner.setBudgeted(budget, month, category, amount) }
    override suspend fun setCarryover(budget: app.canopy.core.model.BudgetId, month: app.canopy.core.model.YearMonth, category: app.canopy.core.model.CategoryId, enabled: Boolean) =
        write { inner.setCarryover(budget, month, category, enabled) }
    override suspend fun moveMoney(
        budget: app.canopy.core.model.BudgetId,
        month: app.canopy.core.model.YearMonth,
        from: app.canopy.core.model.BudgetPot,
        to: app.canopy.core.model.BudgetPot,
        amount: app.canopy.core.model.Money,
        idempotencyKey: String,
    ) = write { inner.moveMoney(budget, month, from, to, amount, idempotencyKey) }
    override suspend fun holdForNextMonth(budget: app.canopy.core.model.BudgetId, month: app.canopy.core.model.YearMonth, amount: app.canopy.core.model.Money?) =
        write { inner.holdForNextMonth(budget, month, amount) }
    override suspend fun createAccount(budget: app.canopy.core.model.BudgetId, name: String, offBudget: Boolean, initialBalance: app.canopy.core.model.Money) =
        write { inner.createAccount(budget, name, offBudget, initialBalance) }
    override suspend fun renameAccount(budget: app.canopy.core.model.BudgetId, id: app.canopy.core.model.AccountId, name: String) =
        write { inner.renameAccount(budget, id, name) }
    override suspend fun closeAccount(budget: app.canopy.core.model.BudgetId, id: app.canopy.core.model.AccountId, moveBalanceTo: app.canopy.core.model.AccountId?, balanceCategory: app.canopy.core.model.CategoryId?) =
        write { inner.closeAccount(budget, id, moveBalanceTo, balanceCategory) }
    override suspend fun reopenAccount(budget: app.canopy.core.model.BudgetId, id: app.canopy.core.model.AccountId) =
        write { inner.reopenAccount(budget, id) }
    override suspend fun createCategory(budget: app.canopy.core.model.BudgetId, name: String, group: app.canopy.core.model.CategoryGroupId) =
        write { inner.createCategory(budget, name, group) }
    override suspend fun updateCategory(budget: app.canopy.core.model.BudgetId, id: app.canopy.core.model.CategoryId, name: String?, hidden: Boolean?, group: app.canopy.core.model.CategoryGroupId?) =
        write { inner.updateCategory(budget, id, name, hidden, group) }
    override suspend fun deleteCategory(budget: app.canopy.core.model.BudgetId, id: app.canopy.core.model.CategoryId, moveTo: app.canopy.core.model.CategoryId?) =
        write { inner.deleteCategory(budget, id, moveTo) }
    override suspend fun createCategoryGroup(budget: app.canopy.core.model.BudgetId, name: String) =
        write { inner.createCategoryGroup(budget, name) }
    override suspend fun updateCategoryGroup(budget: app.canopy.core.model.BudgetId, id: app.canopy.core.model.CategoryGroupId, name: String?, hidden: Boolean?) =
        write { inner.updateCategoryGroup(budget, id, name, hidden) }
    override suspend fun deleteCategoryGroup(budget: app.canopy.core.model.BudgetId, id: app.canopy.core.model.CategoryGroupId, moveTo: app.canopy.core.model.CategoryId?) =
        write { inner.deleteCategoryGroup(budget, id, moveTo) }
}
