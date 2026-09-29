package app.centsible.core.domain

import app.centsible.core.model.Appearance
import app.centsible.core.model.BudgetId
import app.centsible.core.model.HomeLayout

interface PersonalGateway {
    suspend fun homeLayout(): HomeLayout
    suspend fun setHomeLayout(layout: HomeLayout): HomeLayout
    /** By category or group id. */
    suspend fun appearance(budget: BudgetId): Map<String, Appearance>
    suspend fun setAppearance(budget: BudgetId, id: String, appearance: Appearance)
}
