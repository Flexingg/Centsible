package app.centsible.core.domain

import app.centsible.core.model.BudgetId
import app.centsible.core.model.RuleDraft
import app.centsible.core.model.RulePreview
import app.centsible.core.model.TransactionId

/** Rules beyond editing: previews, running a rule on existing transactions, running all rules again. */
interface RuleTools {
    suspend fun preview(budget: BudgetId, draft: RuleDraft, limit: Int = 20): RulePreview
    /** Runs a saved rule on what it matches; returns how many were updated. */
    suspend fun run(budget: BudgetId, ruleId: String, only: List<TransactionId>? = null): Int
    /** Runs every rule again on these; returns how many changed. */
    suspend fun rerun(budget: BudgetId, ids: List<TransactionId>): Int
    /** Every rule on every transaction since [since] (all without it), except reconciled ones: (checked, changed). */
    suspend fun runAll(budget: BudgetId, since: String?): Pair<Int, Int>
}
