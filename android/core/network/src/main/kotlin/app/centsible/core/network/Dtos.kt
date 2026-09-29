package app.centsible.core.network

import kotlinx.serialization.Serializable

// Wire types for contract/openapi.yaml (v1). Unknown fields are ignored and every
// field added after 1.0 must have a default, so newer bridges never break older apps.

@Serializable data class ProblemDto(val type: String = "", val title: String = "", val status: Int = 0, val code: String = "", val detail: String? = null)

@Serializable data class HealthDto(val status: String, val version: String)

@Serializable data class CapabilitiesDto(
    val contract: String,
    val bridge: BridgeInfo,
    val actual: ActualInfo,
    val status: String,
    val features: Map<String, Boolean> = emptyMap(),
) {
    @Serializable data class BridgeInfo(val version: String)
    @Serializable data class ActualInfo(val serverVersion: String? = null, val apiVersion: String, val compatibility: String)
}

@Serializable data class SetupStatusDto(val needsOwner: Boolean, val actual: String? = null)
@Serializable data class SetupClaimDto(
    val setupCode: String,
    val displayName: String,
    val deviceName: String,
    val platform: String = "android",
    val actualPassword: String? = null,
)
@Serializable data class NewBudgetDto(val name: String)
@Serializable data class PairRequestDto(val code: String, val deviceName: String, val platform: String = "android")
@Serializable data class RefreshRequestDto(val refreshToken: String)

@Serializable data class TokenResponseDto(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Int,
    val member: MemberDto,
    val device: DeviceDto,
)

@Serializable data class MemberDto(val id: String, val displayName: String, val role: String, val disabled: Boolean = false, val budgetIds: List<String> = emptyList())
@Serializable data class NewMemberDto(val displayName: String, val role: String, val budgetIds: List<String> = emptyList())
@Serializable data class MemberBudgetsDto(val budgetIds: List<String>)
@Serializable data class DeviceDto(val id: String, val name: String, val platform: String, val createdAt: String, val lastSeenAt: String? = null)
@Serializable data class MeDto(val member: MemberDto, val device: DeviceDto, val devices: List<DeviceDto> = emptyList())
@Serializable data class PairingCodeDto(val code: String, val expiresAt: String, val pairingUri: String)

@Serializable data class ItemsDto<T>(val items: List<T>)

@Serializable data class BudgetDto(val id: String, val name: String, val encrypted: Boolean = false)

@Serializable data class AccountDto(
    val id: String,
    val name: String,
    val offBudget: Boolean,
    val closed: Boolean,
    val balance: Long,
    val accountGroupId: String? = null,
    val syncSource: String? = null,
    val lastSync: String? = null,
    val bankSyncStatus: String? = null,
)

@Serializable data class CategoryDto(val id: String, val name: String, val groupId: String, val isIncome: Boolean, val hidden: Boolean)
@Serializable data class CategoryGroupDto(val id: String, val name: String, val isIncome: Boolean, val hidden: Boolean, val categories: List<CategoryDto> = emptyList())
@Serializable data class PayeeDto(val id: String, val name: String, val transferAccountId: String? = null)

@Serializable data class TransactionDto(
    val id: String,
    val accountId: String,
    val date: String,
    val amount: Long,
    val payeeId: String? = null,
    val payeeName: String? = null,
    val categoryId: String? = null,
    val notes: String? = null,
    val cleared: Boolean = false,
    val reconciled: Boolean = false,
    val transferId: String? = null,
    val isParent: Boolean = false,
    val parentId: String? = null,
    val subtransactions: List<TransactionDto> = emptyList(),
)

@Serializable data class TransactionPageDto(val items: List<TransactionDto>, val nextCursor: String? = null, val runningBalances: List<Long>? = null)

@Serializable data class NewTransactionDto(
    val id: String,
    val accountId: String,
    val date: String,
    val amount: Long,
    val payeeId: String? = null,
    val payeeName: String? = null,
    val categoryId: String? = null,
    val notes: String? = null,
    val cleared: Boolean? = null,
    val subtransactions: List<NewSplitDto>? = null,
)

@Serializable data class NewSplitDto(val amount: Long, val categoryId: String? = null, val notes: String? = null, val id: String? = null)

@Serializable data class MonthsDto(val months: List<String>)

@Serializable data class BudgetMonthDto(
    val month: String,
    val budgetType: String,
    val toBudget: Long,
    val incomeAvailable: Long,
    val lastMonthOverspent: Long,
    val forNextMonth: Long,
    val fromLastMonth: Long,
    val totalBudgeted: Long,
    val totalIncome: Long,
    val totalSpent: Long,
    val totalBalance: Long,
    val groups: List<BudgetGroupDto>,
)

@Serializable data class BudgetGroupDto(
    val id: String,
    val name: String,
    val isIncome: Boolean,
    val hidden: Boolean,
    val budgeted: Long = 0,
    val spent: Long = 0,
    val balance: Long = 0,
    val received: Long = 0,
    val categories: List<BudgetCategoryDto> = emptyList(),
)

@Serializable data class BudgetCategoryDto(
    val id: String,
    val name: String,
    val hidden: Boolean = false,
    val budgeted: Long = 0,
    val spent: Long = 0,
    val balance: Long = 0,
    val received: Long = 0,
    val carryover: Boolean = false,
)

@Serializable data class CategoryBudgetPatchDto(val budgeted: Long? = null, val carryover: Boolean? = null)
@Serializable data class MoneyTransferDto(val from: String, val to: String, val amount: Long)
@Serializable data class HoldDto(val amount: Long)

@Serializable data class PreferencesDto(
    val budgetType: String = "envelope",
    val currencyCode: String = "USD",
    val numberFormat: String = "comma-dot",
    val dateFormat: String = "MM/dd/yyyy",
    val firstDayOfWeek: Int = 0,
    val hideFraction: Boolean = false,
)

@Serializable data class NewAccountDto(val name: String, val offBudget: Boolean = false, val initialBalance: Long = 0)
@Serializable data class AccountPatchDto(val name: String)
@Serializable data class CloseAccountDto(val transferAccountId: String? = null, val transferCategoryId: String? = null)
@Serializable data class NewCategoryDto(val name: String, val groupId: String)
@Serializable data class CategoryPatchDto(val name: String? = null, val hidden: Boolean? = null, val groupId: String? = null)
@Serializable data class NewGroupDto(val name: String)
@Serializable data class GroupPatchDto(val name: String? = null, val hidden: Boolean? = null)
