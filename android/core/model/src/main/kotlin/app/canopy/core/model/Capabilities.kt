package app.canopy.core.model

/** Feature flags reported by the bridge; the UI hides anything not supported. */
enum class Feature(val key: String) {
    Household("household"),
    AccountsRead("accounts.read"),
    TransactionsRead("transactions.read"),
    TransactionsCreate("transactions.create"),
    BudgetEnvelope("budget.envelope"),
    BudgetTracking("budget.tracking"),
    BudgetCarryover("budget.carryover"),
    BudgetHold("budget.hold"),
    BudgetMoveMoney("budget.moveMoney"),
}

enum class BridgeStatus { Ok, Degraded, Unavailable, Unknown }

data class Capabilities(
    val contract: String,
    val bridgeVersion: String,
    val actualServerVersion: String?,
    val actualApiVersion: String,
    val compatibility: String,
    val status: BridgeStatus,
    private val features: Map<String, Boolean>,
) {
    fun has(feature: Feature) = features[feature.key] == true

    companion object {
        /** Before the first successful call: nothing is assumed to work. */
        val None = Capabilities("", "", null, "", "unknown", BridgeStatus.Unknown, emptyMap())
    }
}
