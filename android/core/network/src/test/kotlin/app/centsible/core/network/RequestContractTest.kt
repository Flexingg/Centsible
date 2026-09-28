package app.centsible.core.network

import app.centsible.core.domain.Session
import app.centsible.core.model.DeviceId
import app.centsible.core.model.Member
import app.centsible.core.model.MemberId
import app.centsible.core.model.Role
import app.centsible.core.testing.InMemorySessionStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Every request body the app sends, checked against contract/openapi.yaml.
 *
 * The bridge's contract suite only checks responses, so a request the app encodes
 * wrongly (a required field silently dropped by the serializer) passed every test and
 * failed on the first real pairing. This drives the real BridgeApi calls through a mock
 * engine and validates what actually goes over the wire.
 */
class RequestContractTest {
    private val session = Session(
        bridgeUrl = "https://budget-api.example.com",
        accessToken = "a",
        refreshToken = "r",
        cfAccessClientId = null,
        cfAccessClientSecret = null,
        member = Member(MemberId("m"), "Jo", Role.Owner, false, emptyList()),
        deviceId = DeviceId("d"),
    )

    private data class Sent(val method: String, val path: String, val body: String?)

    @Suppress("UNCHECKED_CAST")
    private val spec = Yaml().load<Map<String, Any?>>(File(System.getProperty("contract.openapi") ?: "../../../contract/openapi.yaml").readText())

    @Test
    fun `every request body matches the contract`() = runTest {
        val sent = mutableListOf<Sent>()
        val engine = MockEngine { req ->
            val body = (req.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()
            sent += Sent(req.method.value, req.url.encodedPath, body)
            // Responses don't matter here; decoding failures after the send are ignored.
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = BridgeClient(InMemorySessionStore(session), engine)
        val api = BridgeApi(client)
        val planning = PlanningApi(client)
        val b = "budget-1"
        suspend fun call(block: suspend () -> Unit) { runCatching { block() } }

        // The app builds this exactly like this, relying on the default platform.
        call { api.pair("https://budget-api.example.com", null, null, PairRequestDto("ABCD-EFGH", "Pixel 9")) }
        call { api.claim("https://budget-api.example.com", null, null, SetupClaimDto("ABCD-EFGH", "Jo", "Pixel 9", actualPassword = "household-pass")) }
        call { api.claim("https://budget-api.example.com", null, null, SetupClaimDto("ABCD-EFGH", "Jo", "Pixel 9")) } // Actual already signed in
        call { api.createBudget("Our Budget") }
        call { api.createMember(NewMemberDto("Sam", "member")) }
        call { api.setMemberBudgets("m2", listOf(b)) }
        call { api.createTransaction(b, NewTransactionDto("t1", "acc", "2026-09-27", -1234, payeeName = "Cafe", categoryId = "c1")) }
        call {
            api.createTransaction(b, NewTransactionDto("t2", "acc", "2026-09-27", -5000,
                subtransactions = listOf(NewSplitDto(-3000, "c1"), NewSplitDto(-2000, "c2", "note"))))
        }
        call { api.updateTransaction(b, "t1", buildJsonObject { put("notes", JsonNull); put("amount", -99) }) }
        call { api.createAccount(b, NewAccountDto("Savings")) }
        call { api.updateAccount(b, "acc", AccountPatchDto("Joint")) }
        call { api.closeAccount(b, "acc", CloseAccountDto(transferAccountId = "acc2")) }
        call { api.createCategory(b, NewCategoryDto("Pets", "g1")) }
        call { api.updateCategory(b, "c1", CategoryPatchDto(hidden = true)) }
        call { api.createGroup(b, NewGroupDto("Fun")) }
        call { api.updateGroup(b, "g1", GroupPatchDto(name = "Joy")) }
        call { api.updateCategoryBudget(b, "2026-09", "c1", CategoryBudgetPatchDto(budgeted = 40000)) }
        call { api.moveMoney(b, "2026-09", MoneyTransferDto("to-budget", "c1", 1000), "key") }
        call { api.hold(b, "2026-09", 5000) }
        call { planning.createSchedule(b, ScheduleInputDto(name = "Rent", payeeName = "Landlord", accountId = "acc", amount = -150000, amountOp = "is",
            recurrence = RecurrenceDto(frequency = "monthly", start = "2026-10-01"), postsTransaction = false)) }
        call { planning.updateSchedule(b, "s1", ScheduleInputDto(amount = -160000)) }
        val rule = RuleInputDto(
            conditions = listOf(RuleItemDto("payee", "is", JsonPrimitive("p1"), "id")),
            actions = listOf(RuleItemDto("category", "set", JsonPrimitive("c1"), "id")),
        )
        call { planning.createRule(b, rule) }
        call { planning.updateRule(b, "r1", rule) }
        call { planning.renamePayee(b, "p1", "Blue Bottle") }
        call { planning.mergePayees(b, "p1", listOf("p2")) }
        call { planning.createTag(b, TagInputDto("groceries", "#00AA00")) }
        call { planning.updateTag(b, "tag1", TagInputDto(color = "#112233")) }
        call { planning.setCategoryNote(b, "c1", "#template 300") }
        call { planning.setCategoryNote(b, "c1", null) } // clearing still sends "note": null
        call { planning.applyTemplates(b, "2026-09", overwrite = false) }
        val file = ImportRequestDto("visa.csv", "ZGF0ZQ==", ImportOptionsDto(dateFormat = "MM/dd/yyyy", csvMapping = CsvMappingDto(date = "Date", amount = "Amount")))
        call { planning.previewImport(b, "acc", file) }
        call { planning.importFile(b, "acc", file) }
        call { planning.reconcile(b, "acc", ReconcileRequestDto(-58234, createAdjustment = true)) }
        call { planning.connectSimpleFin("aHR0cHM6Ly9icmlkZ2Uuc2ltcGxlZmluLm9yZy9jbGFpbS9kZW1v") }
        call { planning.setSyncSchedule(6) }
        call { planning.linkSimpleFin(b, LinkRequestDto("SF-CHK", offBudget = false)) }
        call { planning.linkSimpleFin(b, LinkRequestDto("SF-CARD", accountId = "acc")) }
        val mapping = FieldMappingDto("postedDate", "notes", "payeeName")
        call { planning.updateBankSyncSettings(b, "acc", BankSyncSettingsDto(true, false, true, true, false, SyncMappingsDto(mapping, mapping))) }
        call { planning.startBackfill(b, BackfillRequestDto(years = 10)) }
        call { planning.applyAutopilot(b, "2026-09", ApplyAutopilotDto(3)) }
        call { planning.applyAutopilot(b, "2026-09", ApplyAutopilotDto(12, listOf("c1"))) }
        call { planning.setGoal(b, "c1", GoalInputDto("by", 120000, "2027-03")) }
        call { planning.setGoal(b, "c1", GoalInputDto("balance", 500000)) }
        call { planning.dismissSubscription(b, "p1") }
        val server = ServerApi(client)
        call { server.backupSettings(BackupSettingsDto(intervalHours = 24, keep = 7)) }
        call { server.backupSettings(BackupSettingsDto(keep = 3)) }
        call { server.restore("20260928T120000Z", b) }
        call { planning.startBackfill(b, BackfillRequestDto(years = 2, accountIds = listOf("acc"))) }

        val withBodies = sent.filter { it.body != null }
        assertTrue("expected every write to be captured, got ${withBodies.size}", withBodies.size >= 30)
        val problems = withBodies.flatMap { s ->
            val schema = requestSchema(s.method, s.path) ?: return@flatMap listOf("${s.method} ${s.path}: no requestBody in the contract")
            validate(BridgeJson.parseToJsonElement(s.body!!), schema, "${s.method} ${s.path} body")
        }
        assertEquals(emptyList<String>(), problems)
    }

    // --- minimal OpenAPI lookup and JSON Schema checks (what the contract uses) ---

    @Suppress("UNCHECKED_CAST")
    private fun requestSchema(method: String, path: String): Map<String, Any?>? {
        val paths = spec["paths"] as Map<String, Map<String, Any?>>
        val template = paths.keys.firstOrNull { t ->
            val a = t.trim('/').split('/'); val c = path.trim('/').split('/')
            a.size == c.size && a.zip(c).all { (x, y) -> x.startsWith("{") || x == y }
        } ?: return null
        val op = paths[template]!![method.lowercase()] as? Map<String, Any?> ?: return null
        val content = (op["requestBody"] as? Map<String, Any?>)?.get("content") as? Map<String, Any?> ?: return null
        return resolve((content["application/json"] as Map<String, Any?>)["schema"] as Map<String, Any?>)
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolve(schema: Map<String, Any?>): Map<String, Any?> {
        val ref = schema["\$ref"] as? String ?: return schema
        val name = ref.substringAfterLast('/')
        return resolve(((spec["components"] as Map<String, Any?>)["schemas"] as Map<String, Any?>)[name] as Map<String, Any?>)
    }

    @Suppress("UNCHECKED_CAST")
    private fun validate(value: JsonElement, raw: Map<String, Any?>, at: String): List<String> {
        val schema = resolve(raw)
        val types = when (val t = schema["type"]) { is String -> listOf(t); is List<*> -> t.map { it.toString() }; else -> emptyList() }
        (schema["oneOf"] ?: schema["anyOf"])?.let { options ->
            val results = (options as List<Map<String, Any?>>).map { validate(value, it, at) }
            return if (results.any { it.isEmpty() }) emptyList() else results.minBy { it.size }
        }
        if (types.isNotEmpty() && types.none { matches(value, it) }) return listOf("$at: expected $types, got $value")
        (schema["enum"] as? List<*>)?.let { e -> if (value is JsonPrimitive && value !is JsonNull && e.none { it.toString() == value.content }) return listOf("$at: $value not in $e") }
        return when (value) {
            is JsonObject -> {
                val props = schema["properties"] as? Map<String, Map<String, Any?>> ?: emptyMap()
                val missing = (schema["required"] as? List<String> ?: emptyList()).filter { it !in value }.map { "$at: missing required \"$it\"" }
                val extra = if (schema["additionalProperties"] == false) (value.keys - props.keys).map { "$at: unknown field \"$it\"" } else emptyList()
                missing + extra + value.entries.flatMap { (k, v) -> props[k]?.let { validate(v, it, "$at.$k") } ?: emptyList() }
            }
            is JsonArray -> (schema["items"] as? Map<String, Any?>)?.let { items -> value.flatMapIndexed { i, v -> validate(v, items, "$at[$i]") } } ?: emptyList()
            else -> emptyList()
        }
    }

    private fun matches(v: JsonElement, type: String) = when (type) {
        "null" -> v is JsonNull
        "object" -> v is JsonObject
        "array" -> v is JsonArray
        "string" -> v is JsonPrimitive && v !is JsonNull && v.isString
        "integer" -> v is JsonPrimitive && !v.isString && v.longOrNull != null
        "number" -> v is JsonPrimitive && !v.isString && v.content.toDoubleOrNull() != null
        "boolean" -> v is JsonPrimitive && !v.isString && v.booleanOrNull != null
        else -> true
    }
}
