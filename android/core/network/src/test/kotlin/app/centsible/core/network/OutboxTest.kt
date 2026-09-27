package app.centsible.core.network

import app.centsible.core.domain.BridgeException
import app.centsible.core.domain.Session
import app.centsible.core.model.DeviceId
import app.centsible.core.model.Member
import app.centsible.core.model.MemberId
import app.centsible.core.model.Role
import app.centsible.core.testing.InMemorySessionStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryOutbox : Outbox {
    val items = mutableListOf<PendingRequest>()
    val failed = mutableMapOf<Long, String>()
    private var seq = 0L
    override suspend fun enqueue(request: PendingRequest) { items += request.copy(id = ++seq) }
    override suspend fun next() = items.firstOrNull { it.id !in failed }
    override suspend fun remove(id: Long) { items.removeAll { it.id == id } }
    override suspend fun markFailed(id: Long, error: String) { failed[id] = error }
    override suspend fun clear() { items.clear(); failed.clear() }
}

class OutboxTest {
    private val session = Session("https://b.example.com", "a", "r", null, null, Member(MemberId("m"), "Jo", Role.Owner, false, emptyList()), DeviceId("d"))
    private val tx = File(System.getProperty("contract.fixtures") ?: "../../../contract/fixtures", "transaction.json").readText()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val notFound = headersOf(HttpHeaders.ContentType, "application/problem+json")

    @Test
    fun `queues writes while offline and replays them in order`() = runTest {
        var online = false
        val seen = mutableListOf<HttpRequestData>()
        val outbox = InMemoryOutbox()
        val api = BridgeApi(
            BridgeClient(InMemorySessionStore(session), MockEngine { req ->
                if (!online) throw java.io.IOException("offline")
                seen += req
                if (req.method.value == "DELETE") respond("""{"type":"x","title":"Not found","status":404,"code":"not_found"}""", HttpStatusCode.NotFound, notFound)
                else respond(tx, headers = json)
            }),
            outbox,
        )
        val created = runCatching { api.createTransaction("b", NewTransactionDto("11111111-1111-4111-8111-111111111111", "acc", "2026-09-01", -100)) }
        assertTrue(created.exceptionOrNull() is BridgeException.QueuedOffline)
        runCatching { api.updateTransaction("b", "t1", JsonObject(mapOf("categoryId" to JsonNull))) }
        runCatching { api.deleteTransaction("b", "gone") }
        assertEquals(3, outbox.items.size)

        assertEquals(0, api.replayOutbox()) // still offline: nothing sent, nothing lost
        assertEquals(3, outbox.items.size)

        online = true
        assertEquals(2, api.replayOutbox())
        assertEquals(listOf("POST", "PATCH", "DELETE"), seen.map { it.method.value })
        assertEquals("""{"categoryId":null}""", (seen[1].body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
        assertTrue("404 on delete means already done", outbox.items.isEmpty())
    }

    @Test
    fun `sets aside requests the bridge rejects`() = runTest {
        val outbox = InMemoryOutbox()
        outbox.enqueue(PendingRequest(method = "PATCH", path = "/v1/budgets/b/transactions/t1", body = "{}"))
        outbox.enqueue(PendingRequest(method = "PATCH", path = "/v1/budgets/b/transactions/t2", body = """{"notes":"x"}"""))
        val api = BridgeApi(
            BridgeClient(InMemorySessionStore(session), MockEngine { req ->
                if (req.url.encodedPath.endsWith("t1")) respond("""{"type":"x","title":"Bad","status":400,"code":"validation"}""", HttpStatusCode.BadRequest, notFound)
                else respond(tx, headers = json)
            }),
            outbox,
        )
        assertEquals(1, api.replayOutbox())
        assertEquals(1, outbox.failed.size)
    }
}
