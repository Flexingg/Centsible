package app.centsible.core.network

import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable

@Serializable data class HomeLayoutDto(val order: List<String> = emptyList(), val hidden: List<String> = emptyList())
@Serializable data class CategoryAppearanceDto(val categoryId: String, val color: String? = null, val emoji: String? = null)
@Serializable data class AppearanceListDto(val categories: List<CategoryAppearanceDto> = emptyList())
@Serializable data class AppearanceBodyDto(val color: String?, val emoji: String?)

class PersonalApi(private val client: BridgeClient) {
    suspend fun home(): HomeLayoutDto = client.get("/v1/me/home")
    suspend fun setHome(body: HomeLayoutDto): HomeLayoutDto = client.send(HttpMethod.Put, "/v1/me/home", body)
    suspend fun appearance(budgetId: String): AppearanceListDto = client.get("/v1/budgets/$budgetId/appearance")
    suspend fun setAppearance(budgetId: String, id: String, body: AppearanceBodyDto): CategoryAppearanceDto =
        client.send(HttpMethod.Put, "/v1/budgets/$budgetId/categories/$id/appearance", body)
}
