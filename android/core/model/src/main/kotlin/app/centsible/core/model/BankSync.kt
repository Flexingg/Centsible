package app.centsible.core.model

/** Settings → Bank sync: the SimpleFIN connection, its quota use, and the background schedule. */
data class BankSyncOverview(
    val simplefinConfigured: Boolean,
    val requestsToday: Int,
    val dailyQuota: Int,
    val schedule: SyncSchedule,
    val intervals: List<Int>,
    /** False for SimpleFIN connections made before the bridge could import history; reconnecting fixes it. */
    val historyAccess: Boolean = false,
    val backfill: Backfill? = null,
)

/** Importing older SimpleFIN history, 90 days per request, over hours or days. */
data class Backfill(
    val budgetId: BudgetId,
    val accountIds: List<AccountId>,
    val since: String,
    val status: Status,
    val reachedDate: String,
    val windowsDone: Int,
    val windowsTotal: Int,
    val transactionsAdded: Int,
    val message: String?,
) {
    enum class Status { Running, Waiting, Done, Failed, Cancelled }

    val active get() = status == Status.Running || status == Status.Waiting
    val progress get() = if (windowsTotal <= 0) 0f else (windowsDone.toFloat() / windowsTotal).coerceIn(0f, 1f)

    companion object {
        val YEARS = listOf(1, 2, 3, 5, 10)
    }
}

data class SyncSchedule(val intervalHours: Int, val lastRunAt: String?, val nextRunAt: String?, val lastResult: SyncRunResult?)

data class SyncRunResult(val newTransactions: Int, val accounts: Int, val errors: List<String>, val skipped: String?)

/** An account inside the SimpleFIN connection. */
data class ExternalAccount(
    val id: String,
    val name: String,
    val institution: String?,
    val balance: Money,
    val linkedAccountId: AccountId?,
    val linkedAccountName: String?,
)

/** Where an imported transaction's date, payee and notes come from (Actual's field mapping). */
data class FieldMapping(val date: String = "date", val payee: String = "payeeName", val notes: String = "notes") {
    companion object {
        val DATE_FIELDS = listOf("date", "postedDate", "transactedDate")
        val TEXT_FIELDS = listOf("payeeName", "notes")

        fun label(field: String) = when (field) {
            "date" -> "Date (posted, or when pending)"
            "postedDate" -> "Posted date"
            "transactedDate" -> "Transaction date"
            "payeeName" -> "Payee"
            "notes" -> "Description"
            else -> field
        }
    }
}

data class BankSyncSettings(
    val importTransactions: Boolean = true,
    val importPending: Boolean = true,
    val importNotes: Boolean = true,
    val reimportDeleted: Boolean = true,
    val updateDates: Boolean = false,
    val payment: FieldMapping = FieldMapping(),
    val deposit: FieldMapping = FieldMapping(),
)

data class AccountSyncResult(val accountId: AccountId, val name: String, val newTransactions: Int, val error: String?, val status: String?)

/** Actual's per-account sync status, in words. */
object BankSyncStatus {
    fun describe(status: String?): String? = when (status) {
        null, "ok" -> null
        "reauth-required" -> "The bank connection needs you to sign in again at SimpleFIN."
        "attention-required" -> "The bank connection needs your attention at SimpleFIN."
        "rate-limit-exceeded" -> "Too many syncs today. Try again tomorrow."
        "timed-out" -> "The bank took too long to answer. It'll retry next sync."
        "account-missing" -> "This account is no longer in your SimpleFIN connection."
        else -> "The last sync failed."
    }
}
