package app.centsible.core.model

/** A bridge address plus the Cloudflare Access service token, if it sits behind Access. */
data class BridgeAddress(
    val url: String,
    val cfAccessClientId: String? = null,
    val cfAccessClientSecret: String? = null,
)

/** What the bridge's Actual connection needs before it can be used (see GET /v1/setup). */
enum class ActualSetup { Ready, NeedsPassword, NeedsLogin, Unsupported, Unreachable, Unknown }

data class SetupStatus(val needsOwner: Boolean, val actual: ActualSetup)
