package app.centsible.core.domain

import app.centsible.core.model.Automation
import app.centsible.core.model.AutomationCategory
import app.centsible.core.model.AutomationPreview
import app.centsible.core.model.AutomationRun
import app.centsible.core.model.BudgetId
import app.centsible.core.model.CategoryAutomations
import app.centsible.core.model.CategoryId
import app.centsible.core.model.YearMonth

/** Budget automations: see [Automation]. */
interface AutomationsGateway {
    suspend fun list(budget: BudgetId, month: YearMonth?): List<AutomationCategory>
    suspend fun get(budget: BudgetId, category: CategoryId, month: YearMonth?): CategoryAutomations
    /** Saves as automations; the category's #template notes stop counting. */
    suspend fun set(budget: BudgetId, category: CategoryId, automations: List<Automation>, month: YearMonth?): CategoryAutomations
    /** Back to the category's #template notes. */
    suspend fun useNotes(budget: BudgetId, category: CategoryId, month: YearMonth?): CategoryAutomations
    suspend fun preview(budget: BudgetId, category: CategoryId, month: YearMonth, automations: List<Automation>): AutomationPreview
    /** All categories (fill empty ones, or overwrite), or just [categories] (overwrites those). */
    suspend fun apply(budget: BudgetId, month: YearMonth, overwrite: Boolean, categories: List<CategoryId>? = null): AutomationRun
}
