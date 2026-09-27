package app.centsible.core.testing

import app.centsible.core.domain.Session
import app.centsible.core.domain.SessionStore
import app.centsible.core.model.BudgetId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class InMemorySessionStore(initial: Session? = null) : SessionStore {
    private val state = MutableStateFlow(initial)
    override val session: StateFlow<Session?> = state

    override suspend fun current() = state.value
    override suspend fun save(session: Session) { state.value = session }
    override suspend fun updateTokens(accessToken: String, refreshToken: String) {
        state.value = state.value?.copy(accessToken = accessToken, refreshToken = refreshToken)
    }
    override suspend fun selectBudget(budget: BudgetId) { state.value = state.value?.copy(selectedBudget = budget) }
    override suspend fun clear() { state.value = null }
}
