package app.centsible.core.domain

import app.centsible.core.model.AccountId
import app.centsible.core.model.Autopilot
import app.centsible.core.model.AverageBasis
import app.centsible.core.model.BudgetId
import app.centsible.core.model.BudgetMonth
import app.centsible.core.model.CategoryId
import app.centsible.core.model.CoverResult
import app.centsible.core.model.Forecast
import app.centsible.core.model.Goal
import app.centsible.core.model.GoalInput
import app.centsible.core.model.YearMonth

/** Budget autopilot, savings goals, and the forecast behind the bill calendar. */
interface PlanAheadGateway {
    suspend fun autopilot(budget: BudgetId, month: YearMonth): Autopilot
    /** [categories] empty budgets every category whose suggestion differs. */
    suspend fun applyAutopilot(budget: BudgetId, month: YearMonth, basis: AverageBasis, categories: List<CategoryId>): BudgetMonth
    suspend fun coverOverspending(budget: BudgetId, month: YearMonth): CoverResult
    suspend fun goals(budget: BudgetId, month: YearMonth? = null): List<Goal>
    /** null removes the goal. */
    suspend fun setGoal(budget: BudgetId, category: CategoryId, goal: GoalInput?)
    suspend fun forecast(budget: BudgetId, days: Int, accounts: List<AccountId> = emptyList(), includeTypical: Boolean = true): Forecast
    /** Goals on accounts and on monthly spending. */
    suspend fun targets(budget: BudgetId, month: YearMonth? = null): List<app.centsible.core.model.Target>
    /** [id] null creates one. */
    suspend fun saveTarget(budget: BudgetId, id: String?, input: app.centsible.core.model.TargetInput)
    suspend fun deleteTarget(budget: BudgetId, id: String)
}
