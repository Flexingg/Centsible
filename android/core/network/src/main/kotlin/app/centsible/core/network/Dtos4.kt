package app.centsible.core.network

import kotlinx.serialization.Serializable

// Net worth by account, insights, subscriptions, year in review (see contract/openapi.yaml, tag "insights").

@Serializable data class NetWorthAccountDto(val accountId: String, val name: String, val offBudget: Boolean = false, val closed: Boolean = false, val balances: List<Long> = emptyList())
@Serializable data class NetWorthDetailDto(val points: List<NetWorthPointDto>, val accounts: List<NetWorthAccountDto> = emptyList())

@Serializable data class CategoryTrendDto(val categoryId: String? = null, val name: String, val spent: Long = 0, val typical: Long = 0, val projected: Long = 0, val changePct: Int? = null)
@Serializable data class InsightDto(
    val id: String,
    val kind: String,
    val severity: String,
    val title: String,
    val detail: String,
    val amount: Long = 0,
    val date: String,
    val categoryId: String? = null,
    val transactionId: String? = null,
    val scheduleId: String? = null,
)
@Serializable data class InsightsDto(
    val month: String,
    val daysElapsed: Int,
    val daysInMonth: Int,
    val spent: Long = 0,
    val typical: Long = 0,
    val projected: Long = 0,
    val categories: List<CategoryTrendDto> = emptyList(),
    val alerts: List<InsightDto> = emptyList(),
)

@Serializable data class CandidateDto(
    val payeeId: String,
    val payeeName: String,
    val accountId: String,
    val accountName: String? = null,
    val amount: Long,
    val approximateAmount: Boolean = false,
    val recurrence: RecurrenceDto,
    val lastDate: String? = null,
    val yearlyAmount: Long = 0,
    val income: Boolean = false,
)
@Serializable data class PriceChangeDto(val scheduleId: String, val name: String, val previous: Long, val latest: Long, val changePct: Int, val date: String)
@Serializable data class SubscriptionsDto(val candidates: List<CandidateDto> = emptyList(), val priceChanges: List<PriceChangeDto> = emptyList())
@Serializable data class DismissDto(val payeeId: String)

@Serializable data class CategoryShareDto(val categoryId: String? = null, val name: String, val amount: Long, val share: Float = 0f)
@Serializable data class MerchantTotalDto(val payeeId: String, val name: String, val amount: Long, val visits: Int)
@Serializable data class BiggestPurchaseDto(val transactionId: String, val date: String, val payeeName: String? = null, val categoryName: String? = null, val amount: Long)
@Serializable data class MonthTotalsDto(val month: String, val spending: Long = 0, val income: Long = 0)
@Serializable data class StreakDto(val days: Int = 0, val start: String? = null, val end: String? = null)
@Serializable data class PreviousYearDto(val spending: Long = 0, val income: Long = 0, val spendingChangePct: Int? = null)
@Serializable data class YearInReviewDto(
    val year: Int,
    val complete: Boolean = true,
    val availableYears: List<Int> = emptyList(),
    val empty: Boolean = false,
    val income: Long = 0,
    val spending: Long = 0,
    val saved: Long = 0,
    val savingsRate: Int? = null,
    val purchases: Int = 0,
    val dailyAverage: Long = 0,
    val topCategories: List<CategoryShareDto> = emptyList(),
    val topMerchants: List<MerchantTotalDto> = emptyList(),
    val mostVisited: MerchantTotalDto? = null,
    val biggestPurchase: BiggestPurchaseDto? = null,
    val months: List<MonthTotalsDto> = emptyList(),
    val biggestMonth: MonthTotalsDto? = null,
    val smallestMonth: MonthTotalsDto? = null,
    val noSpendDays: Int = 0,
    val longestNoSpendStreak: StreakDto = StreakDto(),
    val newMerchants: Int = 0,
    val merchantsVisited: Int = 0,
    val previousYear: PreviousYearDto? = null,
    val period: String = "year",
    val start: String? = null,
    val end: String? = null,
    val label: String? = null,
    val previousStart: String? = null,
    val nextStart: String? = null,
    val buckets: List<ReviewBucketDto> = emptyList(),
    val biggestBucket: ReviewBucketDto? = null,
    val smallestBucket: ReviewBucketDto? = null,
    val previousPeriod: PreviousPeriodDto? = null,
)
@Serializable data class ReviewBucketDto(val key: String, val label: String, val start: String, val spending: Long = 0, val income: Long = 0)
@Serializable data class PreviousPeriodDto(val spending: Long = 0, val income: Long = 0, val spendingChangePct: Int? = null, val label: String = "")
