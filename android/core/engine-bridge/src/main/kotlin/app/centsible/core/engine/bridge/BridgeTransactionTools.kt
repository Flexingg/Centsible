package app.centsible.core.engine.bridge

import app.centsible.core.domain.TransactionTools
import app.centsible.core.model.BatchChange
import app.centsible.core.model.BatchResult
import app.centsible.core.model.BudgetId
import app.centsible.core.model.ReviewInbox
import app.centsible.core.model.TransactionId
import app.centsible.core.model.TransferMatches
import app.centsible.core.model.TransferPair
import app.centsible.core.network.ReviewMarkDto
import app.centsible.core.network.TransferPairRequestDto
import app.centsible.core.network.TransferSettingsDto
import app.centsible.core.network.TransactionToolsApi
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class BridgeTransactionTools(private val api: TransactionToolsApi, private val onWrite: () -> Unit) : TransactionTools {
    override suspend fun inbox(budget: BudgetId, limit: Int): ReviewInbox {
        val d = api.inbox(budget.raw, limit)
        return ReviewInbox(d.items.map { it.toModel() }, d.total, d.more, d.since)
    }

    override suspend fun markReviewed(budget: BudgetId, ids: List<TransactionId>) =
        api.mark(budget.raw, ReviewMarkDto(ids = ids.map { it.raw })).remaining

    override suspend fun reviewAll(budget: BudgetId) = api.mark(budget.raw, ReviewMarkDto(all = true)).remaining

    override suspend fun batch(budget: BudgetId, ids: List<TransactionId>, change: BatchChange): BatchResult {
        val body = buildJsonObject {
            putJsonArray("ids") { ids.forEach { add(JsonPrimitive(it.raw)) } }
            when (change) {
                BatchChange.Delete -> put("delete", true)
                is BatchChange.Category -> putJsonObject("set") { put("categoryId", change.id?.raw?.let(::JsonPrimitive) ?: JsonNull) }
                is BatchChange.Account -> putJsonObject("set") { put("accountId", change.id.raw) }
                is BatchChange.Cleared -> putJsonObject("set") { put("cleared", change.cleared) }
            }
        }
        val r = api.batch(budget.raw, body)
        onWrite()
        return BatchResult(r.updated, r.deleted, r.skipped.map { BatchResult.Skipped(TransactionId(it.id), it.reason) })
    }

    override suspend fun transferMatches(budget: BudgetId): TransferMatches {
        val d = api.transferMatches(budget.raw)
        return TransferMatches(d.pairs.map { TransferPair(it.from.toModel(), it.to.toModel(), it.days, it.confident) }, d.auto)
    }

    override suspend fun linkTransfer(budget: BudgetId, from: TransactionId, to: TransactionId) {
        api.linkTransfer(budget.raw, TransferPairRequestDto(from.raw, to.raw))
        onWrite()
    }

    override suspend fun transferCandidates(budget: BudgetId, id: TransactionId, query: String?) =
        api.transferCandidates(budget.raw, id.raw, query).items.map { app.centsible.core.model.TransferCandidate(it.transaction.toModel(), it.exact, it.days) }

    override suspend fun dismissTransfer(budget: BudgetId, from: TransactionId, to: TransactionId) {
        api.dismissTransfer(budget.raw, TransferPairRequestDto(from.raw, to.raw))
    }

    override suspend fun setAutoTransfers(budget: BudgetId, auto: Boolean) = api.transferSettings(budget.raw, TransferSettingsDto(auto)).auto
}
