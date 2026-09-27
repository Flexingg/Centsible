package app.centsible.core.domain

import kotlinx.coroutines.flow.Flow

/**
 * Emits after any successful write through the engine, so screens showing other views
 * of the same data (the dashboard after editing a transaction, say) reload.
 */
interface BudgetChanges {
    val changes: Flow<Unit>
}
