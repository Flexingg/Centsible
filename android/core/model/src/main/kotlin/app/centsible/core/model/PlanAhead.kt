package app.centsible.core.model

/** Budget autopilot: amounts suggested from past spending, and a plan to cover overspending. */
data class Autopilot(
    val month: YearMonth,
    val toBudget: Money,
    val suggestions: List<BudgetSuggestion>,
    val overspent: List<Overspent>,
    val coverMoves: List<CoverMove>,
    val uncovered: Money,
)

enum class AverageBasis(val months: Int) {
    Three(3), Six(6), Twelve(12);

    val label get() = "$months-month average"
}

data class BudgetSuggestion(
    val categoryId: CategoryId,
    val name: String,
    val groupName: String,
    val budgeted: Money,
    val lastMonthSpent: Money,
    /** Average monthly spending per basis (positive = spent). */
    val average: Map<AverageBasis, Money>,
    /** The average rounded up to whole units: what autopilot budgets. */
    val suggested: Map<AverageBasis, Money>,
    val monthsOfHistory: Int,
) {
    fun suggestion(basis: AverageBasis) = suggested[basis] ?: Money.Zero
    fun differs(basis: AverageBasis) = suggestion(basis) != budgeted
}

data class Overspent(val categoryId: CategoryId, val name: String, val amount: Money)

/** [from] is a category id or "to-budget". */
data class CoverMove(val from: String, val fromName: String, val to: CategoryId, val toName: String, val amount: Money)

data class CoverResult(val month: BudgetMonth, val moves: List<CoverMove>, val uncovered: Money)

/** A savings goal kept in a category's notes, the way Actual keeps goal templates. */
data class Goal(
    val categoryId: CategoryId,
    val name: String,
    val groupName: String,
    val kind: Kind,
    val target: Money,
    val targetMonth: YearMonth?,
    val balance: Money,
    val budgetedThisMonth: Money,
    val progress: Float,
    val remaining: Money,
    /** For "save by" goals: what to budget each month from now on to make it. */
    val monthlyNeeded: Money?,
    val avgContribution: Money,
    /** When the balance gets there at the recent pace. */
    val projectedMonth: YearMonth?,
    val status: Status,
) {
    enum class Kind { Balance, By }
    enum class Status { Reached, OnTrack, Behind, Stalled }
}

data class GoalInput(val kind: Goal.Kind, val target: Money, val targetMonth: YearMonth?)

/**
 * A goal kept by the bridge (Actual has no place for these): grow an account to an amount,
 * keep a category's spending under an amount each month, or put at least an amount (or a
 * share of the month's income) into a category each month.
 */
data class Target(
    val id: String,
    val kind: Kind,
    val name: String,
    val accountId: AccountId?,
    val categoryId: CategoryId?,
    val amount: Money?,
    val percentOfIncome: Double?,
    val targetMonth: YearMonth?,
    /** The balance, or what went out this month. */
    val current: Money,
    /** This month's amount to reach or stay under (a share of income, worked out). */
    val goal: Money,
    val progress: Float,
    val status: Status,
    val remaining: Money,
    val monthlyNeeded: Money?,
    val avgChange: Money,
    val projectedMonth: YearMonth?,
    val income: Money,
    /** How far through the month (1 for a past one). */
    val pace: Float,
    val monthsKept: Int,
    val missing: Boolean,
    val history: List<Month>,
) {
    enum class Kind { Account, SpendUnder, SpendAtLeast }
    enum class Status { Reached, OnTrack, Behind, Over, Stalled }
    data class Month(val month: YearMonth, val value: Money, val goal: Money)
}

data class TargetInput(
    val kind: Target.Kind,
    val name: String? = null,
    val accountId: AccountId? = null,
    val categoryId: CategoryId? = null,
    val amount: Money? = null,
    val percentOfIncome: Double? = null,
    val targetMonth: YearMonth? = null,
)

/** Projected balances from scheduled bills and income plus typical everyday money. */
data class Forecast(
    val from: String,
    val to: String,
    val accountIds: List<AccountId>,
    val startingBalance: Money,
    val typicalDaily: Money,
    val events: List<ForecastEvent>,
    val days: List<ForecastDay>,
    val lowest: ForecastDay,
    /** Scheduled bills already paid this month, before today. */
    val paid: List<PaidBill> = emptyList(),
) {
    fun eventsOn(date: String) = events.filter { it.date == date }
    fun paidOn(date: String) = paid.filter { it.date == date }
    fun day(date: String) = days.firstOrNull { it.date == date }
}

data class ForecastEvent(
    val date: String,
    val scheduleId: String,
    val name: String,
    val accountName: String?,
    val amount: Money,
    val internalTransfer: Boolean,
    val overdue: Boolean,
)

data class PaidBill(val date: String, val scheduleId: String, val name: String, val amount: Money)

data class ForecastDay(val date: String, val balance: Money, val scheduled: Money = Money.Zero, val typical: Money = Money.Zero)
