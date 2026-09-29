package app.centsible.core.network

import io.ktor.client.request.parameter
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable data class ReviewInboxDto(val items: List<TransactionDto> = emptyList(), val total: Int = 0, val more: Boolean = false, val since: String = "")
@Serializable data class ReviewMarkDto(val ids: List<String>? = null, val all: Boolean? = null)
@Serializable data class ReviewRemainingDto(val remaining: Int)
@Serializable data class BatchSkippedDto(val id: String, val reason: String)
@Serializable data class BatchResultDto(val updated: Int = 0, val deleted: Int = 0, val skipped: List<BatchSkippedDto> = emptyList())

class TransactionToolsApi(private val client: BridgeClient) {
    suspend fun inbox(budgetId: String, limit: Int): ReviewInboxDto = client.get("/v1/budgets/$budgetId/review") { parameter("limit", limit) }
    suspend fun mark(budgetId: String, body: ReviewMarkDto): ReviewRemainingDto = client.send(HttpMethod.Post, "/v1/budgets/$budgetId/review", body)
    /** Built by hand so "clear the category" (null) survives. */
    suspend fun batch(budgetId: String, body: JsonObject): BatchResultDto = client.send(HttpMethod.Post, "/v1/budgets/$budgetId/transactions/batch", body)
}
