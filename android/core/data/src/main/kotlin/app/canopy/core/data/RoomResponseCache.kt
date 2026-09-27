package app.canopy.core.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import app.canopy.core.network.ResponseCache
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Entity(tableName = "response_cache")
data class CachedResponse(@PrimaryKey val key: String, val body: String, val savedAt: Long)

@Dao
interface ResponseCacheDao {
    @Query("SELECT * FROM response_cache WHERE `key` = :key")
    suspend fun get(key: String): CachedResponse?

    @Upsert
    suspend fun put(entry: CachedResponse)

    @Query("DELETE FROM response_cache")
    suspend fun clear()

    @Query("DELETE FROM response_cache WHERE savedAt < :before")
    suspend fun prune(before: Long)
}

@Database(entities = [CachedResponse::class], version = 1, exportSchema = false)
abstract class CanopyDatabase : RoomDatabase() {
    abstract fun responseCache(): ResponseCacheDao
}

/**
 * Offline copy of the last successful bridge reads, so the app still opens with your
 * budget when the tunnel is down. Bodies are encrypted with a Keystore key; the whole
 * cache is dropped on sign-out. Phase 2 grows this into a real cache + write outbox.
 */
@Singleton
class RoomResponseCache @Inject constructor(@ApplicationContext context: Context) : ResponseCache {
    private val dao = Room.databaseBuilder(context, CanopyDatabase::class.java, "canopy.db")
        .fallbackToDestructiveMigration(dropAllTables = true) // it's only a cache
        .build()
        .responseCache()
    private val cipher = KeystoreCipher("canopy.cache")
    private var pruned = false

    override suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        dao.get(key)?.let { runCatching { cipher.decrypt(it.body) }.getOrNull() }
    }

    override suspend fun put(key: String, body: String) = withContext(Dispatchers.IO) {
        if (!pruned) {
            dao.prune(System.currentTimeMillis() - MAX_AGE_MS)
            pruned = true
        }
        dao.put(CachedResponse(key, cipher.encrypt(body), System.currentTimeMillis()))
    }

    override suspend fun clear() = withContext(Dispatchers.IO) { dao.clear() }

    private companion object {
        const val MAX_AGE_MS = 30L * 24 * 3600 * 1000
    }
}
