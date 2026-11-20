package app.centsible.core.model

/** Plaid: the second bank sync provider, run by the bridge (free Trial plan: up to 10 bank logins). */
data class PlaidStatus(
    val configured: Boolean,
    val clientId: String?,
    val environment: String?,
    /** What to add under "Allowed Android package names" in the Plaid dashboard. */
    val androidPackageName: String,
    val items: List<PlaidItem>,
) {
    companion object {
        val ENVIRONMENTS = listOf("production", "sandbox")
        fun environmentLabel(env: String) = when (env) {
            "production" -> "Production (real banks; free on the Trial plan)"
            "sandbox" -> "Sandbox (test data only)"
            else -> env
        }
    }
}

/** One bank login connected through Plaid Link. */
data class PlaidItem(val id: String, val institution: String, val status: Status, val message: String?, val linkedAccounts: Int) {
    enum class Status { Ok, Reauth, Gone }
}

data class PlaidAccount(
    val id: String,
    val itemId: String,
    val institution: String,
    val name: String,
    val isCredit: Boolean,
    val mask: String?,
    /** As it would show in the budget (debts negative). */
    val balance: Money?,
    val linkedAccountId: AccountId?,
    val linkedAccountName: String?,
) {
    val label get() = name + (mask?.let { " ··$it" } ?: "")
}

/** What Plaid Link hands back after someone signs in to their bank. [itemId] set: an existing login was repaired. */
data class PlaidLinkResult(val publicToken: String?, val institution: String?, val itemId: String? = null)
