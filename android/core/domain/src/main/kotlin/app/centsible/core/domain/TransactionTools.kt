package app.centsible.core.domain

import app.centsible.core.model.BatchChange
import app.centsible.core.model.BatchResult
import app.centsible.core.model.BudgetId
import app.centsible.core.model.ReviewInbox
import app.centsible.core.model.TransactionId
import app.centsible.core.model.TransferMatches

/** The review inbox and bulk edits. */
interface TransactionTools {
    suspend fun inbox(budget: BudgetId, limit: Int = 50): ReviewInbox
    /** Returns how many are left. */
    suspend fun markReviewed(budget: BudgetId, ids: List<TransactionId>): Int
    suspend fun reviewAll(budget: BudgetId): Int
    suspend fun batch(budget: BudgetId, ids: List<TransactionId>, change: BatchChange): BatchResult

    /** Card payments and other moves seen from both accounts, not yet linked. */
    suspend fun transferMatches(budget: BudgetId): TransferMatches
    /** Makes the pair one transfer ([from] is where the money left). */
    suspend fun linkTransfer(budget: BudgetId, from: TransactionId, to: TransactionId)
    /** The other side of [id], to link by hand; [query] searches payee, notes and amount. */
    suspend fun transferCandidates(budget: BudgetId, id: TransactionId, query: String? = null): List<app.centsible.core.model.TransferCandidate>
    suspend fun dismissTransfer(budget: BudgetId, from: TransactionId, to: TransactionId)
    /** Link the sure ones by itself after every bank sync. */
    suspend fun setAutoTransfers(budget: BudgetId, auto: Boolean): Boolean
}
