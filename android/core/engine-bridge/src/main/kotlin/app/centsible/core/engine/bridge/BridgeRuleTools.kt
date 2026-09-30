package app.centsible.core.engine.bridge

import app.centsible.core.domain.RuleTools
import app.centsible.core.model.BudgetId
import app.centsible.core.model.RuleChange
import app.centsible.core.model.RuleDraft
import app.centsible.core.model.RulePreview
import app.centsible.core.model.RulePreviewItem
import app.centsible.core.model.TransactionId
import app.centsible.core.network.RerunBodyDto
import app.centsible.core.network.RulePreviewBodyDto
import app.centsible.core.network.RuleRunBodyDto
import app.centsible.core.network.RuleToolsApi
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

class BridgeRuleTools(private val api: RuleToolsApi, private val onWrite: () -> Unit) : RuleTools {
    override suspend fun preview(budget: BudgetId, draft: RuleDraft, limit: Int): RulePreview {
        val d = draft.toDto()
        val r = api.preview(budget.raw, RulePreviewBodyDto(d.stage, d.conditionsOp, d.conditions, d.actions, limit))
        return RulePreview(
            r.matchCount,
            r.items.map { i ->
                RulePreviewItem(
                    i.transaction.toModel(),
                    i.changes.map { c ->
                        val v = c.value
                        RuleChange(c.field, if (v == null || v is JsonNull) null else (v as? JsonPrimitive)?.content ?: v.toString(), c.note, c.error)
                    },
                )
            },
            r.errors,
        )
    }

    override suspend fun run(budget: BudgetId, ruleId: String, only: List<TransactionId>?) =
        api.run(budget.raw, ruleId, RuleRunBodyDto(only?.map { it.raw })).updated.also { onWrite() }

    override suspend fun rerun(budget: BudgetId, ids: List<TransactionId>) =
        api.rerun(budget.raw, RerunBodyDto(ids.map { it.raw })).changed.also { onWrite() }

    override suspend fun runAll(budget: BudgetId, since: String?) =
        api.runAll(budget.raw, app.centsible.core.network.RunAllBodyDto(since)).let { it.checked to it.changed }.also { onWrite() }
}
