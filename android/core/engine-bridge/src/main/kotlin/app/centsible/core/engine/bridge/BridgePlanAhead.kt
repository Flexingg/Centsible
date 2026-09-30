package app.centsible.core.engine.bridge

import app.centsible.core.domain.PlanAheadGateway
import app.centsible.core.model.AccountId
import app.centsible.core.model.Autopilot
import app.centsible.core.model.AverageBasis
import app.centsible.core.model.BudgetId
import app.centsible.core.model.BudgetSuggestion
import app.centsible.core.model.CategoryId
import app.centsible.core.model.CoverMove
import app.centsible.core.model.CoverResult
import app.centsible.core.model.Forecast
import app.centsible.core.model.ForecastDay
import app.centsible.core.model.ForecastEvent
import app.centsible.core.model.Goal
import app.centsible.core.model.GoalInput
import app.centsible.core.model.Money
import app.centsible.core.model.Overspent
import app.centsible.core.model.YearMonth
import app.centsible.core.network.ApplyAutopilotDto
import app.centsible.core.network.AveragesDto
import app.centsible.core.network.CoverMoveDto
import app.centsible.core.network.GoalDto
import app.centsible.core.network.GoalInputDto
import app.centsible.core.network.PlanningApi

class BridgePlanAhead(private val api: PlanningApi, private val onWrite: () -> Unit) : PlanAheadGateway {
    override suspend fun autopilot(budget: BudgetId, month: YearMonth): Autopilot {
        val dto = api.autopilot(budget.raw, month.raw)
        return Autopilot(
            YearMonth(dto.month),
            Money(dto.toBudget),
            dto.suggestions.map {
                BudgetSuggestion(
                    CategoryId(it.categoryId), it.name, it.groupName, Money(it.budgeted), Money(it.lastMonthSpent),
                    it.average.toMap(), it.suggested.toMap(), it.monthsOfHistory,
                )
            },
            dto.overspent.map { Overspent(CategoryId(it.categoryId), it.name, Money(it.amount)) },
            dto.cover.moves.map { it.toModel() },
            Money(dto.cover.uncovered),
        )
    }

    override suspend fun applyAutopilot(budget: BudgetId, month: YearMonth, basis: AverageBasis, categories: List<CategoryId>) =
        api.applyAutopilot(budget.raw, month.raw, ApplyAutopilotDto(basis.months, categories.map { it.raw }.ifEmpty { null })).month.toModel().also { onWrite() }

    override suspend fun coverOverspending(budget: BudgetId, month: YearMonth): CoverResult {
        val dto = api.coverOverspending(budget.raw, month.raw)
        onWrite()
        return CoverResult(dto.month.toModel(), dto.moves.map { it.toModel() }, Money(dto.uncovered))
    }

    override suspend fun goals(budget: BudgetId, month: YearMonth?) = api.goals(budget.raw, month?.raw).items.map { it.toModel() }

    override suspend fun setGoal(budget: BudgetId, category: CategoryId, goal: GoalInput?) {
        if (goal == null) api.removeGoal(budget.raw, category.raw)
        else api.setGoal(budget.raw, category.raw, GoalInputDto(if (goal.kind == Goal.Kind.By) "by" else "balance", goal.target.minor, goal.targetMonth?.raw))
        onWrite()
    }

    override suspend fun forecast(budget: BudgetId, days: Int, accounts: List<AccountId>, includeTypical: Boolean): Forecast {
        val dto = api.forecast(budget.raw, days, accounts.map { it.raw }, includeTypical)
        return Forecast(
            dto.from, dto.to, dto.accountIds.map(::AccountId), Money(dto.startingBalance), Money(dto.typicalDaily),
            dto.events.map { ForecastEvent(it.date, it.scheduleId, it.name, it.accountName, Money(it.amount), it.internalTransfer, it.overdue) },
            dto.days.map { ForecastDay(it.date, Money(it.balance), Money(it.scheduled), Money(it.typical)) },
            ForecastDay(dto.lowest.date, Money(dto.lowest.balance)),
            dto.paid.map { app.centsible.core.model.PaidBill(it.date, it.scheduleId, it.name, Money(it.amount)) },
        )
    }

    override suspend fun targets(budget: BudgetId, month: YearMonth?) = api.targets(budget.raw, month?.raw).items.map { it.toModel() }

    override suspend fun saveTarget(budget: BudgetId, id: String?, input: app.centsible.core.model.TargetInput) {
        val body = app.centsible.core.network.TargetInputDto(
            kind = kindName(input.kind),
            name = input.name,
            accountId = input.accountId?.raw,
            categoryId = input.categoryId?.raw,
            amount = input.amount?.minor,
            percentOfIncome = input.percentOfIncome,
            targetMonth = input.targetMonth?.raw,
        )
        if (id == null) api.createTarget(budget.raw, body) else api.updateTarget(budget.raw, id, body)
        onWrite()
    }

    override suspend fun deleteTarget(budget: BudgetId, id: String) {
        api.deleteTarget(budget.raw, id)
        onWrite()
    }
}

private fun kindName(k: app.centsible.core.model.Target.Kind) = when (k) {
    app.centsible.core.model.Target.Kind.Account -> "account"
    app.centsible.core.model.Target.Kind.SpendUnder -> "spend-under"
    app.centsible.core.model.Target.Kind.SpendAtLeast -> "spend-at-least"
}

private fun app.centsible.core.network.TargetDto.toModel() = app.centsible.core.model.Target(
    id = id,
    kind = when (kind) {
        "account" -> app.centsible.core.model.Target.Kind.Account
        "spend-under" -> app.centsible.core.model.Target.Kind.SpendUnder
        else -> app.centsible.core.model.Target.Kind.SpendAtLeast
    },
    name = name,
    accountId = accountId?.let(::AccountId),
    categoryId = categoryId?.let(::CategoryId),
    amount = amount?.let(::Money),
    percentOfIncome = percentOfIncome,
    targetMonth = targetMonth?.let(::YearMonth),
    current = Money(current),
    goal = Money(goal),
    progress = progress,
    status = when (status) {
        "reached" -> app.centsible.core.model.Target.Status.Reached
        "behind" -> app.centsible.core.model.Target.Status.Behind
        "over" -> app.centsible.core.model.Target.Status.Over
        "stalled" -> app.centsible.core.model.Target.Status.Stalled
        else -> app.centsible.core.model.Target.Status.OnTrack
    },
    remaining = Money(remaining),
    monthlyNeeded = monthlyNeeded?.let(::Money),
    avgChange = Money(avgChange),
    projectedMonth = projectedMonth?.let(::YearMonth),
    income = Money(income),
    pace = pace,
    monthsKept = monthsKept,
    missing = missing,
    history = history.map { app.centsible.core.model.Target.Month(YearMonth(it.month), Money(it.value), Money(it.goal)) },
)

private fun AveragesDto.toMap() = mapOf(AverageBasis.Three to Money(avg3), AverageBasis.Six to Money(avg6), AverageBasis.Twelve to Money(avg12))

private fun CoverMoveDto.toModel() = CoverMove(from, fromName, CategoryId(to), toName, Money(amount))

private fun GoalDto.toModel() = Goal(
    CategoryId(categoryId), name, groupName,
    if (kind == "by") Goal.Kind.By else Goal.Kind.Balance,
    Money(target), targetMonth?.let(::YearMonth), Money(balance), Money(budgetedThisMonth), progress, Money(remaining),
    monthlyNeeded?.let(::Money), Money(avgContribution), projectedMonth?.let(::YearMonth),
    when (status) {
        "reached" -> Goal.Status.Reached
        "on-track" -> Goal.Status.OnTrack
        "behind" -> Goal.Status.Behind
        else -> Goal.Status.Stalled
    },
)
