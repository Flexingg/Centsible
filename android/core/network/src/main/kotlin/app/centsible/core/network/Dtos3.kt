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

@Serializable data class MortgageRowDto(
    val n: Int,
    val date: String,
    val payment: Long,
    val interest: Long,
    val principal: Long,
    val extra: Long = 0,
    val balance: Long,
    val paidOn: String? = null,
    val paidAmount: Long? = null,
)
@Serializable data class MortgageDto(
    val id: String,
    val name: String,
    val principal: Long,
    val rate: Double,
    val termMonths: Int,
    val firstPayment: String,
    val escrow: Long = 0,
    val extra: Long = 0,
    val payeeId: String? = null,
    val paymentAccountId: String? = null,
    val loanAccountId: String? = null,
    val homeAccountId: String? = null,
    val loanSynced: Boolean = false,
    val monthlyPayment: Long,
    val monthlyTotal: Long,
    val balance: Long,
    val scheduledBalance: Long = 0,
    val aheadBy: Long = 0,
    val paymentsMade: Int = 0,
    val paymentsLeft: Int = 0,
    val payoffDate: String,
    val originalPayoffDate: String,
    val interestPaid: Long = 0,
    val interestLeft: Long = 0,
    val interestSaved: Long = 0,
    val homeValue: Long? = null,
    val equity: Long? = null,
    val paymentsFound: Int = 0,
    val unrecorded: Int = 0,
    val schedule: List<MortgageRowDto> = emptyList(),
)
@Serializable data class MortgagesDto(val items: List<MortgageDto> = emptyList())
@Serializable data class MortgageInputDto(
    val name: String,
    val principal: Long,
    val rate: Double,
    val termMonths: Int,
    val firstPayment: String,
    val escrow: Long = 0,
    val extra: Long = 0,
    val payeeId: String? = null,
    val paymentAccountId: String? = null,
    val loanAccountId: String? = null,
    val homeAccountId: String? = null,
    val createLoanAccount: Boolean? = null,
    val currentBalance: Long? = null,
    val homeValue: Long? = null,
)
@Serializable data class HomeValueDto(val value: Long)
@Serializable data class AnnualBudgetDto(
    val categoryId: String,
    val name: String = "",
    val amount: Long,
    val startMonth: Int = 1,
    val monthIndex: Int = 0,
    val budgetedBefore: Long = 0,
    val remaining: Long = 0,
    val spentThisMonth: Long = 0,
    val carryIn: Long = 0,
    val budgeted: Long = 0,
    val suggested: Long = 0,
)
@Serializable data class AnnualBudgetsDto(val month: String, val items: List<AnnualBudgetDto> = emptyList())
@Serializable data class AnnualBudgetInputDto(val amount: Long, val startMonth: Int = 1)
@Serializable data class RecordedDto(val recorded: Int = 0, val principal: Long = 0)

@Serializable data class TargetMonthDto(val month: String, val value: Long, val goal: Long)
@Serializable data class TargetDto(
    val id: String,
    val kind: String,
    val name: String = "",
    val accountId: String? = null,
    val categoryId: String? = null,
    val groupId: String? = null,
    val amount: Long? = null,
    val percentOfIncome: Double? = null,
    val targetMonth: String? = null,
    val current: Long = 0,
    val goal: Long = 0,
    val progress: Float = 0f,
    val status: String = "on-track",
    val remaining: Long = 0,
    val monthlyNeeded: Long? = null,
    val avgChange: Long = 0,
    val projectedMonth: String? = null,
    val income: Long = 0,
    val pace: Float = 1f,
    val monthsKept: Int = 0,
    val missing: Boolean = false,
    val history: List<TargetMonthDto> = emptyList(),
)
@Serializable data class TargetsDto(val month: String, val items: List<TargetDto> = emptyList())
@Serializable data class TargetInputDto(
    val kind: String,
    val name: String? = null,
    val accountId: String? = null,
    val categoryId: String? = null,
    val amount: Long? = null,
    val percentOfIncome: Double? = null,
    val targetMonth: String? = null,
)

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
    val estimate: String? = null,
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
