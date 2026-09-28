package app.centsible.core.domain

import app.centsible.core.model.BudgetId
import app.centsible.core.model.Insights
import app.centsible.core.model.NetWorth
import app.centsible.core.model.PayeeId
import app.centsible.core.model.Subscriptions
import app.centsible.core.model.YearInReview
import app.centsible.core.model.YearMonth

/** Net worth by account, spending trends and alerts, recurring-payment discovery, year in review. */
interface InsightsGateway {
    suspend fun netWorth(budget: BudgetId, months: Int): NetWorth
    suspend fun insights(budget: BudgetId, month: YearMonth? = null): Insights
    suspend fun subscriptions(budget: BudgetId): Subscriptions
    suspend fun dismissSubscription(budget: BudgetId, payee: PayeeId)
    /** null: the bridge picks (this year from November, otherwise last year). */
    suspend fun yearInReview(budget: BudgetId, year: Int? = null): YearInReview
    /** [date]: any day in the period; null for the last finished one. */
    suspend fun review(budget: BudgetId, period: app.centsible.core.model.ReviewPeriod, date: String? = null): YearInReview
}
