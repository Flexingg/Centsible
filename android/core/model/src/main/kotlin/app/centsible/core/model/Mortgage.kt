package app.centsible.core.model

/**
 * A mortgage (any fixed-rate loan) and the home it's for. The terms are kept by the bridge;
 * the balances are Actual accounts (the loan owing, the home's value), off budget, so net
 * worth counts both.
 */
data class Mortgage(
    val id: String,
    val name: String,
    val principal: Money,
    /** Yearly, in percent. */
    val rate: Double,
    val termMonths: Int,
    val firstPayment: String,
    val escrow: Money,
    val extra: Money,
    val payeeId: PayeeId?,
    val paymentAccountId: AccountId?,
    val loanAccountId: AccountId?,
    val homeAccountId: AccountId?,
    val loanSynced: Boolean,
    /** Principal and interest. */
    val monthlyPayment: Money,
    val monthlyTotal: Money,
    val balance: Money,
    val scheduledBalance: Money,
    /** Positive: owed less than the schedule says by now. */
    val aheadBy: Money,
    val paymentsMade: Int,
    val paymentsLeft: Int,
    val payoffDate: String,
    val originalPayoffDate: String,
    val interestPaid: Money,
    val interestLeft: Money,
    val interestSaved: Money,
    val homeValue: Money?,
    val equity: Money?,
    val paymentsFound: Int,
    /** Payments whose principal isn't on the loan account yet. */
    val unrecorded: Int,
    val schedule: List<Row> = emptyList(),
) {
    /** How much of what was borrowed is paid off. */
    val paidOff: Float get() = if (principal.minor > 0) (1f - balance.minor.toFloat() / principal.minor).coerceIn(0f, 1f) else 1f

    data class Row(
        val n: Int,
        val date: String,
        val payment: Money,
        val interest: Money,
        val principal: Money,
        val extra: Money,
        val balance: Money,
        val paidOn: String?,
    )
}

data class MortgageInput(
    val name: String,
    val principal: Money,
    val rate: Double,
    val termMonths: Int,
    val firstPayment: String,
    val escrow: Money = Money.Zero,
    val extra: Money = Money.Zero,
    val payeeId: PayeeId? = null,
    val paymentAccountId: AccountId? = null,
    val loanAccountId: AccountId? = null,
    val homeAccountId: AccountId? = null,
    val createLoanAccount: Boolean = false,
    val currentBalance: Money? = null,
    val homeValue: Money? = null,
)
