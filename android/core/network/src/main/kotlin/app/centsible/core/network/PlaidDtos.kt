package app.centsible.core.network

import kotlinx.serialization.Serializable

// Plaid, the second bank sync provider (bridge-side; Actual has no Plaid support).

@Serializable data class PlaidItemDto(
    val id: String,
    val institution: String,
    val status: String = "ok",
    val message: String? = null,
    val linkedAccounts: Int = 0,
    val updatedAt: String = "",
)

@Serializable data class PlaidStatusDto(
    val configured: Boolean = false,
    val clientId: String? = null,
    val environment: String? = null,
    val androidPackageName: String = "app.centsible.budget",
    val items: List<PlaidItemDto> = emptyList(),
)

@Serializable data class PlaidConfigInputDto(val clientId: String, val environment: String, val secret: String? = null)

@Serializable data class PlaidLinkTokenRequestDto(val itemId: String? = null)
@Serializable data class PlaidLinkTokenDto(val linkToken: String, val expiration: String = "")

@Serializable data class PlaidItemInputDto(val publicToken: String? = null, val itemId: String? = null, val institution: String? = null)

@Serializable data class PlaidAccountDto(
    val id: String,
    val itemId: String,
    val institution: String,
    val name: String,
    val type: String = "depository",
    val subtype: String? = null,
    val mask: String? = null,
    val balance: Long? = null,
    val currency: String = "USD",
    val linkedAccountId: String? = null,
    val linkedAccountName: String? = null,
)

@Serializable data class PlaidLinkRequestDto(val plaidAccountId: String, val accountId: String, val startDate: String? = null)
@Serializable data class PlaidLinkedDto(val accountId: String, val result: AccountSyncResultDto)
