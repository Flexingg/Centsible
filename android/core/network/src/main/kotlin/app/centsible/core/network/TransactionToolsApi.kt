package app.centsible.core.network

import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable data class ReviewInboxDto(
    val items: List<TransactionDto> = emptyList(),
    val total: Int = 0,
    val more: Boolean = false,
    val since: String = "",
    /** An older bridge leaves it out: its inbox is the "new" one. */
    val mode: String = "new",
)
@Serializable data class ReviewSettingsDto(val mode: String)
@Serializable data class ReviewMarkDto(val ids: List<String>? = null, val all: Boolean? = null)
@Serializable data class ReviewRemainingDto(val remaining: Int)
@Serializable data class BatchSkippedDto(val id: String, val reason: String)
@Serializable data class BatchResultDto(val updated: Int = 0, val deleted: Int = 0, val skipped: List<BatchSkippedDto> = emptyList())

@Serializable data class TransferPairDto(
    val from: TransactionDto,
    val to: TransactionDto,
    val days: Int = 0,
    val confident: Boolean = false,
    val amountDiff: Long = 0L,
    val reason: String? = null,
)
@Serializable data class TransferMatchesDto(val pairs: List<TransferPairDto> = emptyList(), val auto: Boolean = false)
@Serializable data class TransferPairRequestDto(val fromId: String, val toId: String)
@Serializable data class TransferSettingsDto(val auto: Boolean)
@Serializable data class TransferCandidateDto(val transaction: TransactionDto, val exact: Boolean = false, val days: Int = 0)
@Serializable data class TransferCandidatesDto(val transaction: TransactionDto, val items: List<TransferCandidateDto> = emptyList())
@Serializable data class TransferLinkedDto(val from: TransactionDto, val to: TransactionDto)
@Serializable data class DuplicatePairDto(val a: TransactionDto, val b: TransactionDto, val days: Int = 0)
@Serializable data class DuplicatePairsDto(val pairs: List<DuplicatePairDto> = emptyList())
@Serializable data class TransactionPairDto(val ids: List<String>)

class TransactionToolsApi(private val client: BridgeClient) {
    suspend fun inbox(budgetId: String, limit: Int): ReviewInboxDto = client.get("/v1/budgets/$budgetId/review") { parameter("limit", limit) }
    suspend fun mark(budgetId: String, body: ReviewMarkDto): ReviewRemainingDto = client.send(HttpMethod.Post, "/v1/budgets/$budgetId/review", body)
    suspend fun reviewSettings(budgetId: String): ReviewSettingsDto = client.get("/v1/budgets/$budgetId/review/settings")
    suspend fun setReviewSettings(budgetId: String, body: ReviewSettingsDto): ReviewSettingsDto = client.send(HttpMethod.Put, "/v1/budgets/$budgetId/review/settings", body)
    /** Built by hand so "clear the category" (null) survives. */
    suspend fun batch(budgetId: String, body: JsonObject): BatchResultDto = client.send(HttpMethod.Post, "/v1/budgets/$budgetId/transactions/batch", body)
    suspend fun transferMatches(
        budgetId: String,
        allTime: Boolean = false,
        fromAccountId: String? = null,
        toAccountId: String? = null,
        windowDays: Int? = null,
        fuzzy: Boolean = false,
    ): TransferMatchesDto = client.get("/v1/budgets/$budgetId/transfers/matches") {
        if (allTime) parameter("allTime", true)
        fromAccountId?.let { parameter("fromAccountId", it) }
        toAccountId?.let { parameter("toAccountId", it) }
        windowDays?.let { parameter("windowDays", it) }
        if (fuzzy) parameter("fuzzy", true)
    }
    suspend fun transferCandidates(budgetId: String, transactionId: String, q: String?): TransferCandidatesDto =
        client.get("/v1/budgets/$budgetId/transfers/candidates") {
            parameter("transactionId", transactionId)
            q?.takeIf { it.isNotBlank() }?.let { parameter("q", it) }
        }
    suspend fun linkTransfer(budgetId: String, body: TransferPairRequestDto): TransferLinkedDto = client.send(HttpMethod.Post, "/v1/budgets/$budgetId/transfers/link", body)
    suspend fun dismissTransfer(budgetId: String, body: TransferPairRequestDto) {
        client.execute(HttpMethod.Post, "/v1/budgets/$budgetId/transfers/dismiss") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }
    suspend fun transferSettings(budgetId: String, body: TransferSettingsDto): TransferSettingsDto = client.send(HttpMethod.Put, "/v1/budgets/$budgetId/transfers/settings", body)
    suspend fun duplicates(budgetId: String): DuplicatePairsDto = client.get("/v1/budgets/$budgetId/transactions/duplicates")
    suspend fun merge(budgetId: String, body: TransactionPairDto): TransactionDto = client.send(HttpMethod.Post, "/v1/budgets/$budgetId/transactions/merge", body)
    suspend fun dismissDuplicate(budgetId: String, body: TransactionPairDto) {
        client.execute(HttpMethod.Post, "/v1/budgets/$budgetId/transactions/duplicates/dismiss") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }
}
