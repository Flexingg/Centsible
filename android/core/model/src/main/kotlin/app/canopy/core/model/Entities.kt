package app.canopy.core.model

data class Budget(val id: BudgetId, val name: String, val encrypted: Boolean)

data class Account(
    val id: AccountId,
    val name: String,
    val offBudget: Boolean,
    val closed: Boolean,
    val balance: Money,
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
    val isTransfer: Boolean,
    val isParent: Boolean,
    val subtransactions: List<Transaction>,
)

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

data class Page<T>(val items: List<T>, val nextCursor: String?)
