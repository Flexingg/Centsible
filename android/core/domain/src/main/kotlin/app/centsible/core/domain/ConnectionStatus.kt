package app.centsible.core.domain

import kotlinx.coroutines.flow.StateFlow

/** Whether screens are currently showing saved data because the bridge is unreachable. */
interface ConnectionStatus {
    val offline: StateFlow<Boolean>
}
