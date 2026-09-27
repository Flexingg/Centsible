package app.centsible.core.domain

import kotlinx.coroutines.flow.Flow

/** "Lock with fingerprint or PIN": a per-device choice, kept on the phone. */
interface AppLockSettings {
    val enabled: Flow<Boolean>
    suspend fun setEnabled(enabled: Boolean)
}

/**
 * When to ask again. The phone just left the app for a moment (a 2FA code, a photo of
 * a receipt) shouldn't mean unlocking again; a minute away should.
 */
object AppLockPolicy {
    const val GRACE_MILLIS = 60_000L

    fun shouldLock(enabled: Boolean, backgroundedAt: Long?, now: Long): Boolean =
        enabled && (backgroundedAt == null || now - backgroundedAt >= GRACE_MILLIS)
}
