package app.centsible.core.network

import kotlinx.serialization.Serializable

// Teller, the second bank sync provider (bridge-side; Actual has no Teller support).

@Serializable data class TellerEnrollmentDto(
    val id: String,
    val institution: String,
    val status: String = "ok",
    val message: String? = null,
    val linkedAccounts: Int = 0,
    val updatedAt: String = "",
)

@Serializable data class TellerStatusDto(
    val configured: Boolean = false,
    val applicationId: String? = null,
    val environment: String? = null,
    val hasCertificate: Boolean = false,
    val enrollments: List<TellerEnrollmentDto> = emptyList(),
)

@Serializable data class TellerConfigInputDto(
    val applicationId: String,
    val environment: String,
    val certificate: String? = null,
    val privateKey: String? = null,
)

@Serializable data class TellerEnrollmentInputDto(
    val accessToken: String,
    val enrollmentId: String,
    val institution: String,
    val userId: String? = null,
)

@Serializable data class TellerAccountDto(
    val id: String,
    val enrollmentId: String,
    val institution: String,
    val name: String,
    val type: String = "depository",
    val subtype: String = "",
    val lastFour: String? = null,
    val balance: Long? = null,
    val currency: String = "USD",
    val linkedAccountId: String? = null,
    val linkedAccountName: String? = null,
    val flip: Boolean = false,
)

@Serializable data class TellerPreviewRequestDto(val tellerAccountId: String, val flip: Boolean = false)
@Serializable data class TellerPreviewTxnDto(val date: String, val payee: String, val amount: Long, val pending: Boolean = false)
@Serializable data class TellerPreviewDto(val transactions: List<TellerPreviewTxnDto> = emptyList())

@Serializable data class TellerLinkRequestDto(val tellerAccountId: String, val accountId: String, val flip: Boolean = false, val startDate: String? = null)
@Serializable data class TellerLinkedDto(val accountId: String, val result: AccountSyncResultDto)

@Serializable data class TellerFlipDto(val flip: Boolean)
