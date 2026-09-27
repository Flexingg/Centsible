package app.centsible.core.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import app.centsible.core.domain.AppLockSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("settings")
private val APP_LOCK = booleanPreferencesKey("app_lock")

@Singleton
class DataStoreAppLockSettings @Inject constructor(
    @ApplicationContext private val context: Context,
) : AppLockSettings {
    override val enabled: Flow<Boolean> = context.settingsDataStore.data.map { it[APP_LOCK] ?: false }

    override suspend fun setEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[APP_LOCK] = enabled }
    }
}
