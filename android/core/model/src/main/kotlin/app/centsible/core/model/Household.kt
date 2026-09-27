package app.centsible.core.model

enum class Role { Owner, Member, Viewer, Unknown;
    val canWrite get() = this == Owner || this == Member
}

data class Member(
    val id: MemberId,
    val displayName: String,
    val role: Role,
    val disabled: Boolean,
    val budgetIds: List<BudgetId>,
)

data class Device(val id: DeviceId, val name: String, val platform: String, val lastSeenAt: String?)

data class PairingInvite(val code: String, val expiresAt: String, val pairingUri: String)

/** Parsed `actualbridge://pair?u=…&c=…[&cfid=…&cfsecret=…]` link from a QR code. */
data class PairingLink(
    val bridgeUrl: String,
    val code: String,
    val cfAccessClientId: String? = null,
    val cfAccessClientSecret: String? = null,
)
