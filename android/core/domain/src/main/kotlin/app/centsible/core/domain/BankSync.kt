package app.centsible.core.domain

import app.centsible.core.model.AccountId
import app.centsible.core.model.BankSyncOverview
import app.centsible.core.model.BankSyncSettings
import app.centsible.core.model.BudgetId
import app.centsible.core.model.ExternalAccount
import app.centsible.core.model.SyncSchedule

/** SimpleFIN setup, linking and sync options. Syncing itself is [AccountServices.startBankSync]. */
interface BankSyncGateway {
    suspend fun overview(): BankSyncOverview
    suspend fun connect(setupToken: String): BankSyncOverview
    suspend fun disconnect()
    suspend fun setSchedule(intervalHours: Int): SyncSchedule
    suspend fun externalAccounts(budget: BudgetId, refresh: Boolean = true): List<ExternalAccount>
    /** [existing] null creates a new account (on or off budget). */
    suspend fun link(budget: BudgetId, externalId: String, existing: AccountId?, offBudget: Boolean): AccountId
    suspend fun unlink(budget: BudgetId, account: AccountId)
    suspend fun settings(budget: BudgetId, account: AccountId): BankSyncSettings
    suspend fun updateSettings(budget: BudgetId, account: AccountId, settings: BankSyncSettings): BankSyncSettings
}
