package app.centsible.core.domain

import java.net.URI
import java.net.URLDecoder

/** Where a `centsible://open/...` link asks the app to go. */
sealed interface AppLink {
    enum class Screen(val path: String) {
        Review("review"),
        Transactions("transactions"),
        Accounts("accounts"),
        Budget("budget"),
        NetWorth("net-worth"),
        Recurring("recurring"),
        Trends("trends"),
        BankSync("bank-sync"),
        Teller("teller"),
        Mortgage("mortgage"),
    }

    data class To(val screen: Screen) : AppLink
    data class Account(val id: String) : AppLink
    /** A category's transactions; `name` is the screen title, `month` (yyyy-MM) narrows to that month. */
    data class Category(val id: String, val name: String, val month: String?) : AppLink
}

/**
 * Links from outside the app (Home Assistant dashboards and notifications, shortcuts):
 *
 *     centsible://open/review                       the review inbox
 *     centsible://open/<screen>                     transactions, accounts, budget, net-worth,
 *                                                   recurring, trends, bank-sync, teller, mortgage
 *     centsible://open/account/<id>                 one account
 *     centsible://open/category/<id>?name=Groceries[&month=2026-11]
 *
 * They only navigate: nothing is changed, and the app lock still applies.
 */
object AppLinks {
    const val SCHEME = "centsible"
    private val ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val MONTH = Regex("^\\d{4}-(0[1-9]|1[0-2])$")

    fun parse(raw: String): AppLink? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals(SCHEME, ignoreCase = true) || !uri.host.equals("open", ignoreCase = true)) return null
        val parts = uri.rawPath.orEmpty().trim('/').split('/').filter { it.isNotEmpty() }
        val query = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i) to URLDecoder.decode(part.substring(i + 1), Charsets.UTF_8)
        }.toMap()
        return when {
            parts.size == 1 -> AppLink.Screen.entries.firstOrNull { it.path == parts[0].lowercase() }?.let { AppLink.To(it) }
            parts.size == 2 && parts[0] == "account" && ID.matches(parts[1]) -> AppLink.Account(parts[1])
            parts.size == 2 && parts[0] == "category" && ID.matches(parts[1]) ->
                AppLink.Category(
                    id = parts[1],
                    name = query["name"]?.trim()?.take(80)?.takeIf { it.isNotEmpty() } ?: "Category",
                    month = query["month"]?.takeIf { MONTH.matches(it) },
                )
            else -> null
        }
    }
}
