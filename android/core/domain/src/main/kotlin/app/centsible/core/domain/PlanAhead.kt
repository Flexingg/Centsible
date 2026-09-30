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

    suspend fun mortgages(budget: BudgetId): List<app.centsible.core.model.Mortgage>
    /** With its amortization schedule. */
    suspend fun mortgage(budget: BudgetId, id: String): app.centsible.core.model.Mortgage
    /** [id] null creates one. */
    suspend fun saveMortgage(budget: BudgetId, id: String?, input: app.centsible.core.model.MortgageInput)
    suspend fun deleteMortgage(budget: BudgetId, id: String)
    suspend fun setHomeValue(budget: BudgetId, id: String, value: app.centsible.core.model.Money)
    /** Takes unrecorded payments' principal off the loan account; returns how many. */
    suspend fun recordPrincipal(budget: BudgetId, id: String): Int

    suspend fun annualBudgets(budget: BudgetId, month: YearMonth): List<app.centsible.core.model.AnnualBudget>
    /** Sets a yearly amount (and applies it to this month); null amount removes it. */
    suspend fun setAnnualBudget(budget: BudgetId, category: CategoryId, amount: app.centsible.core.model.Money?, startMonth: Int = 1)
}
