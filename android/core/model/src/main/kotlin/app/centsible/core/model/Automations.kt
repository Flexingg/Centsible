package app.centsible.core.model

/**
 * Budget automations (Actual's "programmable budgets"): rules that fill a category's
 * budget each month. [priority]: lower runs first; only priority 0 may take To Budget
 * below zero.
 */
sealed interface Automation {
    val description: String?

    /** A fixed amount each month, and/or a cap ("up to"). */
    data class Fixed(val priority: Int, val monthly: Money?, val cap: Cap?, override val description: String? = null) : Automation
    /** An amount every N days, weeks, months or years from a start date. */
    data class Periodic(val priority: Int, val amount: Money, val unit: PeriodUnit, val count: Int, val starting: String, val cap: Cap?, override val description: String? = null) : Automation
    /** Save a total by a month (optionally repeating; optionally spending along the way). */
    data class SaveBy(val priority: Int, val amount: Money, val month: YearMonth, val repeat: Repeat?, val spendFrom: YearMonth?, override val description: String? = null) : Automation
    /** Cover a schedule: save for the next one, or (full) budget each when it's due. */
    data class CoverSchedule(val priority: Int, val schedule: String, val full: Boolean, val adjustment: Adjustment?, override val description: String? = null) : Automation
    /** The average of the last N months' spending. */
    data class Average(val priority: Int, val months: Int, val adjustment: Adjustment?, override val description: String? = null) : Automation
    /** Whatever was budgeted N months ago. */
    data class Copy(val priority: Int, val monthsAgo: Int, override val description: String? = null) : Automation
    /** A share of income: all of it, what's available to budget, or one income category (by id). */
    data class PercentOfIncome(val priority: Int, val percent: Double, val of: String, val previousMonth: Boolean, override val description: String? = null) : Automation
    /** Top the category up to a cap. */
    data class Refill(val priority: Int, val cap: Cap, override val description: String? = null) : Automation
    /** A weighted share of whatever is left to budget after everything else. */
    data class Remainder(val weight: Double, val cap: Cap?, override val description: String? = null) : Automation
    /** A long-term balance goal (for the goal indicator; budgets nothing itself). */
    data class Goal(val amount: Money, override val description: String? = null) : Automation
    /** A #template line Actual couldn't read. */
    data class Unreadable(val line: String, val error: String) : Automation {
        override val description: String? get() = null
    }

    data class Cap(val amount: Money, val hold: Boolean = false, val period: CapPeriod = CapPeriod.Monthly, val start: String? = null)
    enum class CapPeriod { Daily, Weekly, Monthly }
    enum class PeriodUnit { Day, Week, Month, Year }
    data class Repeat(val yearly: Boolean, val count: Int)

    sealed interface Adjustment {
        data class Percent(val percent: Double) : Adjustment
        data class Fixed(val amount: Money) : Adjustment
    }

    companion object {
        const val ALL_INCOME = "all income"
        const val AVAILABLE_FUNDS = "available funds"
    }
}

val Automation.priorityOrNull: Int?
    get() = when (this) {
        is Automation.Fixed -> priority
        is Automation.Periodic -> priority
        is Automation.SaveBy -> priority
        is Automation.CoverSchedule -> priority
        is Automation.Average -> priority
        is Automation.Copy -> priority
        is Automation.PercentOfIncome -> priority
        is Automation.Refill -> priority
        else -> null
    }

/** Where a category's automations come from. */
enum class AutomationSource { Automations, Notes, None }

data class CategoryAutomations(
    val categoryId: CategoryId,
    val source: AutomationSource,
    val automations: List<Automation>,
    /** The notes still hold #template/#goal lines. */
    val notesHaveTemplates: Boolean,
    val month: YearMonth?,
    val projected: Money?,
    val perAutomation: List<Money>?,
)

data class AutomationCategory(
    val categoryId: CategoryId,
    val name: String,
    val groupId: CategoryGroupId,
    val groupName: String,
    val isIncome: Boolean,
    val hidden: Boolean,
    val source: AutomationSource,
    val automations: List<Automation>,
    val projected: Money?,
)

data class AutomationPreview(val projected: Money, val perAutomation: List<Money>)

data class AutomationRun(val ok: Boolean, val message: String, val details: String?)
