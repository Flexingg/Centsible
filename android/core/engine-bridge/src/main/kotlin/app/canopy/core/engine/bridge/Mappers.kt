package app.canopy.core.engine.bridge

import app.canopy.core.model.Account
import app.canopy.core.model.AccountId
import app.canopy.core.model.BridgeStatus
import app.canopy.core.model.Budget
import app.canopy.core.model.BudgetCategory
import app.canopy.core.model.BudgetGroup
import app.canopy.core.model.BudgetId
import app.canopy.core.model.BudgetMonth
import app.canopy.core.model.BudgetType
import app.canopy.core.model.Capabilities
import app.canopy.core.model.Category
import app.canopy.core.model.CategoryGroup
import app.canopy.core.model.CategoryGroupId
import app.canopy.core.model.CategoryId
import app.canopy.core.model.Device
import app.canopy.core.model.DeviceId
import app.canopy.core.model.Member
import app.canopy.core.model.MemberId
import app.canopy.core.model.Money
import app.canopy.core.model.Payee
import app.canopy.core.model.PayeeId
import app.canopy.core.model.Role
import app.canopy.core.model.Transaction
import app.canopy.core.model.TransactionId
import app.canopy.core.model.YearMonth
import app.canopy.core.network.AccountDto
import app.canopy.core.network.BudgetDto
import app.canopy.core.network.BudgetMonthDto
import app.canopy.core.network.CapabilitiesDto
import app.canopy.core.network.CategoryGroupDto
import app.canopy.core.network.DeviceDto
import app.canopy.core.network.MemberDto
import app.canopy.core.network.PayeeDto
import app.canopy.core.network.TransactionDto

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
internal fun AccountDto.toModel() = Account(AccountId(id), name, offBudget, closed, Money(balance))
internal fun PayeeDto.toModel() = Payee(PayeeId(id), name, transferAccountId?.let(::AccountId))

internal fun CategoryGroupDto.toModel() = CategoryGroup(
    id = CategoryGroupId(id),
    name = name,
    isIncome = isIncome,
    hidden = hidden,
    categories = categories.map { Category(CategoryId(it.id), it.name, CategoryGroupId(it.groupId), it.isIncome, it.hidden) },
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
    isTransfer = transferId != null,
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
