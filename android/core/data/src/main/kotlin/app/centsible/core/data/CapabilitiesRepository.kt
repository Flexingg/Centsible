package app.centsible.core.data

import app.centsible.core.domain.BudgetEngine
import app.centsible.core.model.Capabilities
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the connected bridge + Actual version support. Refreshed on app start and
 * foreground; screens gate UI on it instead of assuming features exist.
 */
@Singleton
class CapabilitiesRepository @Inject constructor(private val engine: BudgetEngine) {
    private val state = MutableStateFlow(Capabilities.None)
    val capabilities: StateFlow<Capabilities> = state.asStateFlow()

    suspend fun refresh(): Result<Capabilities> = runCatching { engine.capabilities() }.onSuccess { state.value = it }
}
