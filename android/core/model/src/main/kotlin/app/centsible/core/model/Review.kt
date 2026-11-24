package app.centsible.core.model

/** This person's review inbox (the bridge keeps it per person). */
data class ReviewInbox(
    val items: List<Transaction>,
    val total: Int,
    val more: Boolean,
    val since: String,
    val mode: ReviewMode = ReviewMode.Uncategorized,
)

/** What a person's review inbox holds; each person picks their own. */
enum class ReviewMode(val wire: String, val label: String, val blurb: String) {
    /** Waiting for a category; each leaves as it gets one. */
    Uncategorized("uncategorized", "Uncategorized", "Transactions that still need a category"),
    /** Not yet checked against the bank; each leaves as it's cleared. */
    Uncleared("uncleared", "Uncleared", "Everything not yet cleared, to check against your bank"),
    /** Everything added since you last caught up, plus anything uncategorized. */
    New("new", "New since I caught up", "Every new transaction, plus anything uncategorized");

    companion object {
        fun of(wire: String?) = entries.firstOrNull { it.wire == wire } ?: Uncategorized
    }
}

/** One change applied to many transactions at once (multi-select). */
sealed interface BatchChange {
    data class Category(val id: CategoryId?) : BatchChange
    data class Account(val id: AccountId) : BatchChange
    data class Cleared(val cleared: Boolean) : BatchChange
    data object Delete : BatchChange
}

data class BatchResult(val updated: Int, val deleted: Int, val skipped: List<Skipped>) {
    data class Skipped(val id: TransactionId, val reason: String)
}

/** Two transactions that look like one payment seen from both accounts. */
data class TransferPair(
    val from: Transaction,
    val to: Transaction,
    val days: Int,
    val confident: Boolean,
    /** Difference in minor units between the two sides (0 for exact matches). */
    val amountDiff: Long = 0L,
    /** Match signal, e.g. "Card payment" or "Account name match". */
    val reason: String? = null,
)

/** Options for finding transfer matches across all time or between two specific accounts. */
data class TransferSearchOptions(
    val allTime: Boolean = false,
    val fromAccountId: AccountId? = null,
    val toAccountId: AccountId? = null,
    val windowDays: Int? = null,
    val fuzzy: Boolean = false,
)

/** A possible other side of a transfer: [exact] is the same amount going the other way. */
data class TransferCandidate(val transaction: Transaction, val exact: Boolean, val days: Int)

data class TransferMatches(val pairs: List<TransferPair>, val auto: Boolean)

/** The same transaction twice in an account (a reconnected or switched bank sync); merging keeps one. */
data class DuplicatePair(val a: Transaction, val b: Transaction, val days: Int)
