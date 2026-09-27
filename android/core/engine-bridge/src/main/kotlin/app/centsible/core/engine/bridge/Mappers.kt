package app.centsible.core.engine.bridge

import app.centsible.core.model.Account
import app.centsible.core.model.AccountId
import app.centsible.core.model.BridgeStatus
import app.centsible.core.model.Budget
import app.centsible.core.model.BudgetCategory
import app.centsible.core.model.BudgetGroup
import app.centsible.core.model.BudgetId
import app.centsible.core.model.BudgetMonth
import app.centsible.core.model.BudgetType
import app.centsible.core.model.Capabilities
import app.centsible.core.model.Category
import app.centsible.core.model.CategoryGroup
import app.centsible.core.model.CategoryGroupId
import app.centsible.core.model.CategoryId
import app.centsible.core.model.Device
import app.centsible.core.model.DeviceId
import app.centsible.core.model.Member
import app.centsible.core.model.MemberId
import app.centsible.core.model.Money
import app.centsible.core.model.Payee
import app.centsible.core.model.PayeeId
import app.centsible.core.model.Role
import app.centsible.core.model.Transaction
import app.centsible.core.model.TransactionId
import app.centsible.core.model.YearMonth
import app.centsible.core.network.AccountDto
import app.centsible.core.network.BudgetDto
import app.centsible.core.network.BudgetMonthDto
import app.centsible.core.network.CapabilitiesDto
import app.centsible.core.network.CategoryGroupDto
import app.centsible.core.network.DeviceDto
import app.centsible.core.network.MemberDto
import app.centsible.core.network.PayeeDto
import app.centsible.core.network.TransactionDto
import app.centsible.core.network.CategoryDto
import app.centsible.core.network.PreferencesDto
import app.centsible.core.model.Preferences
import app.centsible.core.model.TransactionPatch
import app.centsible.core.model.Update
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Wire → domain. Unknown enum values map to Unknown instead of crashing.

internal fun CapabilitiesDto.toModel() = Capabilities(
    contract = contract,
    bridgeVersion = bridge.version,
    actualServerVersion = actual.serverVersion,
    actualApiVersion = actual.apiVersion,
    compatibility = actual.compatibility,
    status = when (status) {
        "ok" -> BridgeStatus.Ok
        "degraded" -> BridgeStatus.Degraded
        "unavailable" -> BridgeStatus.Unavailable
        else -> BridgeStatus.Unknown
    },
    features = features,
)

internal fun String.toRole() = when (this) {
    "owner" -> Role.Owner
    "member" -> Role.Member
    "viewer" -> Role.Viewer
    else -> Role.Unknown
}

internal fun Role.wire() = when (this) {
    Role.Owner -> "owner"
    Role.Member -> "member"
    Role.Viewer, Role.Unknown -> "viewer"
}

internal fun MemberDto.toModel() = Member(MemberId(id), displayName, role.toRole(), disabled, budgetIds.map(::BudgetId))
internal fun DeviceDto.toModel() = Device(DeviceId(id), name, platform, lastSeenAt)
internal fun BudgetDto.toModel() = Budget(BudgetId(id), name, encrypted)
internal fun AccountDto.toModel() = Account(AccountId(id), name, offBudget, closed, Money(balance), syncSource, lastSync)
internal fun PayeeDto.toModel() = Payee(PayeeId(id), name, transferAccountId?.let(::AccountId))

internal fun CategoryGroupDto.toModel() = CategoryGroup(
    id = CategoryGroupId(id),
    name = name,
    isIncome = isIncome,
    hidden = hidden,
    categories = categories.map { it.toModel() },
)

internal fun TransactionDto.toModel(): Transaction = Transaction(
    id = TransactionId(id),
    accountId = AccountId(accountId),
    date = date,
    amount = Money(amount),
    payeeId = payeeId?.let(::PayeeId),
    payeeName = payeeName,
    categoryId = categoryId?.let(::CategoryId),
    notes = notes,
    cleared = cleared,
    reconciled = reconciled,
    transferId = transferId?.let(::TransactionId),
    isParent = isParent,
    subtransactions = subtransactions.map { it.toModel() },
)

internal fun BudgetMonthDto.toModel() = BudgetMonth(
    month = YearMonth(month),
    budgetType = when (budgetType) {
        "envelope" -> BudgetType.Envelope
        "tracking" -> BudgetType.Tracking
        else -> BudgetType.Unknown
    },
    toBudget = Money(toBudget),
    incomeAvailable = Money(incomeAvailable),
    lastMonthOverspent = Money(lastMonthOverspent),
    forNextMonth = Money(forNextMonth),
    fromLastMonth = Money(fromLastMonth),
    totalBudgeted = Money(totalBudgeted),
    totalIncome = Money(totalIncome),
    totalSpent = Money(totalSpent),
    totalBalance = Money(totalBalance),
    groups = groups.map { g ->
        BudgetGroup(
            id = CategoryGroupId(g.id),
            name = g.name,
            isIncome = g.isIncome,
            hidden = g.hidden,
            budgeted = Money(g.budgeted),
            spent = Money(g.spent),
            balance = Money(g.balance),
            received = Money(g.received),
            categories = g.categories.map { c ->
                BudgetCategory(
                    id = CategoryId(c.id),
                    name = c.name,
                    hidden = c.hidden,
                    budgeted = Money(c.budgeted),
                    spent = Money(c.spent),
                    balance = Money(c.balance),
                    received = Money(c.received),
                    carryover = c.carryover,
                )
            },
        )
    },
)

internal fun PreferencesDto.toModel() = Preferences(
    budgetType = when (budgetType) {
        "envelope" -> BudgetType.Envelope
        "tracking" -> BudgetType.Tracking
        else -> BudgetType.Unknown
    },
    currencyCode = currencyCode,
    numberFormat = numberFormat,
    dateFormat = dateFormat,
    firstDayOfWeek = firstDayOfWeek,
    hideFraction = hideFraction,
)

internal fun CategoryDto.toModel() = Category(CategoryId(id), name, CategoryGroupId(groupId), isIncome, hidden)

/**
 * PATCH body: fields left as [Update.Keep] are omitted, [Update.Set] with null is sent as
 * an explicit JSON null, which the bridge reads as "clear this field".
 */
internal fun TransactionPatch.toJson(): JsonObject = buildJsonObject {
    fun <T> put(key: String, u: Update<T>, encode: (T) -> JsonElement) {
        if (u is Update.Set) put(key, u.value?.let(encode) ?: JsonNull)
    }
    put("accountId", accountId) { JsonPrimitive(it.raw) }
    put("date", date) { JsonPrimitive(it) }
    put("amount", amount) { JsonPrimitive(it.minor) }
    put("payeeId", payeeId) { JsonPrimitive(it?.raw) }
    if (payeeId == Update.Keep) put("payeeName", payeeName) { JsonPrimitive(it) }
    put("categoryId", categoryId) { JsonPrimitive(it?.raw) }
    put("notes", notes) { JsonPrimitive(it) }
    put("cleared", cleared) { JsonPrimitive(it) }
    put("subtransactions", splits) { list ->
        buildJsonArray {
            list.forEach { s ->
                add(
                    buildJsonObject {
                        s.id?.let { put("id", it.raw) }
                        put("amount", s.amount.minor)
                        put("categoryId", s.categoryId?.raw)
                        put("notes", s.notes)
                    },
                )
            }
        }
    }
}
