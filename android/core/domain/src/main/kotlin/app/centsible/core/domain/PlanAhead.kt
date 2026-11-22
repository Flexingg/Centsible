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
    /**
     * Uses [account] (one the bank syncs) as the mortgage's loan account; null unlinks.
     * [retireOld] closes the previous unsynced loan account at zero. Returns its name if closed.
     */
    suspend fun linkMortgageLoan(budget: BudgetId, id: String, account: app.centsible.core.model.AccountId?, retireOld: Boolean): String?
    suspend fun setHomeValue(budget: BudgetId, id: String, value: app.centsible.core.model.Money)
    /** Takes unrecorded payments' principal off the loan account; returns how many. */
    suspend fun recordPrincipal(budget: BudgetId, id: String): Int

    suspend fun categoryHistory(budget: BudgetId, category: CategoryId, month: YearMonth): app.centsible.core.model.CategoryHistory
    /** Categories kept off the books (out of reviews, reports and insights). */
    suspend fun offBooks(budget: BudgetId): Set<CategoryId>
    suspend fun setOffBooks(budget: BudgetId, category: CategoryId, offBooks: Boolean)

    suspend fun annualBudgets(budget: BudgetId, month: YearMonth): List<app.centsible.core.model.AnnualBudget>
    /** Sets a yearly amount (and applies it to this month); null amount removes it. */
    suspend fun setAnnualBudget(budget: BudgetId, category: CategoryId, amount: app.centsible.core.model.Money?, startMonth: Int = 1)

    suspend fun incomeOverview(budget: BudgetId, year: Int? = null): app.centsible.core.model.IncomeOverview
    suspend fun setIncomeSettings(budget: BudgetId, mode: app.centsible.core.model.IncomeMode, categoryIds: List<CategoryId>)
    /** [id] null creates a new pay stub. */
    suspend fun savePayStub(budget: BudgetId, id: String?, input: app.centsible.core.model.PayStubInput): app.centsible.core.model.PayStub
    suspend fun deletePayStub(budget: BudgetId, id: String)
}
