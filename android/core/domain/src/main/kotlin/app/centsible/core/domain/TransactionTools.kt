package app.centsible.core.domain

import app.centsible.core.model.BatchChange
import app.centsible.core.model.BatchResult
import app.centsible.core.model.BudgetId
import app.centsible.core.model.ReviewInbox
import app.centsible.core.model.TransactionId

/** The review inbox and bulk edits. */
interface TransactionTools {
    suspend fun inbox(budget: BudgetId, limit: Int = 50): ReviewInbox
    /** Returns how many are left. */
    suspend fun markReviewed(budget: BudgetId, ids: List<TransactionId>): Int
    suspend fun reviewAll(budget: BudgetId): Int
    suspend fun batch(budget: BudgetId, ids: List<TransactionId>, change: BatchChange): BatchResult
}
