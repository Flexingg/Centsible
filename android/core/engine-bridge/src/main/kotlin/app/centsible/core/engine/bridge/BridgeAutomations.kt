package app.centsible.core.engine.bridge

import app.centsible.core.domain.AutomationsGateway
import app.centsible.core.model.Automation
import app.centsible.core.model.AutomationCategory
import app.centsible.core.model.AutomationPreview
import app.centsible.core.model.AutomationRun
import app.centsible.core.model.AutomationSource
import app.centsible.core.model.BudgetId
import app.centsible.core.model.CategoryAutomations
import app.centsible.core.model.CategoryGroupId
import app.centsible.core.model.CategoryId
import app.centsible.core.model.Money
import app.centsible.core.model.YearMonth
import app.centsible.core.network.AutomationAdjustmentDto
import app.centsible.core.network.AutomationApplyBodyDto
import app.centsible.core.network.AutomationDto
import app.centsible.core.network.AutomationEveryDto
import app.centsible.core.network.AutomationLimitDto
import app.centsible.core.network.AutomationPreviewBodyDto
import app.centsible.core.network.AutomationRepeatDto
import app.centsible.core.network.AutomationsApi
import app.centsible.core.network.AutomationsBodyDto
import app.centsible.core.network.CategoryAutomationsDto

class BridgeAutomations(private val api: AutomationsApi, private val onWrite: () -> Unit) : AutomationsGateway {
    override suspend fun list(budget: BudgetId, month: YearMonth?) = api.list(budget.raw, month?.raw).categories.map { c ->
        AutomationCategory(
            CategoryId(c.categoryId), c.name, CategoryGroupId(c.groupId), c.groupName, c.isIncome, c.hidden,
            source(c.source), c.automations.map { it.toModel() }, c.projected?.let(::Money),
        )
    }

    override suspend fun get(budget: BudgetId, category: CategoryId, month: YearMonth?) = api.get(budget.raw, category.raw, month?.raw).toModel()

    override suspend fun set(budget: BudgetId, category: CategoryId, automations: List<Automation>, month: YearMonth?) =
        api.set(budget.raw, category.raw, AutomationsBodyDto(automations.map { it.toDto() }), month?.raw).toModel().also { onWrite() }

    override suspend fun useNotes(budget: BudgetId, category: CategoryId, month: YearMonth?) =
        api.useNotes(budget.raw, category.raw, month?.raw).toModel().also { onWrite() }

    override suspend fun preview(budget: BudgetId, category: CategoryId, month: YearMonth, automations: List<Automation>): AutomationPreview {
        val r = api.preview(budget.raw, category.raw, AutomationPreviewBodyDto(month.raw, automations.map { it.toDto() }))
        return AutomationPreview(Money(r.projected), r.perAutomation.map(::Money))
    }

    override suspend fun apply(budget: BudgetId, month: YearMonth, overwrite: Boolean, categories: List<CategoryId>?): AutomationRun {
        val r = api.apply(budget.raw, AutomationApplyBodyDto(month.raw, overwrite, categories?.map { it.raw }))
        onWrite()
        return AutomationRun(r.ok, r.message, r.details)
    }
}

private fun source(s: String) = when (s) {
    "ui" -> AutomationSource.Automations
    "notes" -> AutomationSource.Notes
    else -> AutomationSource.None
}

private fun CategoryAutomationsDto.toModel() = CategoryAutomations(
    CategoryId(categoryId), source(source), automations.map { it.toModel() }, notesHaveTemplates,
    month?.let(::YearMonth), projected?.let(::Money), perAutomation?.map(::Money),
)

private fun AutomationLimitDto.toModel() = Automation.Cap(
    Money(amount), hold,
    when (period) { "daily" -> Automation.CapPeriod.Daily; "weekly" -> Automation.CapPeriod.Weekly; else -> Automation.CapPeriod.Monthly },
    start,
)

private fun Automation.Cap.toDto() = AutomationLimitDto(amount.minor, hold, period.name.lowercase(), start?.takeIf { period == Automation.CapPeriod.Weekly })

private fun AutomationAdjustmentDto.toModel(): Automation.Adjustment =
    if (kind == "percent") Automation.Adjustment.Percent(value) else Automation.Adjustment.Fixed(Money(value.toLong()))

private fun Automation.Adjustment.toDto() = when (this) {
    is Automation.Adjustment.Percent -> AutomationAdjustmentDto("percent", percent)
    is Automation.Adjustment.Fixed -> AutomationAdjustmentDto("fixed", amount.minor.toDouble())
}

internal fun AutomationDto.toModel(): Automation {
    val p = priority ?: 0
    return when (type) {
        "simple" -> Automation.Fixed(p, monthly?.let(::Money), limit?.toModel(), description)
        "periodic" -> Automation.Periodic(
            p, Money(amount ?: 0),
            when (every?.unit) { "day" -> Automation.PeriodUnit.Day; "week" -> Automation.PeriodUnit.Week; "year" -> Automation.PeriodUnit.Year; else -> Automation.PeriodUnit.Month },
            every?.count ?: 1, starting.orEmpty(), limit?.toModel(), description,
        )
        "by" -> Automation.SaveBy(
            p, Money(amount ?: 0), YearMonth(month ?: "2000-01"),
            repeat?.let { Automation.Repeat(it.unit == "year", it.count) }, spendFrom?.let(::YearMonth), description,
        )
        "schedule" -> Automation.CoverSchedule(p, schedule.orEmpty(), full ?: false, adjustment?.toModel(), description)
        "average" -> Automation.Average(p, months ?: 3, adjustment?.toModel(), description)
        "copy" -> Automation.Copy(p, monthsAgo ?: 1, description)
        "percentage" -> Automation.PercentOfIncome(p, percent ?: 0.0, of ?: Automation.ALL_INCOME, previous ?: false, description)
        "refill" -> Automation.Refill(p, limit?.toModel() ?: Automation.Cap(Money.Zero), description)
        "remainder" -> Automation.Remainder(weight ?: 1.0, limit?.toModel(), description)
        "goal" -> Automation.Goal(Money(amount ?: 0), description)
        else -> Automation.Unreadable(line ?: type, error ?: "This automation isn't supported by the app yet")
    }
}

internal fun Automation.toDto(): AutomationDto = when (this) {
    is Automation.Fixed -> AutomationDto("simple", priority, description, monthly = monthly?.minor, limit = cap?.toDto())
    is Automation.Periodic -> AutomationDto("periodic", priority, description, amount = amount.minor, every = AutomationEveryDto(unit.name.lowercase(), count), starting = starting, limit = cap?.toDto())
    is Automation.SaveBy -> AutomationDto(
        "by", priority, description, amount = amount.minor, month = month.raw,
        repeat = repeat?.let { AutomationRepeatDto(if (it.yearly) "year" else "month", it.count) }, spendFrom = spendFrom?.raw,
    )
    is Automation.CoverSchedule -> AutomationDto("schedule", priority, description, schedule = schedule, full = full, adjustment = adjustment?.toDto())
    is Automation.Average -> AutomationDto("average", priority, description, months = months, adjustment = adjustment?.toDto())
    is Automation.Copy -> AutomationDto("copy", priority, description, monthsAgo = monthsAgo)
    is Automation.PercentOfIncome -> AutomationDto("percentage", priority, description, percent = percent, of = of, previous = previousMonth)
    is Automation.Refill -> AutomationDto("refill", priority, description, limit = cap.toDto())
    is Automation.Remainder -> AutomationDto("remainder", description = description, weight = weight, limit = cap?.toDto())
    is Automation.Goal -> AutomationDto("goal", description = description, amount = amount.minor)
    is Automation.Unreadable -> error("Unreadable automations can't be saved")
}
