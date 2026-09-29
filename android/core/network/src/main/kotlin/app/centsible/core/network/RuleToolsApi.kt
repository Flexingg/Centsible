package app.centsible.core.network

import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable data class RulePreviewBodyDto(
    val stage: String? = null,
    val conditionsOp: String = "and",
    val conditions: List<RuleItemDto>,
    val actions: List<RuleItemDto>,
    val limit: Int = 20,
)
@Serializable data class RuleChangeDto(val field: String, val value: JsonElement? = null, val note: String? = null, val error: String? = null)
@Serializable data class RulePreviewItemDto(val transaction: TransactionDto, val changes: List<RuleChangeDto> = emptyList())
@Serializable data class RulePreviewDto(val matchCount: Int = 0, val items: List<RulePreviewItemDto> = emptyList(), val errors: List<String> = emptyList())
@Serializable data class RuleRunBodyDto(val transactionIds: List<String>? = null)
@Serializable data class RuleRunDto(val updated: Int)
@Serializable data class RerunBodyDto(val transactionIds: List<String>)
@Serializable data class RerunDto(val checked: Int, val changed: Int)

class RuleToolsApi(private val client: BridgeClient) {
    suspend fun preview(budgetId: String, body: RulePreviewBodyDto): RulePreviewDto = client.send(HttpMethod.Post, "/v1/budgets/$budgetId/rules/preview", body)
    suspend fun run(budgetId: String, ruleId: String, body: RuleRunBodyDto): RuleRunDto = client.send(HttpMethod.Post, "/v1/budgets/$budgetId/rules/$ruleId/run", body)
    suspend fun rerun(budgetId: String, body: RerunBodyDto): RerunDto = client.send(HttpMethod.Post, "/v1/budgets/$budgetId/rules/rerun", body)
}
