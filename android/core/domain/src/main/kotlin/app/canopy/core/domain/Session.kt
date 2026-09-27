package app.canopy.core.domain

import app.canopy.core.model.BudgetId
import app.canopy.core.model.DeviceId
import app.canopy.core.model.Member
import kotlinx.coroutines.flow.Flow

/** A paired device's connection to one bridge. Tokens are stored encrypted. */
data class Session(
    val bridgeUrl: String,
    val accessToken: String,
    val refreshToken: String,
    val cfAccessClientId: String?,
    val cfAccessClientSecret: String?,
    val member: Member,
    val deviceId: DeviceId,
    val selectedBudget: BudgetId? = null,
)

interface SessionStore {
    val session: Flow<Session?>
    suspend fun current(): Session?
    suspend fun save(session: Session)
    suspend fun updateTokens(accessToken: String, refreshToken: String)
    suspend fun selectBudget(budget: BudgetId)
    suspend fun clear()
}
