package app.centsible.core.domain

import app.centsible.core.model.AccountId
import app.centsible.core.model.AccountSyncResult
import app.centsible.core.model.BudgetId
import app.centsible.core.model.TellerAccount
import app.centsible.core.model.TellerEnrollment
import app.centsible.core.model.TellerEnrollmentResult
import app.centsible.core.model.TellerPreviewTransaction
import app.centsible.core.model.TellerStatus

/** Teller setup, bank logins and linking. Syncing is [AccountServices.startBankSync], like SimpleFIN. */
interface TellerGateway {
    suspend fun status(): TellerStatus
    /** Owners. [certificate]/[privateKey] null keeps the stored pair. */
    suspend fun configure(applicationId: String, environment: String, certificate: String?, privateKey: String?): TellerStatus
    suspend fun reset()
    suspend fun addEnrollment(result: TellerEnrollmentResult): TellerEnrollment
    suspend fun removeEnrollment(id: String)
    suspend fun accounts(budget: BudgetId, refresh: Boolean = true): List<TellerAccount>
    suspend fun preview(budget: BudgetId, tellerAccountId: String, flip: Boolean): List<TellerPreviewTransaction>
    suspend fun link(budget: BudgetId, tellerAccountId: String, account: AccountId, flip: Boolean): AccountSyncResult
    suspend fun setFlip(budget: BudgetId, account: AccountId, flip: Boolean)
    suspend fun unlink(budget: BudgetId, account: AccountId)
}
