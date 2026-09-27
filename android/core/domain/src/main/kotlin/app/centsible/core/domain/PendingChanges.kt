package app.centsible.core.domain

import kotlinx.coroutines.flow.StateFlow

/** Changes made offline that haven't reached the bridge yet. */
interface PendingChanges {
    val pending: StateFlow<Int>
    /** Changes the bridge rejected on replay (e.g. the transaction was deleted elsewhere). */
    val failed: StateFlow<Int>
    suspend fun syncNow()
    suspend fun discardFailed()
}
