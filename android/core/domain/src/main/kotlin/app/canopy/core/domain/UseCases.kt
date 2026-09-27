package app.canopy.core.domain

import app.canopy.core.model.BudgetId
import app.canopy.core.model.BudgetMonth
import app.canopy.core.model.BudgetPot
import app.canopy.core.model.Money
import app.canopy.core.model.PairingLink
import app.canopy.core.model.YearMonth
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull

/** Pairs this device and, when only one budget is visible, selects it. */
class PairDevice @Inject constructor(
    private val pairing: PairingGateway,
    private val sessions: SessionStore,
    private val engine: BudgetEngine,
) {
    suspend operator fun invoke(link: PairingLink, deviceName: String) {
        sessions.save(pairing.pair(link, deviceName))
        engine.budgets().singleOrNull()?.let { sessions.selectBudget(it.id) }
    }
}

/** The budget file the household is working in. Suspends until one is selected. */
class SelectedBudget @Inject constructor(private val sessions: SessionStore) {
    suspend operator fun invoke(): BudgetId = sessions.session.filterNotNull().first { it.selectedBudget != null }.selectedBudget!!
}

/**
 * Moves money between envelopes. The idempotency key is generated once per user
 * action, so a retry after a dropped connection can never move the money twice.
 */
class MoveMoney @Inject constructor(private val engine: BudgetEngine) {
    suspend operator fun invoke(
        budget: BudgetId,
        month: YearMonth,
        from: BudgetPot,
        to: BudgetPot,
        amount: Money,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): BudgetMonth {
        require(amount.minor > 0) { "Amount must be positive" }
        require(from != to) { "Pick two different envelopes" }
        return engine.moveMoney(budget, month, from, to, amount, idempotencyKey)
    }
}
