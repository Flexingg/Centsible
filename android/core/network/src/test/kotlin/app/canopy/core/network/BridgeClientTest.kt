package app.canopy.core.network

import app.canopy.core.domain.BridgeException
import app.canopy.core.domain.Session
import app.canopy.core.model.DeviceId
import app.canopy.core.model.Member
import app.canopy.core.model.MemberId
import app.canopy.core.model.Role
import app.canopy.core.testing.InMemorySessionStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeClientTest {
    private val session = Session(
        bridgeUrl = "https://budget-api.example.com",
        accessToken = "old-access",
        refreshToken = "old-refresh",
        cfAccessClientId = "cf-id",
        cfAccessClientSecret = "cf-secret",
        member = Member(MemberId("m"), "Jo", Role.Owner, false, emptyList()),
        deviceId = DeviceId("d"),
    )

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val problemHeaders = headersOf(HttpHeaders.ContentType, "application/problem+json")
    private val health = """{"status":"ok","version":"0.1.0"}"""
    private val tokens = """{"accessToken":"new-access","refreshToken":"new-refresh","expiresIn":3600,
        "member":{"id":"m","displayName":"Jo","role":"owner","disabled":false,"budgetIds":[]},
        "device":{"id":"d","name":"Pixel","platform":"android","createdAt":"2026-09-27T00:00:00Z"}}"""
    private val unauthorized = """{"type":"about:blank","title":"Unauthorized","status":401,"code":"unauthorized"}"""

    @Test
    fun `sends bearer and Cloudflare Access headers`() = runTest {
        var seen: HttpRequestData? = null
        val client = BridgeClient(InMemorySessionStore(session), MockEngine { req -> seen = req; respond(health, headers = jsonHeaders) })
        client.get<HealthDto>("/v1/health")
        assertEquals("Bearer old-access", seen!!.headers["Authorization"])
        assertEquals("cf-id", seen!!.headers["CF-Access-Client-Id"])
        assertEquals("cf-secret", seen!!.headers["CF-Access-Client-Secret"])
        assertEquals("https://budget-api.example.com/v1/health", seen!!.url.toString())
    }

    @Test
    fun `refreshes once for concurrent 401s and retries`() = runTest {
        val refreshes = AtomicInteger()
        val store = InMemorySessionStore(session)
        val client = BridgeClient(store, MockEngine { req ->
            when {
                req.url.encodedPath == "/v1/auth/refresh" -> { refreshes.incrementAndGet(); respond(tokens, headers = jsonHeaders) }
                req.headers["Authorization"] == "Bearer old-access" -> respond(unauthorized, HttpStatusCode.Unauthorized, problemHeaders)
                else -> respond(health, headers = jsonHeaders)
            }
        })
        (1..5).map { async { client.get<HealthDto>("/v1/health") } }.awaitAll()
        assertEquals(1, refreshes.get())
        assertEquals("new-access", store.current()!!.accessToken)
        assertEquals("new-refresh", store.current()!!.refreshToken)
    }

    @Test
    fun `signs out when the refresh token is rejected`() = runTest {
        val store = InMemorySessionStore(session)
        val client = BridgeClient(store, MockEngine { respond(unauthorized, HttpStatusCode.Unauthorized, problemHeaders) })
        val error = runCatching { client.get<HealthDto>("/v1/health") }.exceptionOrNull()
        assertTrue(error is BridgeException.Unauthorized)
        assertNull(store.current())
    }

    @Test
    fun `maps problem codes to typed errors`() = runTest {
        val client = BridgeClient(InMemorySessionStore(session), MockEngine {
            respond("""{"type":"about:blank","title":"Feature unavailable","status":501,"code":"feature_unavailable","detail":"nope"}""",
                HttpStatusCode.NotImplemented, problemHeaders)
        })
        val error = runCatching { client.get<HealthDto>("/v1/health") }.exceptionOrNull()
        assertTrue(error is BridgeException.FeatureUnavailable)
        assertEquals("nope", error!!.message)
    }

    @Test
    fun `treats Cloudflare origin errors as network problems`() = runTest {
        val client = BridgeClient(InMemorySessionStore(session), MockEngine { respond("<html>Bad gateway</html>", HttpStatusCode.BadGateway) })
        assertTrue(runCatching { client.get<HealthDto>("/v1/health") }.exceptionOrNull() is BridgeException.Network)
    }

    @Test
    fun `serves cached reads while offline and recovers`() = runTest {
        var online = true
        val cache = InMemoryResponseCache()
        val client = BridgeClient(InMemorySessionStore(session), MockEngine {
            if (online) respond(health, headers = jsonHeaders) else throw java.io.IOException("no route to host")
        }, cache)
        client.get<HealthDto>("/v1/health")
        assertTrue(!client.offline.value)

        online = false
        assertEquals("ok", client.get<HealthDto>("/v1/health").status)
        assertTrue(client.offline.value)
        // Nothing cached for this URL: the network error surfaces.
        assertTrue(runCatching { client.get<HealthDto>("/v1/other") }.exceptionOrNull() is BridgeException.Network)

        online = true
        client.get<HealthDto>("/v1/health")
        assertTrue(!client.offline.value)
    }
}
