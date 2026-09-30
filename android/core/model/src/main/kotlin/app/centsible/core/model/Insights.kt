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
data class Subscriptions(val candidates: List<RecurringCandidate>, val priceChanges: List<PriceChange>, val patterns: List<RecurringPattern> = emptyList())

/**
 * What Actual's discovery misses: a bill on about the same day each month whose amount
 * moves around (utilities), or pay on business days (the first weekday, around the 15th).
 */
data class RecurringPattern(
    val payeeId: PayeeId,
    val payeeName: String,
    val accountId: AccountId,
    val accountName: String?,
    val income: Boolean,
    /** Days of the month; 1 means the first weekday when [firstWeekday]. */
    val days: List<Int>,
    val firstWeekday: Boolean,
    /** A weekend date moves to the Monday after (else the Friday before). */
    val weekendAfter: Boolean,
    val description: String,
    val occurrences: List<Occurrence>,
    val min: Money,
    val max: Money,
    val average3: Money,
    val varies: Boolean,
    val next: Projection?,
) {
    data class Occurrence(val date: String, val amount: Money)
    /** The next one; [fromLastYear] means the same month last year, else the last three's average. */
    data class Projection(val date: String, val amount: Money, val fromLastYear: Boolean)
}

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

enum class ReviewPeriod(val key: String, val label: String) {
    Week("week", "Week"), Month("month", "Month"), Quarter("quarter", "Quarter"), Year("year", "Year");

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: Year
    }
}

/** A period's story (week, month, quarter or year), Spotify Wrapped style. */
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
    val period: ReviewPeriod = ReviewPeriod.Year,
    val start: String = "$year-01-01",
    val end: String = "$year-12-31",
    /** "Week of Sep 21, 2026", "September 2026", "Q3 2026", "2026". */
    val label: String = "$year",
    val previousStart: String? = null,
    /** Null while the next period hasn't started. */
    val nextStart: String? = null,
    /** Days for a week or month, weeks for a quarter, months for a year. */
    val buckets: List<Bucket> = emptyList(),
    val biggestBucket: Bucket? = null,
    val smallestBucket: Bucket? = null,
    val previousPeriod: PreviousPeriod? = null,
) {
    data class Bucket(val key: String, val label: String, val start: String, val spending: Money, val income: Money)
    data class PreviousPeriod(val spending: Money, val income: Money, val spendingChangePct: Int?, val label: String)

    data class CategoryShare(val categoryId: CategoryId?, val name: String, val amount: Money, val share: Float)
    data class MerchantTotal(val payeeId: PayeeId, val name: String, val amount: Money, val visits: Int)
    data class BiggestPurchase(val transactionId: TransactionId, val date: String, val payeeName: String?, val categoryName: String?, val amount: Money)
    data class MonthTotal(val month: YearMonth, val spending: Money, val income: Money)
    data class Streak(val days: Int, val start: String?, val end: String?)
    data class PreviousYear(val spending: Money, val income: Money, val spendingChangePct: Int?)
}
