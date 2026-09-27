package app.canopy.core.network

import app.canopy.core.domain.BridgeException
import app.canopy.core.domain.SessionStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * HTTP transport to the bridge: base URL and tokens from the stored session, Cloudflare
 * Access headers, single-flight token refresh, and problem+json → [BridgeException].
 */
class BridgeClient(
    private val sessions: SessionStore,
    engine: HttpClientEngine,
    private val cache: ResponseCache? = null,
) {
    private val offlineState = MutableStateFlow(false)

    /** True while reads are being served from the offline cache. */
    val offline: StateFlow<Boolean> = offlineState.asStateFlow()

    val json = Json {
        ignoreUnknownKeys = true // newer bridges may add fields
        coerceInputValues = true // unknown enum-ish values fall back to defaults
        explicitNulls = false
    }

    @PublishedApi
    internal val http = HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
    }

    private val refreshLock = Mutex()

    /** Authenticated request. Retries once after refreshing an expired access token. */
    suspend fun execute(method: HttpMethod, path: String, configure: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
        val session = sessions.current() ?: throw BridgeException.Unauthorized("This device is not paired")
        var response = send(session.bridgeUrl, method, path, session.accessToken, session.cfAccessClientId, session.cfAccessClientSecret, configure)
        if (response.status == HttpStatusCode.Unauthorized && refresh(session.accessToken)) {
            val renewed = sessions.current() ?: throw BridgeException.Unauthorized("Signed out")
            response = send(renewed.bridgeUrl, method, path, renewed.accessToken, renewed.cfAccessClientId, renewed.cfAccessClientSecret, configure)
        }
        return response.also { ensureSuccess(it) }
    }

    /** Unauthenticated request to an explicit bridge (pairing happens before a session exists). */
    suspend fun executeAnonymous(
        bridgeUrl: String,
        method: HttpMethod,
        path: String,
        cfId: String?,
        cfSecret: String?,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = send(bridgeUrl, method, path, null, cfId, cfSecret, configure).also { ensureSuccess(it) }

    suspend inline fun <reified T> get(path: String, noinline configure: HttpRequestBuilder.() -> Unit = {}): T =
        json.decodeFromString(getText(path, configure))

    /**
     * GET with an offline fallback: successful responses are cached; when the bridge
     * can't be reached, the last cached response for the same URL is returned instead.
     */
    suspend fun getText(path: String, configure: HttpRequestBuilder.() -> Unit = {}): String {
        val session = sessions.current() ?: throw BridgeException.Unauthorized("This device is not paired")
        val key = HttpRequestBuilder().apply {
            url(session.bridgeUrl.trimEnd('/') + path)
            configure()
        }.url.buildString()
        return try {
            execute(HttpMethod.Get, path, configure).bodyAsText().also {
                cache?.put(key, it)
                offlineState.value = false
            }
        } catch (e: BridgeException.Network) {
            val cached = cache?.get(key) ?: throw e
            offlineState.value = true
            cached
        }
    }

    suspend inline fun <reified B : Any, reified T> send(method: HttpMethod, path: String, body: B, noinline configure: HttpRequestBuilder.() -> Unit = {}): T =
        execute(method, path) {
            contentType(ContentType.Application.Json)
            setBody(body)
            configure(this)
        }.body()

    /**
     * Refreshes tokens once for all concurrent callers that saw the same expired token.
     * Returns false (and signs the device out) when the bridge rejects the refresh token.
     */
    private suspend fun refresh(expiredAccessToken: String): Boolean = refreshLock.withLock {
        val session = sessions.current() ?: return false
        if (session.accessToken != expiredAccessToken) return true // someone else already refreshed
        val res = send(session.bridgeUrl, HttpMethod.Post, "/v1/auth/refresh", null, session.cfAccessClientId, session.cfAccessClientSecret) {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequestDto(session.refreshToken))
        }
        return when {
            res.status.isSuccess() -> {
                val tokens = res.body<TokenResponseDto>()
                sessions.updateTokens(tokens.accessToken, tokens.refreshToken)
                true
            }
            res.status == HttpStatusCode.Unauthorized -> {
                sessions.clear()
                cache?.clear()
                false
            }
            else -> false
        }
    }

    private suspend fun send(
        baseUrl: String,
        method: HttpMethod,
        path: String,
        accessToken: String?,
        cfId: String?,
        cfSecret: String?,
        configure: HttpRequestBuilder.() -> Unit,
    ): HttpResponse = try {
        http.request {
            this.method = method
            url(baseUrl.trimEnd('/') + path)
            accessToken?.let { header("Authorization", "Bearer $it") }
            if (cfId != null && cfSecret != null) {
                header("CF-Access-Client-Id", cfId)
                header("CF-Access-Client-Secret", cfSecret)
            }
            configure(this)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw BridgeException.Network(e.message ?: "Network error", e)
    }

    private suspend fun ensureSuccess(res: HttpResponse) {
        if (res.status.isSuccess()) return
        val text = runCatching { res.bodyAsText() }.getOrDefault("")
        val problem = runCatching { json.decodeFromString(ProblemDto.serializer(), text) }.getOrNull()
        val message = problem?.detail ?: problem?.title?.takeIf { it.isNotBlank() } ?: "HTTP ${res.status.value}"
        throw when (problem?.code ?: "") {
            "unauthorized" -> BridgeException.Unauthorized(message)
            "forbidden" -> BridgeException.Forbidden(message)
            "not_found" -> BridgeException.NotFound(message)
            "validation" -> BridgeException.Validation(message)
            "conflict" -> BridgeException.Conflict(message)
            "rate_limited" -> BridgeException.RateLimited(message)
            "feature_unavailable" -> BridgeException.FeatureUnavailable(message)
            "actual_unavailable" -> BridgeException.ActualUnavailable(message)
            else -> when (res.status.value) {
                401 -> BridgeException.Unauthorized(message)
                403 -> BridgeException.Forbidden(message)
                // Cloudflare returns HTML error pages when the tunnel or origin is down.
                502, 503, 504, 530 -> BridgeException.Network("Bridge unreachable (${res.status.value})")
                else -> BridgeException.Unexpected(message)
            }
        }
    }
}

/** Adds a JSON body inside a request builder. */
inline fun <reified B : Any> HttpRequestBuilder.jsonBody(body: B) {
    contentType(ContentType.Application.Json)
    setBody(body)
}
