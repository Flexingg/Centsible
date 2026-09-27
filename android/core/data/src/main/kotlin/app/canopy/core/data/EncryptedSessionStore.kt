package app.canopy.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.canopy.core.domain.Session
import app.canopy.core.domain.SessionStore
import app.canopy.core.model.BudgetId
import app.canopy.core.model.DeviceId
import app.canopy.core.model.Member
import app.canopy.core.model.MemberId
import app.canopy.core.model.Role
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.sessionDataStore by preferencesDataStore("session")
private val SESSION = stringPreferencesKey("session.v1")

/**
 * Stores the session (bridge URL, tokens, Cloudflare Access secret) in DataStore,
 * encrypted with an AES-GCM key that never leaves the Android Keystore.
 */
@Singleton
class EncryptedSessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : SessionStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val crypto = KeystoreCipher("canopy.session")

    override val session: Flow<Session?> = context.sessionDataStore.data.map { prefs ->
        prefs[SESSION]?.let { runCatching { json.decodeFromString<Stored>(crypto.decrypt(it)).toSession() }.getOrNull() }
    }

    override suspend fun current(): Session? = session.first()

    override suspend fun save(session: Session) = write(session)

    override suspend fun updateTokens(accessToken: String, refreshToken: String) {
        current()?.let { write(it.copy(accessToken = accessToken, refreshToken = refreshToken)) }
    }

    override suspend fun selectBudget(budget: BudgetId) {
        current()?.let { write(it.copy(selectedBudget = budget)) }
    }

    override suspend fun clear() {
        context.sessionDataStore.edit { it.remove(SESSION) }
    }

    private suspend fun write(session: Session) {
        val encrypted = crypto.encrypt(json.encodeToString(Stored.serializer(), Stored.from(session)))
        context.sessionDataStore.edit { it[SESSION] = encrypted }
    }

    @Serializable
    private data class Stored(
        val bridgeUrl: String,
        val accessToken: String,
        val refreshToken: String,
        val cfId: String? = null,
        val cfSecret: String? = null,
        val memberId: String,
        val memberName: String,
        val role: String,
        val budgetIds: List<String> = emptyList(),
        val deviceId: String,
        val selectedBudget: String? = null,
    ) {
        fun toSession() = Session(
            bridgeUrl, accessToken, refreshToken, cfId, cfSecret,
            Member(MemberId(memberId), memberName, runCatching { Role.valueOf(role) }.getOrDefault(Role.Unknown), false, budgetIds.map(::BudgetId)),
            DeviceId(deviceId), selectedBudget?.let(::BudgetId),
        )

        companion object {
            fun from(s: Session) = Stored(
                s.bridgeUrl, s.accessToken, s.refreshToken, s.cfAccessClientId, s.cfAccessClientSecret,
                s.member.id.raw, s.member.displayName, s.member.role.name, s.member.budgetIds.map { it.raw },
                s.deviceId.raw, s.selectedBudget?.raw,
            )
        }
    }
}

private class KeystoreCipher(private val alias: String) {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        }
        return String(cipher.doFinal(bytes, 12, bytes.size - 12))
    }
}
