package app.canopy.core.network

/** Stores the last successful body per request URL for offline reads. */
interface ResponseCache {
    suspend fun get(key: String): String?
    suspend fun put(key: String, body: String)
    suspend fun clear()
}

class InMemoryResponseCache : ResponseCache {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
    override suspend fun get(key: String) = map[key]
    override suspend fun put(key: String, body: String) { map[key] = body }
    override suspend fun clear() = map.clear()
}
