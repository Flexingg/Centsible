package app.centsible.core.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.centsible.core.domain.BillReminders
import app.centsible.core.domain.ReminderSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val REMINDERS = booleanPreferencesKey("reminders")
private val REMINDER_DAYS = intPreferencesKey("reminder_days")
private val ALERTS = booleanPreferencesKey("spending_alerts")
private val REMINDERS_SENT = stringSetPreferencesKey("reminders_sent")

@Singleton
class DataStoreReminderSettings @Inject constructor(
    @ApplicationContext private val context: Context,
) : ReminderSettings {
    private val store get() = context.settingsDataStore

    override val enabled: Flow<Boolean> = store.data.map { it[REMINDERS] ?: false }
    override val daysAhead: Flow<Int> = store.data.map { it[REMINDER_DAYS] ?: 1 }

    override val alerts: Flow<Boolean> = store.data.map { it[ALERTS] ?: false }

    override suspend fun setEnabled(enabled: Boolean) { store.edit { it[REMINDERS] = enabled } }
    override suspend fun setAlerts(enabled: Boolean) { store.edit { it[ALERTS] = enabled } }
    override suspend fun setDaysAhead(days: Int) { store.edit { it[REMINDER_DAYS] = days } }
    override suspend fun sent(): Set<String> = store.data.first()[REMINDERS_SENT] ?: emptySet()

    override suspend fun markSent(keys: Collection<String>, today: LocalDate) {
        store.edit { it[REMINDERS_SENT] = BillReminders.prune((it[REMINDERS_SENT] ?: emptySet()) + keys, today) }
    }
}
