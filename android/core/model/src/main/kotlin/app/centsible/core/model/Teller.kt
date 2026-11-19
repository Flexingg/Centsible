package app.centsible.core.model

/** Teller: a second bank sync provider, run by the bridge (free for personal use, up to 100 bank logins). */
data class TellerStatus(
    val configured: Boolean,
    val applicationId: String?,
    val environment: String?,
    val hasCertificate: Boolean,
    val enrollments: List<TellerEnrollment>,
) {
    companion object {
        val ENVIRONMENTS = listOf("development", "production", "sandbox")
        fun environmentLabel(env: String) = when (env) {
            "development" -> "Development (free, up to 100 bank logins)"
            "production" -> "Production (billed by Teller)"
            "sandbox" -> "Sandbox (test data only)"
            else -> env
        }
    }
}

/** One bank login connected through Teller Connect. */
data class TellerEnrollment(val id: String, val institution: String, val status: Status, val message: String?, val linkedAccounts: Int) {
    enum class Status { Ok, Disconnected, Revoked }
}

data class TellerAccount(
    val id: String,
    val enrollmentId: String,
    val institution: String,
    val name: String,
    val isCredit: Boolean,
    val lastFour: String?,
    /** As the bank reports it (its sign is the bank's). */
    val balance: Money?,
    val linkedAccountId: AccountId?,
    val linkedAccountName: String?,
    val flip: Boolean,
) {
    val label get() = name + (lastFour?.let { " ··$it" } ?: "")
}

/** A recent transaction as it would import, to check the sign before linking. */
data class TellerPreviewTransaction(val date: String, val payee: String, val amount: Money, val pending: Boolean)

/** What Teller Connect hands back after someone signs in to their bank. */
data class TellerEnrollmentResult(val accessToken: String, val enrollmentId: String, val institution: String, val userId: String?)
