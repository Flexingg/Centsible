package app.centsible.core.network

import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable data class ReviewInboxDto(val items: List<TransactionDto> = emptyList(), val total: Int = 0, val more: Boolean = false, val since: String = "")
@Serializable data class ReviewMarkDto(val ids: List<String>? = null, val all: Boolean? = null)
@Serializable data class ReviewRemainingDto(val remaining: Int)
@Serializable data class BatchSkippedDto(val id: String, val reason: String)
@Serializable data class BatchResultDto(val updated: Int = 0, val deleted: Int = 0, val skipped: List<BatchSkippedDto> = emptyList())

@Serializable data class TransferPairDto(val from: TransactionDto, val to: TransactionDto, val days: Int = 0, val confident: Boolean = false)
@Serializable data class TransferMatchesDto(val pairs: List<TransferPairDto> = emptyList(), val auto: Boolean = false)
@Serializable data class TransferPairRequestDto(val fromId: String, val toId: String)
@Serializable data class TransferSettingsDto(val auto: Boolean)
@Serializable data class TransferLinkedDto(val from: TransactionDto, val to: TransactionDto)

class TransactionToolsApi(private val client: BridgeClient) {
    suspend fun inbox(budgetId: String, limit: Int): ReviewInboxDto = client.get("/v1/budgets/$budgetId/review") { parameter("limit", limit) }
    suspend fun mark(budgetId: String, body: ReviewMarkDto): ReviewRemainingDto = client.send(HttpMethod.Post, "/v1/budgets/$budgetId/review", body)
    /** Built by hand so "clear the category" (null) survives. */
    suspend fun batch(budgetId: String, body: JsonObject): BatchResultDto = client.send(HttpMethod.Post, "/v1/budgets/$budgetId/transactions/batch", body)
    suspend fun transferMatches(budgetId: String): TransferMatchesDto = client.get("/v1/budgets/$budgetId/transfers/matches")
    suspend fun linkTransfer(budgetId: String, body: TransferPairRequestDto): TransferLinkedDto = client.send(HttpMethod.Post, "/v1/budgets/$budgetId/transfers/link", body)
    suspend fun dismissTransfer(budgetId: String, body: TransferPairRequestDto) {
        client.execute(HttpMethod.Post, "/v1/budgets/$budgetId/transfers/dismiss") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }
    suspend fun transferSettings(budgetId: String, body: TransferSettingsDto): TransferSettingsDto = client.send(HttpMethod.Put, "/v1/budgets/$budgetId/transfers/settings", body)
}
