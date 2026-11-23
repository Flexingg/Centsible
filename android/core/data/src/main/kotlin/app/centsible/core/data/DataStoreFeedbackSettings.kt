package app.centsible.core.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import app.centsible.core.domain.FeedbackSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val FEEDBACK_HAPTICS = booleanPreferencesKey("feedback_haptics")
private val FEEDBACK_SOUNDS = booleanPreferencesKey("feedback_sounds")

@Singleton
class DataStoreFeedbackSettings @Inject constructor(
    @ApplicationContext private val context: Context,
) : FeedbackSettings {
    override val hapticsEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[FEEDBACK_HAPTICS] ?: true }
    override val soundsEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[FEEDBACK_SOUNDS] ?: true }

    override suspend fun setHapticsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[FEEDBACK_HAPTICS] = enabled }
    }

    override suspend fun setSoundsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[FEEDBACK_SOUNDS] = enabled }
    }
}
