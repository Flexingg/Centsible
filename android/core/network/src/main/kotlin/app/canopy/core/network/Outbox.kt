package app.canopy.core.network

/** A write that couldn't reach the bridge, kept verbatim so it can be replayed. */
data class PendingRequest(
    val id: Long = 0,
    val method: String,
    val path: String,
    val body: String?,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Durable FIFO of writes made offline. */
interface Outbox {
    suspend fun enqueue(request: PendingRequest)
    suspend fun next(): PendingRequest?
    suspend fun remove(id: Long)
    suspend fun markFailed(id: Long, error: String)
    suspend fun clear()
}
