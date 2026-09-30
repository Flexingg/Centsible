package app.centsible.core.model

/** This person's review inbox (the bridge keeps it per person). */
data class ReviewInbox(val items: List<Transaction>, val total: Int, val more: Boolean, val since: String)

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
data class TransferPair(val from: Transaction, val to: Transaction, val days: Int, val confident: Boolean)

data class TransferMatches(val pairs: List<TransferPair>, val auto: Boolean)
