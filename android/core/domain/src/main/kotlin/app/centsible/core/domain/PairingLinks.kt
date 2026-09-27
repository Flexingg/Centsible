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

    /**
     * HTTPS anywhere. Plain HTTP only where traffic can't cross the internet: loopback,
     * private LAN ranges (incl. Docker's 172.16/12), Tailscale's 100.64/10 and mDNS names.
     */
    fun isAllowedBridgeUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return when (uri.scheme?.lowercase()) {
            "https" -> true
            "http" -> host == "localhost" || host.endsWith(".local") || isPrivateIpv4(host)
            else -> false
        }
    }

    private fun isPrivateIpv4(host: String): Boolean {
        val o = host.split('.').map { it.toIntOrNull() ?: return false }
        if (o.size != 4 || o.any { it !in 0..255 }) return false
        return o[0] == 127 || o[0] == 10 ||
            (o[0] == 172 && o[1] in 16..31) ||
            (o[0] == 192 && o[1] == 168) ||
            (o[0] == 100 && o[1] in 64..127)
    }
}
