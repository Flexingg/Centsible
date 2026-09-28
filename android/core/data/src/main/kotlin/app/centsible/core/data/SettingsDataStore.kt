package app.centsible.core.data

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

/** Per-device preferences (app lock, reminders). One DataStore file per app process. */
internal val Context.settingsDataStore by preferencesDataStore("settings")
