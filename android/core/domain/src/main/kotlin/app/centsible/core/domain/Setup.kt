package app.centsible.core.domain

import app.centsible.core.model.BridgeAddress
import app.centsible.core.model.SetupStatus
import javax.inject.Inject

/** First-run setup of a bridge from the app. */
interface SetupGateway {
    suspend fun status(address: BridgeAddress): SetupStatus

    /** Becomes the first owner; signs the bridge in to Actual on the way when [actualPassword] is given. */
    suspend fun claim(address: BridgeAddress, setupCode: String, displayName: String, deviceName: String, actualPassword: String?): Session
}

class ClaimBridge @Inject constructor(
    private val setup: SetupGateway,
    private val sessions: SessionStore,
    private val engine: BudgetEngine,
) {
    suspend operator fun invoke(address: BridgeAddress, setupCode: String, displayName: String, deviceName: String, actualPassword: String?) {
        sessions.save(setup.claim(address, setupCode, displayName, deviceName, actualPassword))
        engine.budgets().singleOrNull()?.let { sessions.selectBudget(it.id) }
    }
}

object BridgeUrls {
    /**
     * What people type into a URL field: "budget-api.example.com", "https://x.com/",
     * " HTTPS://X.com ". Adds https:// when there's no scheme; null when it isn't a URL
     * the app may talk to (see [PairingLinks.isAllowedBridgeUrl]).
     */
    fun normalize(input: String): String? {
        var url = input.trim().trimEnd('/')
        if (url.isEmpty()) return null
        if (!Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(url)) url = "https://$url"
        Regex("^[a-zA-Z]+://").find(url)?.let { url = it.value.lowercase() + url.substring(it.range.last + 1) }
        return url.takeIf { PairingLinks.isAllowedBridgeUrl(it) }
    }
}
