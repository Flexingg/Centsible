package app.centsible.core.domain

import app.centsible.core.model.PairingLink
import java.net.URI
import java.net.URLDecoder

object PairingLinks {
    const val SCHEME = "actualbridge"

    /**
     * Parses the QR payload `actualbridge://pair?u=<url>&c=<code>[&cfid=..&cfsecret=..]`.
     * Returns null for anything else, so a random QR code can't point the app elsewhere
     * without the user seeing the URL first.
     */
    fun parse(raw: String): PairingLink? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals(SCHEME, ignoreCase = true) || !uri.host.equals("pair", ignoreCase = true)) return null
        val params = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i) to URLDecoder.decode(part.substring(i + 1), Charsets.UTF_8)
        }.toMap()
        val url = params["u"]?.trimEnd('/') ?: return null
        val code = params["c"] ?: return null
        if (!isAllowedBridgeUrl(url)) return null
        return PairingLink(url, code, params["cfid"], params["cfsecret"])
    }

    /** HTTPS only, except plain HTTP on a local network for development. */
    fun isAllowedBridgeUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host ?: return false
        return when (uri.scheme?.lowercase()) {
            "https" -> true
            "http" -> host == "localhost" || host.startsWith("10.") || host.startsWith("192.168.") || host.endsWith(".local")
            else -> false
        }
    }
}
