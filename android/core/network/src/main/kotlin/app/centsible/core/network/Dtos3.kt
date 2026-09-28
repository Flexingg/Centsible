package app.centsible.core.network

import kotlinx.serialization.Serializable

// Budget autopilot, goals and forecast (see contract/openapi.yaml, tag "plan").

@Serializable data class AveragesDto(val avg3: Long = 0, val avg6: Long = 0, val avg12: Long = 0)
@Serializable data class SuggestionDto(
    val categoryId: String,
    val name: String,
    val groupName: String = "",
    val budgeted: Long = 0,
    val balance: Long = 0,
    val lastMonthSpent: Long = 0,
    val average: AveragesDto = AveragesDto(),
    val suggested: AveragesDto = AveragesDto(),
    val monthsOfHistory: Int = 0,
)
@Serializable data class OverspentDto(val categoryId: String, val name: String, val amount: Long)
@Serializable data class CoverMoveDto(val from: String, val fromName: String, val to: String, val toName: String, val amount: Long)
@Serializable data class CoverPlanDto(val moves: List<CoverMoveDto> = emptyList(), val uncovered: Long = 0)
@Serializable data class AutopilotDto(
    val month: String,
    val toBudget: Long = 0,
    val suggestions: List<SuggestionDto> = emptyList(),
    val overspent: List<OverspentDto> = emptyList(),
    val cover: CoverPlanDto = CoverPlanDto(),
)
@Serializable data class ApplyAutopilotDto(val basis: Int, val categoryIds: List<String>? = null)
@Serializable data class AutopilotAppliedDto(val month: BudgetMonthDto, val changed: Int = 0)
@Serializable data class CoverResultDto(val month: BudgetMonthDto, val moves: List<CoverMoveDto> = emptyList(), val uncovered: Long = 0)

@Serializable data class GoalDto(
    val categoryId: String,
    val name: String,
    val groupName: String = "",
    val kind: String,
    val target: Long,
    val targetMonth: String? = null,
    val balance: Long = 0,
    val budgetedThisMonth: Long = 0,
    val progress: Float = 0f,
    val remaining: Long = 0,
    val monthlyNeeded: Long? = null,
    val avgContribution: Long = 0,
    val projectedMonth: String? = null,
    val status: String = "stalled",
    val line: String = "",
)
@Serializable data class GoalsDto(val month: String, val items: List<GoalDto> = emptyList())
@Serializable data class GoalInputDto(val kind: String, val target: Long, val targetMonth: String? = null)

@Serializable data class ForecastEventDto(
    val date: String,
    val scheduleId: String,
    val name: String,
    val payeeName: String? = null,
    val accountId: String? = null,
    val accountName: String? = null,
    val amount: Long,
    val internalTransfer: Boolean = false,
    val overdue: Boolean = false,
)
@Serializable data class ForecastDayDto(val date: String, val balance: Long, val scheduled: Long = 0, val typical: Long = 0)
@Serializable data class ForecastLowDto(val date: String, val balance: Long)
@Serializable data class ForecastDto(
    val from: String,
    val to: String,
    val accountIds: List<String> = emptyList(),
    val startingBalance: Long = 0,
    val typicalDaily: Long = 0,
    val events: List<ForecastEventDto> = emptyList(),
    val days: List<ForecastDayDto> = emptyList(),
    val lowest: ForecastLowDto,
    /** Older bridges don't send it. */
    val paid: List<PaidBillDto> = emptyList(),
)
@Serializable data class PaidBillDto(val date: String, val scheduleId: String, val name: String, val amount: Long)
