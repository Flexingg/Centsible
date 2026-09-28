package app.centsible.core.model

/** Net worth month by month, in total and per account (off-budget ones included). */
data class NetWorth(val points: List<NetWorthPoint>, val accounts: List<NetWorthAccount>)

data class NetWorthAccount(val id: AccountId, val name: String, val offBudget: Boolean, val closed: Boolean, val balances: List<Money>) {
    val latest get() = balances.lastOrNull() ?: Money.Zero
    val isLiability get() = latest.isNegative
}

/** A month's spending against the three before it, and what stands out. */
data class Insights(
    val month: YearMonth,
    val daysElapsed: Int,
    val daysInMonth: Int,
    val spent: Money,
    val typical: Money,
    val projected: Money,
    val categories: List<CategoryTrend>,
    val alerts: List<Insight>,
) {
    val complete get() = daysElapsed >= daysInMonth
}

data class CategoryTrend(val categoryId: CategoryId?, val name: String, val spent: Money, val typical: Money, val projected: Money, val changePct: Int?)

data class Insight(
    /** Stable: the same alert keeps its id, so it's only ever notified once. */
    val id: String,
    val kind: Kind,
    val severity: Severity,
    val title: String,
    val detail: String,
    val amount: Money,
    val date: String,
    val categoryId: CategoryId?,
    val transactionId: TransactionId?,
    val scheduleId: String?,
) {
    enum class Kind { CategoryPace, UnusualTransaction, NewMerchant, PriceChange }
    enum class Severity { Good, Info, Warning }
}

/** Recurring payments found in the history with no schedule yet, and price changes on existing ones. */
data class Subscriptions(val candidates: List<RecurringCandidate>, val priceChanges: List<PriceChange>)

data class RecurringCandidate(
    val payeeId: PayeeId,
    val payeeName: String,
    val accountId: AccountId,
    val accountName: String?,
    val amount: Money,
    val approximateAmount: Boolean,
    val recurrence: Recurrence,
    val lastDate: String?,
    val yearlyAmount: Money,
    val income: Boolean,
)

data class PriceChange(val scheduleId: String, val name: String, val previous: Money, val latest: Money, val changePct: Int, val date: String)

/** The year's story, Spotify Wrapped style. */
data class YearInReview(
    val year: Int,
    val complete: Boolean,
    val availableYears: List<Int>,
    val empty: Boolean,
    val income: Money,
    val spending: Money,
    val saved: Money,
    val savingsRate: Int?,
    val purchases: Int,
    val dailyAverage: Money,
    val topCategories: List<CategoryShare>,
    val topMerchants: List<MerchantTotal>,
    val mostVisited: MerchantTotal?,
    val biggestPurchase: BiggestPurchase?,
    val months: List<MonthTotal>,
    val biggestMonth: MonthTotal?,
    val smallestMonth: MonthTotal?,
    val noSpendDays: Int,
    val longestNoSpendStreak: Streak,
    val newMerchants: Int,
    val merchantsVisited: Int,
    val previousYear: PreviousYear?,
) {
    data class CategoryShare(val categoryId: CategoryId?, val name: String, val amount: Money, val share: Float)
    data class MerchantTotal(val payeeId: PayeeId, val name: String, val amount: Money, val visits: Int)
    data class BiggestPurchase(val transactionId: TransactionId, val date: String, val payeeName: String?, val categoryName: String?, val amount: Money)
    data class MonthTotal(val month: YearMonth, val spending: Money, val income: Money)
    data class Streak(val days: Int, val start: String?, val end: String?)
    data class PreviousYear(val spending: Money, val income: Money, val spendingChangePct: Int?)
}
