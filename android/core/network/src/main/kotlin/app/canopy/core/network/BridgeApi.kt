package app.canopy.core.network

import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject

/** Typed endpoints of contract/openapi.yaml v1. */
class BridgeApi(private val client: BridgeClient) {

    suspend fun pair(bridgeUrl: String, cfId: String?, cfSecret: String?, request: PairRequestDto): TokenResponseDto =
        client.executeAnonymous(bridgeUrl, HttpMethod.Post, "/v1/auth/pair", cfId, cfSecret) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun capabilities(): CapabilitiesDto = client.get("/v1/capabilities")
    suspend fun me(): MeDto = client.get("/v1/me")
    suspend fun logout() { client.execute(HttpMethod.Post, "/v1/auth/logout") }

    suspend fun members(): List<MemberDto> = client.get<ItemsDto<MemberDto>>("/v1/household/members").items
    suspend fun createMember(body: NewMemberDto): MemberDto = client.send(HttpMethod.Post, "/v1/household/members", body)
    suspend fun createPairingCode(memberId: String): PairingCodeDto =
        client.execute(HttpMethod.Post, "/v1/household/members/$memberId/pairing-codes").body()
    suspend fun setMemberBudgets(memberId: String, budgetIds: List<String>): MemberDto =
        client.send(HttpMethod.Put, "/v1/household/members/$memberId/budgets", MemberBudgetsDto(budgetIds))
    suspend fun revokeDevice(deviceId: String) { client.execute(HttpMethod.Delete, "/v1/household/devices/$deviceId") }

    suspend fun budgets(): List<BudgetDto> = client.get<ItemsDto<BudgetDto>>("/v1/budgets").items
    suspend fun accounts(budgetId: String): List<AccountDto> = client.get<ItemsDto<AccountDto>>("/v1/budgets/$budgetId/accounts").items
    suspend fun categoryGroups(budgetId: String): List<CategoryGroupDto> =
        client.get<ItemsDto<CategoryGroupDto>>("/v1/budgets/$budgetId/category-groups").items
    suspend fun payees(budgetId: String): List<PayeeDto> = client.get<ItemsDto<PayeeDto>>("/v1/budgets/$budgetId/payees").items

    suspend fun transactions(
        budgetId: String,
        accountId: String?,
        categoryId: String?,
        since: String?,
        until: String?,
        limit: Int,
        cursor: String?,
        search: String? = null,
        uncategorized: Boolean = false,
    ): TransactionPageDto = client.get("/v1/budgets/$budgetId/transactions") {
        search?.takeIf { it.isNotBlank() }?.let { parameter("q", it.trim()) }
        if (uncategorized) parameter("uncategorized", true)
        accountId?.let { parameter("accountId", it) }
        categoryId?.let { parameter("categoryId", it) }
        since?.let { parameter("since", it) }
        until?.let { parameter("until", it) }
        parameter("limit", limit)
        cursor?.let { parameter("cursor", it) }
    }

    suspend fun createTransaction(budgetId: String, body: NewTransactionDto): TransactionDto =
        client.send(HttpMethod.Post, "/v1/budgets/$budgetId/transactions", body)

    suspend fun transaction(budgetId: String, id: String): TransactionDto = client.get("/v1/budgets/$budgetId/transactions/$id")

    /** The body is built by hand so "set to null" and "leave alone" stay distinct. */
    suspend fun updateTransaction(budgetId: String, id: String, patch: JsonObject): TransactionDto =
        client.send(HttpMethod.Patch, "/v1/budgets/$budgetId/transactions/$id", patch)

    suspend fun deleteTransaction(budgetId: String, id: String) {
        client.execute(HttpMethod.Delete, "/v1/budgets/$budgetId/transactions/$id")
    }

    suspend fun preferences(budgetId: String): PreferencesDto = client.get("/v1/budgets/$budgetId/preferences")

    suspend fun createAccount(budgetId: String, body: NewAccountDto): AccountDto = client.send(HttpMethod.Post, "/v1/budgets/$budgetId/accounts", body)
    suspend fun updateAccount(budgetId: String, id: String, body: AccountPatchDto): AccountDto =
        client.send(HttpMethod.Patch, "/v1/budgets/$budgetId/accounts/$id", body)

    /** Null when Actual deleted the account (204). */
    suspend fun closeAccount(budgetId: String, id: String, body: CloseAccountDto): AccountDto? {
        val res = client.execute(HttpMethod.Post, "/v1/budgets/$budgetId/accounts/$id/close") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return if (res.status == HttpStatusCode.NoContent) null else res.body()
    }

    suspend fun reopenAccount(budgetId: String, id: String): AccountDto = client.execute(HttpMethod.Post, "/v1/budgets/$budgetId/accounts/$id/reopen").body()

    suspend fun createCategory(budgetId: String, body: NewCategoryDto): CategoryDto = client.send(HttpMethod.Post, "/v1/budgets/$budgetId/categories", body)
    suspend fun updateCategory(budgetId: String, id: String, body: CategoryPatchDto): CategoryDto =
        client.send(HttpMethod.Patch, "/v1/budgets/$budgetId/categories/$id", body)
    suspend fun deleteCategory(budgetId: String, id: String, transferCategoryId: String?) {
        client.execute(HttpMethod.Delete, "/v1/budgets/$budgetId/categories/$id") { transferCategoryId?.let { parameter("transferCategoryId", it) } }
    }

    suspend fun createGroup(budgetId: String, body: NewGroupDto): CategoryGroupDto = client.send(HttpMethod.Post, "/v1/budgets/$budgetId/category-groups", body)
    suspend fun updateGroup(budgetId: String, id: String, body: GroupPatchDto): CategoryGroupDto =
        client.send(HttpMethod.Patch, "/v1/budgets/$budgetId/category-groups/$id", body)
    suspend fun deleteGroup(budgetId: String, id: String, transferCategoryId: String?) {
        client.execute(HttpMethod.Delete, "/v1/budgets/$budgetId/category-groups/$id") { transferCategoryId?.let { parameter("transferCategoryId", it) } }
    }

    suspend fun months(budgetId: String): List<String> = client.get<MonthsDto>("/v1/budgets/$budgetId/months").months
    suspend fun month(budgetId: String, month: String): BudgetMonthDto = client.get("/v1/budgets/$budgetId/months/$month")

    suspend fun updateCategoryBudget(budgetId: String, month: String, categoryId: String, patch: CategoryBudgetPatchDto): BudgetMonthDto =
        client.send(HttpMethod.Patch, "/v1/budgets/$budgetId/months/$month/categories/$categoryId", patch)

    suspend fun moveMoney(budgetId: String, month: String, body: MoneyTransferDto, idempotencyKey: String): BudgetMonthDto =
        client.send(HttpMethod.Post, "/v1/budgets/$budgetId/months/$month/transfers", body) {
            header("Idempotency-Key", idempotencyKey)
        }

    suspend fun hold(budgetId: String, month: String, amount: Long): BudgetMonthDto =
        client.send(HttpMethod.Put, "/v1/budgets/$budgetId/months/$month/hold", HoldDto(amount))

    suspend fun resetHold(budgetId: String, month: String): BudgetMonthDto =
        client.execute(HttpMethod.Delete, "/v1/budgets/$budgetId/months/$month/hold").body()
}
