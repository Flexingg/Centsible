package app.centsible.core.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.centsible.core.assistant.ApiKeyStore
import app.centsible.core.assistant.AssistantEffort
import app.centsible.core.assistant.AssistantModel
import app.centsible.core.assistant.AssistantSettings
import app.centsible.core.assistant.AssistantSettingsStore
import app.centsible.core.assistant.ChangeNotifier
import app.centsible.core.assistant.ClaudeClient
import app.centsible.core.assistant.Conversation
import app.centsible.core.assistant.ConversationStore
import app.centsible.core.assistant.ConversationSummary
import app.centsible.core.assistant.MemoryNote
import app.centsible.core.assistant.MemoryStore
import app.centsible.core.assistant.SdkClaudeClient
import app.centsible.core.assistant.ToolGroup
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.assistantDataStore by preferencesDataStore("assistant")
private val KEY = stringPreferencesKey("api_key.v1")
private val MEMORY = stringPreferencesKey("memory.v1")
private val MODEL = stringPreferencesKey("model")
private val EFFORT = stringPreferencesKey("effort")
private val WRITABLE = stringPreferencesKey("writable")
private val CODE = booleanPreferencesKey("code_execution")
private val WEB = booleanPreferencesKey("web_search")
private val REMEMBER = booleanPreferencesKey("memory_on")

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** The person's Anthropic key, encrypted with a key that never leaves this phone's Keystore. */
@Singleton
class EncryptedApiKeyStore @Inject constructor(@ApplicationContext private val context: Context) : ApiKeyStore {
    private val crypto = KeystoreCipher("centsible.assistant.key")

    override val hasKey: Flow<Boolean> = context.assistantDataStore.data.map { it[KEY] != null }

    override suspend fun get(): String? = context.assistantDataStore.data.first()[KEY]?.let { runCatching { crypto.decrypt(it) }.getOrNull() }

    override suspend fun set(key: String) {
        val encrypted = crypto.encrypt(key.trim())
        context.assistantDataStore.edit { it[KEY] = encrypted }
    }

    override suspend fun clear() {
        context.assistantDataStore.edit { it.remove(KEY) }
    }
}

@Singleton
class DataStoreAssistantSettings @Inject constructor(@ApplicationContext private val context: Context) : AssistantSettingsStore {
    override val settings: Flow<AssistantSettings> = context.assistantDataStore.data.map { p ->
        AssistantSettings(
            model = AssistantModel.of(p[MODEL]),
            effort = AssistantEffort.of(p[EFFORT]),
            writable = p[WRITABLE].orEmpty().split(',').mapNotNull { ToolGroup.of(it) }.toSet(),
            codeExecution = p[CODE] ?: true,
            webSearch = p[WEB] ?: false,
            memory = p[REMEMBER] ?: true,
        )
    }

    override suspend fun update(change: (AssistantSettings) -> AssistantSettings) {
        val next = change(settings.first())
        context.assistantDataStore.edit { p ->
            p[MODEL] = next.model.id
            p[EFFORT] = next.effort.wire
            p[WRITABLE] = next.writable.joinToString(",") { it.key }
            p[CODE] = next.codeExecution
            p[WEB] = next.webSearch
            p[REMEMBER] = next.memory
        }
    }
}

/** The assistant's notes between chats, encrypted like the key. */
@Singleton
class EncryptedMemoryStore @Inject constructor(@ApplicationContext private val context: Context) : MemoryStore {
    private val crypto = KeystoreCipher("centsible.assistant.memory")
    private val serializer = ListSerializer(MemoryNote.serializer())

    override val notes: Flow<List<MemoryNote>> = context.assistantDataStore.data.map { p ->
        p[MEMORY]?.let { runCatching { json.decodeFromString(serializer, crypto.decrypt(it)) }.getOrNull() }.orEmpty()
    }

    override suspend fun all(): List<MemoryNote> = notes.first()

    private suspend fun write(list: List<MemoryNote>) {
        val encrypted = crypto.encrypt(json.encodeToString(serializer, list))
        context.assistantDataStore.edit { it[MEMORY] = encrypted }
    }

    override suspend fun put(note: MemoryNote) = write(all().filter { it.id != note.id } + note)
    override suspend fun delete(id: String) = write(all().filter { it.id != id })
    override suspend fun clear() {
        context.assistantDataStore.edit { it.remove(MEMORY) }
    }
}

/**
 * Chats, one encrypted file each in the app's no-backup folder. The list is read once and
 * kept up to date in memory as chats are saved.
 */
@Singleton
class FileConversationStore @Inject constructor(@ApplicationContext private val context: Context) : ConversationStore {
    private val crypto = KeystoreCipher("centsible.assistant.chats")
    private val dir get() = File(context.noBackupFilesDir, "assistant/chats").apply { mkdirs() }
    private val lock = Mutex()
    private val index = MutableStateFlow<Map<String, ConversationSummary>?>(null)

    override val summaries: Flow<List<ConversationSummary>> = index
        .onStart { load() }
        .map { m -> m?.values.orEmpty().sortedByDescending { it.updatedAt } }

    private fun file(id: String) = File(dir, id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".chat")

    private suspend fun load() = lock.withLock {
        if (index.value != null) return@withLock
        index.value = withContext(Dispatchers.IO) {
            dir.listFiles { f -> f.extension == "chat" }.orEmpty().mapNotNull { f -> read(f)?.let { it.id to it.summary() } }.toMap()
        }
    }

    private fun read(f: File): Conversation? = runCatching { json.decodeFromString(Conversation.serializer(), crypto.decrypt(f.readText())) }.getOrNull()

    private fun Conversation.summary() = ConversationSummary(id, title, updatedAt, awaitingApproval)

    override suspend fun get(id: String): Conversation? = withContext(Dispatchers.IO) { file(id).takeIf { it.exists() }?.let(::read) }

    override suspend fun save(conversation: Conversation) {
        load()
        withContext(Dispatchers.IO) {
            val target = file(conversation.id)
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(crypto.encrypt(json.encodeToString(Conversation.serializer(), conversation)))
            tmp.renameTo(target)
        }
        index.value = index.value.orEmpty() + (conversation.id to conversation.summary())
    }

    override suspend fun delete(id: String) {
        load()
        withContext(Dispatchers.IO) { file(id).delete() }
        index.value = index.value.orEmpty() - id
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) { dir.deleteRecursively() }
        index.value = emptyMap()
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AssistantDataModule {
    @Binds abstract fun apiKeys(impl: EncryptedApiKeyStore): ApiKeyStore
    @Binds abstract fun settings(impl: DataStoreAssistantSettings): AssistantSettingsStore
    @Binds abstract fun memory(impl: EncryptedMemoryStore): MemoryStore
    @Binds abstract fun conversations(impl: FileConversationStore): ConversationStore
    @Binds abstract fun claude(impl: SdkClaudeClient): ClaudeClient

    companion object {
        /** The assistant's changes reload open screens, like any other change. */
        @Provides
        fun notifier(engine: NotifyingBudgetEngine): ChangeNotifier = ChangeNotifier { engine.notifyChanged() }
    }
}
