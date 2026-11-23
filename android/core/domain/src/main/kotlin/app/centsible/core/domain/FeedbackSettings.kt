package app.centsible.core.domain

import kotlinx.coroutines.flow.Flow

/** On-device preferences for expressive haptics and subtle tactile UI sounds. */
interface FeedbackSettings {
    val hapticsEnabled: Flow<Boolean>
    val soundsEnabled: Flow<Boolean>
    suspend fun setHapticsEnabled(enabled: Boolean)
    suspend fun setSoundsEnabled(enabled: Boolean)
}
