package app.centsible.core.engine.bridge

import app.centsible.core.domain.InsightsGateway
import app.centsible.core.model.AccountId
import app.centsible.core.model.BudgetId
import app.centsible.core.model.CategoryId
import app.centsible.core.model.CategoryTrend
import app.centsible.core.model.Insight
import app.centsible.core.model.Insights
import app.centsible.core.model.Money
import app.centsible.core.model.NetWorth
import app.centsible.core.model.NetWorthAccount
import app.centsible.core.model.PayeeId
import app.centsible.core.model.PriceChange
import app.centsible.core.model.RecurringCandidate
import app.centsible.core.model.Subscriptions
import app.centsible.core.model.TransactionId
import app.centsible.core.model.YearInReview
import app.centsible.core.model.YearMonth
import app.centsible.core.network.MerchantTotalDto
import app.centsible.core.network.MonthTotalsDto
import app.centsible.core.network.PlanningApi

class BridgeInsights(private val api: PlanningApi) : InsightsGateway {
    override suspend fun netWorth(budget: BudgetId, months: Int): NetWorth {
        val dto = api.netWorthDetail(budget.raw, months)
        return NetWorth(
            dto.points.map { it.toModel() },
            dto.accounts.map { NetWorthAccount(AccountId(it.accountId), it.name, it.offBudget, it.closed, it.balances.map(::Money)) },
        )
    }

    override suspend fun insights(budget: BudgetId, month: YearMonth?): Insights {
        val d = api.insights(budget.raw, month?.raw)
        return Insights(
            YearMonth(d.month), d.daysElapsed, d.daysInMonth, Money(d.spent), Money(d.typical), Money(d.projected),
            d.categories.map { CategoryTrend(it.categoryId?.let(::CategoryId), it.name, Money(it.spent), Money(it.typical), Money(it.projected), it.changePct) },
            d.alerts.map {
                Insight(
                    it.id,
                    when (it.kind) {
                        "category-pace" -> Insight.Kind.CategoryPace
                        "unusual-transaction" -> Insight.Kind.UnusualTransaction
                        "new-merchant" -> Insight.Kind.NewMerchant
                        else -> Insight.Kind.PriceChange
                    },
                    when (it.severity) {
                        "warning" -> Insight.Severity.Warning
                        "good" -> Insight.Severity.Good
                        else -> Insight.Severity.Info
                    },
                    it.title, it.detail, Money(it.amount), it.date,
                    it.categoryId?.let(::CategoryId), it.transactionId?.let(::TransactionId), it.scheduleId,
                )
            },
        )
    }

    override suspend fun subscriptions(budget: BudgetId): Subscriptions {
        val d = api.subscriptions(budget.raw)
        return Subscriptions(
            d.candidates.map {
                RecurringCandidate(
                    PayeeId(it.payeeId), it.payeeName, AccountId(it.accountId), it.accountName, Money(it.amount), it.approximateAmount,
                    it.recurrence.toModel(), it.lastDate, Money(it.yearlyAmount), it.income,
                )
            },
            d.priceChanges.map { PriceChange(it.scheduleId, it.name, Money(it.previous), Money(it.latest), it.changePct, it.date) },
        )
    }

    override suspend fun dismissSubscription(budget: BudgetId, payee: PayeeId) = api.dismissSubscription(budget.raw, payee.raw)

    override suspend fun yearInReview(budget: BudgetId, year: Int?): YearInReview {
        val d = api.yearInReview(budget.raw, year)
        fun MerchantTotalDto.m() = YearInReview.MerchantTotal(PayeeId(payeeId), name, Money(amount), visits)
        fun MonthTotalsDto.m() = YearInReview.MonthTotal(YearMonth(month), Money(spending), Money(income))
        return YearInReview(
            d.year, d.complete, d.availableYears, d.empty, Money(d.income), Money(d.spending), Money(d.saved), d.savingsRate, d.purchases, Money(d.dailyAverage),
            d.topCategories.map { YearInReview.CategoryShare(it.categoryId?.let(::CategoryId), it.name, Money(it.amount), it.share) },
            d.topMerchants.map { it.m() },
            d.mostVisited?.m(),
            d.biggestPurchase?.let { YearInReview.BiggestPurchase(TransactionId(it.transactionId), it.date, it.payeeName, it.categoryName, Money(it.amount)) },
            d.months.map { it.m() },
            d.biggestMonth?.m(),
            d.smallestMonth?.m(),
            d.noSpendDays,
            YearInReview.Streak(d.longestNoSpendStreak.days, d.longestNoSpendStreak.start, d.longestNoSpendStreak.end),
            d.newMerchants,
            d.merchantsVisited,
            d.previousYear?.let { YearInReview.PreviousYear(Money(it.spending), Money(it.income), it.spendingChangePct) },
        )
    }
}
