package app.centsible.core.model

enum class BudgetType { Envelope, Tracking, Unknown }

/**
 * One envelope-budget month, as computed by Actual. The app never re-derives these
 * numbers; it only presents them. That is what keeps it correct across Actual updates.
 */
data class BudgetMonth(
    val month: YearMonth,
    val budgetType: BudgetType,
    val toBudget: Money,
    val incomeAvailable: Money,
    val lastMonthOverspent: Money,
    val forNextMonth: Money,
    val fromLastMonth: Money,
    val totalBudgeted: Money,
    val totalIncome: Money,
    val totalSpent: Money,
    val totalBalance: Money,
    val groups: List<BudgetGroup>,
) {
    val expenseGroups get() = groups.filter { !it.isIncome }
    val incomeGroups get() = groups.filter { it.isIncome }
}

data class BudgetGroup(
    val id: CategoryGroupId,
    val name: String,
    val isIncome: Boolean,
    val hidden: Boolean,
    val budgeted: Money,
    val spent: Money,
    val balance: Money,
    val received: Money,
    val categories: List<BudgetCategory>,
)

data class BudgetCategory(
    val id: CategoryId,
    val name: String,
    val hidden: Boolean,
    val budgeted: Money,
    val spent: Money,
    val balance: Money,
    val received: Money,
    val carryover: Boolean,
) {
    /**
     * Share of the envelope used, for progress bars. In Actual, balance = budgeted +
     * rollover + spent (spent is negative), so what was available is balance - spent.
     * Display-only arithmetic on Actual's own numbers; no budget math is re-derived.
     */
    val progress: Float
        get() {
            val available = balance.minor - spent.minor
            if (available <= 0) return if (spent.isZero) 0f else 1f
            return ((-spent.minor).toFloat() / available).coerceIn(0f, 1f)
        }
    val isOverspent get() = balance.isNegative
}

/** Source or destination of a "move money" action. */
sealed interface BudgetPot {
    data object ToBudget : BudgetPot
    data class Envelope(val id: CategoryId) : BudgetPot
}
