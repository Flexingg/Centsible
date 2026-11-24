package app.centsible.core.domain

import app.centsible.core.model.BatchChange
import app.centsible.core.model.BatchResult
import app.centsible.core.model.BudgetId
import app.centsible.core.model.ReviewInbox
import app.centsible.core.model.ReviewMode
import app.centsible.core.model.TransactionId
import app.centsible.core.model.TransferMatches
import app.centsible.core.model.TransferSearchOptions

/** The review inbox and bulk edits. */
interface TransactionTools {
    suspend fun inbox(budget: BudgetId, limit: Int = 50): ReviewInbox
    /** Returns how many are left. */
    suspend fun markReviewed(budget: BudgetId, ids: List<TransactionId>): Int
    suspend fun reviewAll(budget: BudgetId): Int
    /** What this person's inbox shows (theirs alone; counts on widgets follow it). */
    suspend fun setReviewMode(budget: BudgetId, mode: ReviewMode): ReviewMode
    suspend fun batch(budget: BudgetId, ids: List<TransactionId>, change: BatchChange): BatchResult

    /** Card payments and other moves seen from both accounts, not yet linked. */
    suspend fun transferMatches(budget: BudgetId, options: TransferSearchOptions = TransferSearchOptions()): TransferMatches
    /** Makes the pair one transfer ([from] is where the money left). */
    suspend fun linkTransfer(budget: BudgetId, from: TransactionId, to: TransactionId)
    /** The other side of [id], to link by hand; [query] searches payee, notes and amount. */
    suspend fun transferCandidates(budget: BudgetId, id: TransactionId, query: String? = null): List<app.centsible.core.model.TransferCandidate>
    suspend fun dismissTransfer(budget: BudgetId, from: TransactionId, to: TransactionId)
    /** Link the sure ones by itself after every bank sync. */
    suspend fun setAutoTransfers(budget: BudgetId, auto: Boolean): Boolean

    /** Probable duplicates from the last four months (same account and amount, a few days apart). */
    suspend fun duplicates(budget: BudgetId): List<app.centsible.core.model.DuplicatePair>
    /** Merges the two into one (Actual keeps the bank-imported one); returns the one kept. */
    suspend fun merge(budget: BudgetId, a: TransactionId, b: TransactionId): app.centsible.core.model.Transaction
    suspend fun dismissDuplicate(budget: BudgetId, a: TransactionId, b: TransactionId)
}
