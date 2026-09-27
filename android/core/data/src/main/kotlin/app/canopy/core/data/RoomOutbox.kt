package app.canopy.core.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import app.canopy.core.domain.PendingChanges
import app.canopy.core.network.BridgeApi
import app.canopy.core.network.Outbox
import app.canopy.core.network.PendingRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Entity(tableName = "outbox")
data class OutboxEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val method: String,
    val path: String,
    val body: String?,
    val createdAt: Long,
    val error: String? = null,
)

@Dao
interface OutboxDao {
    @Insert suspend fun insert(e: OutboxEntry): Long
    @Query("SELECT * FROM outbox WHERE error IS NULL ORDER BY id LIMIT 1") suspend fun next(): OutboxEntry?
    @Query("DELETE FROM outbox WHERE id = :id") suspend fun delete(id: Long)
    @Query("UPDATE outbox SET error = :error WHERE id = :id") suspend fun fail(id: Long, error: String)
    @Query("DELETE FROM outbox") suspend fun clear()
    @Query("DELETE FROM outbox WHERE error IS NOT NULL") suspend fun clearFailed()
    @Query("SELECT COUNT(*) FROM outbox WHERE error IS NULL") fun pendingCount(): kotlinx.coroutines.flow.Flow<Int>
    @Query("SELECT COUNT(*) FROM outbox WHERE error IS NOT NULL") fun failedCount(): kotlinx.coroutines.flow.Flow<Int>
}

/** Its own database: the response cache may be wiped freely, queued changes may not. */
@Database(entities = [OutboxEntry::class], version = 1, exportSchema = false)
abstract class OutboxDatabase : RoomDatabase() {
    abstract fun outbox(): OutboxDao
}

/** Durable, encrypted queue of writes made while the bridge was unreachable. */
@Singleton
class RoomOutbox @Inject constructor(@ApplicationContext context: Context) : Outbox {
    val dao: OutboxDao = Room.databaseBuilder(context, OutboxDatabase::class.java, "canopy-outbox.db").build().outbox()
    private val cipher = KeystoreCipher("canopy.outbox")

    override suspend fun enqueue(request: PendingRequest) = withContext(Dispatchers.IO) {
        dao.insert(OutboxEntry(method = request.method, path = request.path, body = request.body?.let(cipher::encrypt), createdAt = request.createdAt))
        Unit
    }

    override suspend fun next(): PendingRequest? = withContext(Dispatchers.IO) {
        dao.next()?.let { PendingRequest(it.id, it.method, it.path, it.body?.let(cipher::decrypt), it.createdAt) }
    }

    override suspend fun remove(id: Long) = withContext(Dispatchers.IO) { dao.delete(id) }
    override suspend fun markFailed(id: Long, error: String) = withContext(Dispatchers.IO) { dao.fail(id, error) }
    override suspend fun clear() = withContext(Dispatchers.IO) { dao.clear() }
}

/**
 * Sends queued changes whenever there's a chance they'll go through: at start-up, when
 * reads start succeeding again, and every 30 seconds while anything is waiting.
 */
@Singleton
class OutboxSync @Inject constructor(
    private val outbox: RoomOutbox,
    // Provider: BridgeApi depends on the outbox, so resolve it lazily.
    private val api: Provider<BridgeApi>,
    private val changes: NotifyingBudgetEngine,
) : PendingChanges {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    override val pending: StateFlow<Int> = outbox.dao.pendingCount().stateIn(scope, SharingStarted.Eagerly, 0)
    override val failed: StateFlow<Int> = outbox.dao.failedCount().stateIn(scope, SharingStarted.Eagerly, 0)
    private var started = false

    fun start(offline: StateFlow<Boolean>) {
        if (started) return
        started = true
        scope.launch { offline.collect { if (!it) syncNow() } }
        scope.launch {
            while (true) {
                pending.first { it > 0 }
                syncNow()
                delay(30_000)
            }
        }
    }

    override suspend fun syncNow() {
        lock.withLock {
            if (runCatching { api.get().replayOutbox() }.getOrDefault(0) > 0) changes.notifyChanged()
        }
    }

    override suspend fun discardFailed() = withContext(Dispatchers.IO) { outbox.dao.clearFailed() }
}
