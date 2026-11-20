package app.centsible.core.domain

import app.centsible.core.model.AccountId
import app.centsible.core.model.AccountSyncResult
import app.centsible.core.model.BudgetId
import app.centsible.core.model.PlaidAccount
import app.centsible.core.model.PlaidItem
import app.centsible.core.model.PlaidLinkResult
import app.centsible.core.model.PlaidStatus

/** Plaid setup, bank logins and linking. Syncing is [AccountServices.startBankSync], like SimpleFIN. */
interface PlaidGateway {
    suspend fun status(): PlaidStatus
    /** Owners. [secret] null keeps the stored one. */
    suspend fun configure(clientId: String, environment: String, secret: String?): PlaidStatus
    suspend fun reset()
    /** A token that opens Plaid Link: a new bank login, or ([itemId]) signing in to that one again. */
    suspend fun linkToken(itemId: String? = null): String
    suspend fun addItem(result: PlaidLinkResult): PlaidItem
    suspend fun removeItem(id: String)
    suspend fun accounts(budget: BudgetId, refresh: Boolean = true): List<PlaidAccount>
    suspend fun link(budget: BudgetId, plaidAccountId: String, account: AccountId): AccountSyncResult
    suspend fun unlink(budget: BudgetId, account: AccountId)
}
