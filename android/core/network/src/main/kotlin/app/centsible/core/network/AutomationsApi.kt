package app.centsible.core.network

import io.ktor.client.call.body
import io.ktor.client.request.parameter
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable

/** One automation, flat: which fields matter depends on [type] (see the contract). */
@Serializable data class AutomationDto(
    val type: String,
    val priority: Int? = null,
    val description: String? = null,
    val monthly: Long? = null,
    val amount: Long? = null,
    val limit: AutomationLimitDto? = null,
    val every: AutomationEveryDto? = null,
    val starting: String? = null,
    val month: String? = null,
    val repeat: AutomationRepeatDto? = null,
    val spendFrom: String? = null,
    val schedule: String? = null,
    val full: Boolean? = null,
    val adjustment: AutomationAdjustmentDto? = null,
    val months: Int? = null,
    val monthsAgo: Int? = null,
    val percent: Double? = null,
    val of: String? = null,
    val previous: Boolean? = null,
    val weight: Double? = null,
    val line: String? = null,
    val error: String? = null,
)
@Serializable data class AutomationLimitDto(val amount: Long, val hold: Boolean = false, val period: String = "monthly", val start: String? = null)
@Serializable data class AutomationEveryDto(val unit: String, val count: Int)
@Serializable data class AutomationRepeatDto(val unit: String, val count: Int)
@Serializable data class AutomationAdjustmentDto(val kind: String, val value: Double)

@Serializable data class CategoryAutomationsDto(
    val categoryId: String,
    val source: String,
    val automations: List<AutomationDto> = emptyList(),
    val notesHaveTemplates: Boolean = false,
    val month: String? = null,
    val projected: Long? = null,
    val perAutomation: List<Long>? = null,
)
@Serializable data class AutomationCategoryDto(
    val categoryId: String,
    val name: String,
    val groupId: String,
    val groupName: String,
    val isIncome: Boolean = false,
    val hidden: Boolean = false,
    val source: String,
    val automations: List<AutomationDto> = emptyList(),
    val projected: Long? = null,
)
@Serializable data class AutomationListDto(val month: String? = null, val categories: List<AutomationCategoryDto> = emptyList())
@Serializable data class AutomationsBodyDto(val automations: List<AutomationDto>)
@Serializable data class AutomationPreviewBodyDto(val month: String, val automations: List<AutomationDto>)
@Serializable data class AutomationPreviewDto(val month: String, val projected: Long, val perAutomation: List<Long> = emptyList())
@Serializable data class AutomationApplyBodyDto(val month: String, val overwrite: Boolean = false, val categoryIds: List<String>? = null)
@Serializable data class AutomationRunDto(val ok: Boolean, val message: String, val details: String? = null)

class AutomationsApi(private val client: BridgeClient) {
    private fun cat(b: String, c: String) = "/v1/budgets/$b/categories/$c/automations"

    suspend fun list(budgetId: String, month: String?): AutomationListDto =
        client.get("/v1/budgets/$budgetId/automations") { month?.let { parameter("month", it) } }
    suspend fun get(budgetId: String, categoryId: String, month: String?): CategoryAutomationsDto =
        client.get(cat(budgetId, categoryId)) { month?.let { parameter("month", it) } }
    suspend fun set(budgetId: String, categoryId: String, body: AutomationsBodyDto, month: String?): CategoryAutomationsDto =
        client.send(HttpMethod.Put, cat(budgetId, categoryId), body) { month?.let { parameter("month", it) } }
    suspend fun useNotes(budgetId: String, categoryId: String, month: String?): CategoryAutomationsDto =
        client.execute(HttpMethod.Delete, cat(budgetId, categoryId)) { month?.let { parameter("month", it) } }.body()
    suspend fun preview(budgetId: String, categoryId: String, body: AutomationPreviewBodyDto): AutomationPreviewDto =
        client.send(HttpMethod.Post, cat(budgetId, categoryId) + "/preview", body)
    suspend fun apply(budgetId: String, body: AutomationApplyBodyDto): AutomationRunDto =
        client.send(HttpMethod.Post, "/v1/budgets/$budgetId/automations/apply", body)
}
