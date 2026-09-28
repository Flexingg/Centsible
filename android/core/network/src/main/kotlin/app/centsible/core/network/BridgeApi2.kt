package app.centsible.core.network

import io.ktor.client.call.body
import io.ktor.client.request.parameter
import io.ktor.http.HttpMethod

/** Phase 2 endpoints. */
class PlanningApi(private val client: BridgeClient) {
    private fun b(budgetId: String) = "/v1/budgets/$budgetId"

    suspend fun schedules(budgetId: String, upcoming: Int): List<ScheduleDto> =
        client.get<ItemsDto<ScheduleDto>>("${b(budgetId)}/schedules") { parameter("upcoming", upcoming) }.items
    suspend fun createSchedule(budgetId: String, body: ScheduleInputDto): ScheduleDto = client.send(HttpMethod.Post, "${b(budgetId)}/schedules", body)
    suspend fun updateSchedule(budgetId: String, id: String, body: ScheduleInputDto): ScheduleDto = client.send(HttpMethod.Patch, "${b(budgetId)}/schedules/$id", body)
    suspend fun deleteSchedule(budgetId: String, id: String) { client.execute(HttpMethod.Delete, "${b(budgetId)}/schedules/$id") }
    suspend fun skipSchedule(budgetId: String, id: String): ScheduleDto = client.execute(HttpMethod.Post, "${b(budgetId)}/schedules/$id/skip").body()
    suspend fun postSchedule(budgetId: String, id: String): ScheduleDto = client.execute(HttpMethod.Post, "${b(budgetId)}/schedules/$id/post").body()

    suspend fun rules(budgetId: String): List<RuleDto> = client.get<ItemsDto<RuleDto>>("${b(budgetId)}/rules").items
    suspend fun createRule(budgetId: String, body: RuleInputDto): RuleDto = client.send(HttpMethod.Post, "${b(budgetId)}/rules", body)
    suspend fun updateRule(budgetId: String, id: String, body: RuleInputDto): RuleDto = client.send(HttpMethod.Put, "${b(budgetId)}/rules/$id", body)
    suspend fun deleteRule(budgetId: String, id: String) { client.execute(HttpMethod.Delete, "${b(budgetId)}/rules/$id") }

    suspend fun payeeStats(budgetId: String): List<PayeeStatDto> = client.get<ItemsDto<PayeeStatDto>>("${b(budgetId)}/payees/stats").items
    suspend fun renamePayee(budgetId: String, id: String, name: String) { client.send<RenameDto, PayeeDto>(HttpMethod.Patch, "${b(budgetId)}/payees/$id", RenameDto(name)) }
    suspend fun mergePayees(budgetId: String, id: String, mergeIds: List<String>) {
        client.execute(HttpMethod.Post, "${b(budgetId)}/payees/$id/merge") { jsonBody(MergeDto(mergeIds)) }
    }
    suspend fun deletePayee(budgetId: String, id: String) { client.execute(HttpMethod.Delete, "${b(budgetId)}/payees/$id") }

    suspend fun tags(budgetId: String): List<TagDto> = client.get<ItemsDto<TagDto>>("${b(budgetId)}/tags").items
    suspend fun createTag(budgetId: String, body: TagInputDto): TagDto = client.send(HttpMethod.Post, "${b(budgetId)}/tags", body)
    suspend fun updateTag(budgetId: String, id: String, body: TagInputDto): TagDto = client.send(HttpMethod.Patch, "${b(budgetId)}/tags/$id", body)
    suspend fun deleteTag(budgetId: String, id: String) { client.execute(HttpMethod.Delete, "${b(budgetId)}/tags/$id") }

    suspend fun categoryNote(budgetId: String, categoryId: String): CategoryNoteDto = client.get("${b(budgetId)}/categories/$categoryId/note")
    suspend fun setCategoryNote(budgetId: String, categoryId: String, note: String?): CategoryNoteDto =
        client.send(HttpMethod.Put, "${b(budgetId)}/categories/$categoryId/note", NoteInputDto(kotlinx.serialization.json.JsonPrimitive(note)))
    suspend fun applyTemplates(budgetId: String, month: String, overwrite: Boolean): TemplatesResultDto =
        client.send(HttpMethod.Post, "${b(budgetId)}/months/$month/apply-templates", ApplyTemplatesDto(overwrite))

    // Account services
    suspend fun bankSync(budgetId: String, accountId: String?): JobDto =
        client.execute(HttpMethod.Post, if (accountId != null) "${b(budgetId)}/accounts/$accountId/bank-sync" else "${b(budgetId)}/bank-sync").body()
    suspend fun job(id: String): JobDto = client.get("/v1/jobs/$id")
    suspend fun previewImport(budgetId: String, accountId: String, body: ImportRequestDto): ImportPreviewDto =
        client.send(HttpMethod.Post, "${b(budgetId)}/accounts/$accountId/import/preview", body)
    suspend fun importFile(budgetId: String, accountId: String, body: ImportRequestDto): ImportResultDto =
        client.send(HttpMethod.Post, "${b(budgetId)}/accounts/$accountId/import", body)
    suspend fun reconcileStatus(budgetId: String, accountId: String): ReconcileStatusDto = client.get("${b(budgetId)}/accounts/$accountId/reconcile")
    suspend fun reconcile(budgetId: String, accountId: String, body: ReconcileRequestDto): ReconcileResultDto =
        client.send(HttpMethod.Post, "${b(budgetId)}/accounts/$accountId/reconcile", body)

    // Plan ahead: autopilot, goals, forecast
    suspend fun autopilot(budgetId: String, month: String): AutopilotDto = client.get("${b(budgetId)}/months/$month/autopilot")
    suspend fun applyAutopilot(budgetId: String, month: String, body: ApplyAutopilotDto): AutopilotAppliedDto =
        client.send(HttpMethod.Post, "${b(budgetId)}/months/$month/autopilot", body)
    suspend fun coverOverspending(budgetId: String, month: String): CoverResultDto =
        client.execute(HttpMethod.Post, "${b(budgetId)}/months/$month/cover-overspending").body()
    suspend fun goals(budgetId: String, month: String?): GoalsDto = client.get("${b(budgetId)}/goals") { month?.let { parameter("month", it) } }
    suspend fun setGoal(budgetId: String, categoryId: String, body: GoalInputDto) {
        client.execute(HttpMethod.Put, "${b(budgetId)}/categories/$categoryId/goal") { jsonBody(body) }
    }
    suspend fun removeGoal(budgetId: String, categoryId: String) { client.execute(HttpMethod.Delete, "${b(budgetId)}/categories/$categoryId/goal") }
    suspend fun forecast(budgetId: String, days: Int, accountIds: List<String>, includeTypical: Boolean): ForecastDto =
        client.get("${b(budgetId)}/forecast") {
            parameter("days", days)
            if (accountIds.isNotEmpty()) parameter("accountIds", accountIds.joinToString(","))
            parameter("includeTypical", includeTypical)
        }

    // Reports
    suspend fun cashFlow(budgetId: String, start: String, end: String): CashFlowDto =
        client.get("${b(budgetId)}/reports/cash-flow") { parameter("start", start); parameter("end", end) }
    suspend fun spending(budgetId: String, start: String, end: String): SpendingDto =
        client.get("${b(budgetId)}/reports/spending") { parameter("start", start); parameter("end", end) }
    suspend fun netWorth(budgetId: String, months: Int): NetWorthDto = client.get("${b(budgetId)}/reports/net-worth") { parameter("months", months) }

    // ── Bank sync (SimpleFIN) ──
    suspend fun bankSyncOverview(): BankSyncOverviewDto = client.get("/v1/bank-sync")
    suspend fun connectSimpleFin(token: String): BankSyncOverviewDto = client.send(HttpMethod.Put, "/v1/bank-sync/simplefin", SetupTokenDto(token))
    suspend fun resetSimpleFin() { client.execute(HttpMethod.Delete, "/v1/bank-sync/simplefin") }
    suspend fun setSyncSchedule(hours: Int): ScheduleStateDto = client.send(HttpMethod.Put, "/v1/bank-sync/schedule", ScheduleInputDtoBankSync(hours))
    suspend fun externalAccounts(budgetId: String, refresh: Boolean): List<ExternalAccountDto> =
        client.get<ItemsDto<ExternalAccountDto>>("${b(budgetId)}/bank-sync/simplefin/accounts") { parameter("refresh", refresh) }.items
    suspend fun linkSimpleFin(budgetId: String, body: LinkRequestDto): LinkedDto = client.send(HttpMethod.Post, "${b(budgetId)}/bank-sync/simplefin/link", body)
    suspend fun startBackfill(budgetId: String, body: BackfillRequestDto): BackfillDto =
        client.send(HttpMethod.Post, "${b(budgetId)}/bank-sync/simplefin/backfill", body)
    suspend fun cancelBackfill(): BackfillCancelledDto = client.execute(HttpMethod.Delete, "/v1/bank-sync/backfill").body()
    suspend fun unlink(budgetId: String, accountId: String) { client.execute(HttpMethod.Post, "${b(budgetId)}/accounts/$accountId/unlink") }
    suspend fun bankSyncSettings(budgetId: String, accountId: String): BankSyncSettingsDto = client.get("${b(budgetId)}/accounts/$accountId/bank-sync-settings")
    suspend fun updateBankSyncSettings(budgetId: String, accountId: String, body: BankSyncSettingsDto): BankSyncSettingsDto =
        client.send(HttpMethod.Patch, "${b(budgetId)}/accounts/$accountId/bank-sync-settings", body)
}
