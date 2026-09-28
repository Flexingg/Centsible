package app.centsible.core.model

data class Budget(val id: BudgetId, val name: String, val encrypted: Boolean)

data class Account(
    val id: AccountId,
    val name: String,
    val offBudget: Boolean,
    val closed: Boolean,
    val balance: Money,
    /** Bank sync provider once the account is linked in Actual (null = manual). */
    val syncSource: String? = null,
    val lastSync: String? = null,
    /** Actual's result of the last bank sync (ok, reauth-required, ...). */
    val bankSyncStatus: String? = null,
)

data class Category(
    val id: CategoryId,
    val name: String,
    val groupId: CategoryGroupId,
    val isIncome: Boolean,
    val hidden: Boolean,
)

data class CategoryGroup(
    val id: CategoryGroupId,
    val name: String,
    val isIncome: Boolean,
    val hidden: Boolean,
    val categories: List<Category>,
)

data class Payee(val id: PayeeId, val name: String, val transferAccountId: AccountId?)

data class Transaction(
    val id: TransactionId,
    val accountId: AccountId,
    val date: String,
    val amount: Money,
    val payeeId: PayeeId?,
    val payeeName: String?,
    val categoryId: CategoryId?,
    val notes: String?,
    val cleared: Boolean,
    val reconciled: Boolean,
    val transferId: TransactionId?,
    val isParent: Boolean,
    val subtransactions: List<Transaction>,
) {
    val isTransfer: Boolean get() = transferId != null
}

data class NewTransaction(
    val id: TransactionId,
    val accountId: AccountId,
    val date: String,
    val amount: Money,
    val payeeId: PayeeId? = null,
    val payeeName: String? = null,
    val categoryId: CategoryId? = null,
    val notes: String? = null,
    val cleared: Boolean? = null,
    val splits: List<Split> = emptyList(),
) {
    data class Split(val amount: Money, val categoryId: CategoryId?, val notes: String? = null)
}

/** A change to one field: [Keep] leaves it alone, [Set] replaces it (possibly with null). */
sealed interface Update<out T> {
    data object Keep : Update<Nothing>
    data class Set<T>(val value: T) : Update<T>
}

data class TransactionPatch(
    val accountId: Update<AccountId> = Update.Keep,
    val date: Update<String> = Update.Keep,
    val amount: Update<Money> = Update.Keep,
    /** Existing payee (including an account's transfer payee) or null to clear. */
    val payeeId: Update<PayeeId?> = Update.Keep,
    /** New or existing payee by name; ignored when [payeeId] is set. */
    val payeeName: Update<String> = Update.Keep,
    val categoryId: Update<CategoryId?> = Update.Keep,
    val notes: Update<String?> = Update.Keep,
    val cleared: Update<Boolean> = Update.Keep,
    /** Replaces all splits; an empty list unsplits. */
    val splits: Update<List<SplitEdit>> = Update.Keep,
) {
    val isEmpty get() = listOf(accountId, date, amount, payeeId, payeeName, categoryId, notes, cleared, splits).all { it == Update.Keep }
}

/** A split in an edit. [id] is set for splits that already exist. */
data class SplitEdit(val id: TransactionId?, val amount: Money, val categoryId: CategoryId?, val notes: String? = null)

data class Preferences(
    val budgetType: BudgetType,
    val currencyCode: String,
    val numberFormat: String,
    val dateFormat: String,
    val firstDayOfWeek: Int,
    val hideFraction: Boolean,
)

data class Page<T>(val items: List<T>, val nextCursor: String?)
