package app.centsible.core.engine.bridge

import app.centsible.core.domain.Session
import app.centsible.core.domain.TransactionQuery
import app.centsible.core.model.AccountId
import app.centsible.core.model.BatchChange
import app.centsible.core.model.BudgetId
import app.centsible.core.model.DeviceId
import app.centsible.core.model.Member
import app.centsible.core.model.MemberId
import app.centsible.core.model.Role
import app.centsible.core.model.TransactionId
import app.centsible.core.network.BridgeApi
import app.centsible.core.network.BridgeClient
import app.centsible.core.network.TransactionToolsApi
import app.centsible.core.testing.InMemorySessionStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeTransactionToolsTest {
    private val fixtures = File(System.getProperty("contract.fixtures") ?: "../../../contract/fixtures")
    private val session = Session("https://b.example.com", "a", "r", null, null, Member(MemberId("m"), "Jo", Role.Owner, false, emptyList()), DeviceId("d"))
    private val requests = mutableListOf<HttpRequestData>()
    private var writes = 0

    private fun client(respondWith: (HttpRequestData) -> String) = BridgeClient(InMemorySessionStore(session), MockEngine { req ->
        requests += req
        respond(respondWith(req), headers = headersOf(HttpHeaders.ContentType, "application/json"))
    })

    private fun tools(respondWith: (HttpRequestData) -> String) = BridgeTransactionTools(TransactionToolsApi(client(respondWith))) { writes++ }

    private fun fixture(name: String) = File(fixtures, "$name.json").readText()
    private fun body(req: HttpRequestData) = Json.parseToJsonElement((req.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject

    @Test
    fun `maps the recorded review inbox`() = runTest {
        val inbox = tools { fixture("review-inbox") }.inbox(BudgetId("b"), 20)
        assertEquals(1, inbox.items.size)
        assertEquals(inbox.items.size, inbox.total)
        assertEquals("After Catch Up", inbox.items[0].payeeName)
        assertEquals("/v1/budgets/b/review", requests.single().url.encodedPath)
        assertEquals("20", requests.single().url.parameters["limit"])
    }

    @Test
    fun `clearing a category in bulk sends an explicit null`() = runTest {
        val r = tools { """{"updated":2,"deleted":0,"skipped":[{"id":"t3","reason":"split"}]}""" }
            .batch(BudgetId("b"), listOf(TransactionId("t1"), TransactionId("t2"), TransactionId("t3")), BatchChange.Category(null))
        val sent = body(requests.single())
        assertEquals(JsonNull, sent["set"]!!.jsonObject["categoryId"])
        assertEquals(3, (sent["ids"] as kotlinx.serialization.json.JsonArray).size)
        assertEquals(2, r.updated)
        assertEquals("split", r.skipped.single().reason)
        assertEquals(1, writes) // screens reload after a bulk edit
    }

    @Test
    fun `deleting in bulk sends delete, not set`() = runTest {
        tools { """{"updated":0,"deleted":1,"skipped":[]}""" }.batch(BudgetId("b"), listOf(TransactionId("t1")), BatchChange.Delete)
        val sent = body(requests.single())
        assertEquals("true", sent["delete"].toString())
        assertTrue("set" !in sent)
    }

    @Test
    fun `reads running balances for one account`() = runTest {
        val engine = BridgeBudgetEngine(BridgeApi(client { fixture("transactions-running-balance") }))
        val page = engine.transactions(BudgetId("b"), TransactionQuery(accountId = AccountId("acc"), limit = 3))
        val balances = page.runningBalances!!
        assertEquals(page.items.size, balances.size)
        // Each row's balance is the one above it, less that row.
        for (i in 1 until balances.size) assertEquals(balances[i - 1].minor - page.items[i - 1].amount.minor, balances[i].minor)
    }
}

class AppearanceColorTest {
    @Test
    fun `colors go to the bridge and back`() {
        assertEquals(0xFF7FD1A8L, parseColor("#7FD1A8"))
        assertEquals("#7FD1A8", formatColor(0xFF7FD1A8L))
        assertEquals(null, parseColor("#123"))
    }
}
