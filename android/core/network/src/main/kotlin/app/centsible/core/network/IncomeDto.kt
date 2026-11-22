package app.centsible.core.network

import kotlinx.serialization.Serializable

@Serializable
data class IncomeSettingsDto(
    val mode: String,
    val categoryIds: List<String> = emptyList(),
)

@Serializable
data class PayStubDeductionDto(
    val name: String,
    val kind: String,
    val amount: Long,
    val mode: String? = null,
    val rate: Double? = null,
)

@Serializable
data class PayStubInputDto(
    val date: String,
    val employer: String? = null,
    val hours: Double? = null,
    val hourlyRate: Long? = null,
    val gross: Long,
    val deductions: List<PayStubDeductionDto> = emptyList(),
    val notes: String? = null,
)

@Serializable
data class PayStubDto(
    val id: String,
    val date: String,
    val employer: String? = null,
    val hours: Double? = null,
    val hourlyRate: Long? = null,
    val gross: Long,
    val deductions: List<PayStubDeductionDto> = emptyList(),
    val net: Long,
    val notes: String? = null,
)

@Serializable
data class LearnedDeductionDto(
    val name: String,
    val kind: String,
    val mode: String,
    val amount: Long,
    val rate: Double = 0.0,
)

@Serializable
data class LearnedTemplateDto(
    val hourlyRate: Long? = null,
    val employer: String? = null,
    val deductions: List<LearnedDeductionDto> = emptyList(),
)

@Serializable
data class IncomeMonthDto(
    val month: String,
    val amount: Long,
    val count: Int = 0,
    val deltaFromPrev: Long? = null,
    val deltaPercent: Double? = null,
)

@Serializable
data class IncomeMonthPointDto(
    val month: String,
    val amount: Long,
)

@Serializable
data class IncomeSummaryDto(
    val ytdTotal: Long = 0,
    val prevYearTotal: Long = 0,
    val monthlyAverage: Long = 0,
    val projectedAnnual: Long = 0,
    val elapsedMonths: Int = 0,
    val remainingMonths: Int = 0,
    val variationPercent: Double = 0.0,
    val minMonth: IncomeMonthPointDto? = null,
    val maxMonth: IncomeMonthPointDto? = null,
)

@Serializable
data class PayStubByKindDto(
    val tax: Long = 0,
    val retirement: Long = 0,
    val benefit: Long = 0,
    val other: Long = 0,
)

@Serializable
data class DeductionBreakdownDto(
    val name: String,
    val kind: String,
    val total: Long,
    val avgPercentOfGross: Double = 0.0,
)

@Serializable
data class PayStubSummaryDto(
    val stubCount: Int = 0,
    val totalHours: Double = 0.0,
    val totalGross: Long = 0,
    val totalNet: Long = 0,
    val totalDeductions: Long = 0,
    val effectiveTaxRate: Double = 0.0,
    val takeHomeRate: Double = 0.0,
    val byKind: PayStubByKindDto = PayStubByKindDto(),
    val byDeduction: List<DeductionBreakdownDto> = emptyList(),
    val projectedAnnualGross: Long = 0,
    val projectedAnnualRetirement: Long = 0,
    val projectedAnnualTax: Long = 0,
)

@Serializable
data class IncomeOverviewDto(
    val year: Int,
    val mode: String = "all",
    val categoryIds: List<String> = emptyList(),
    val months: List<IncomeMonthDto> = emptyList(),
    val summary: IncomeSummaryDto = IncomeSummaryDto(),
    val payStubs: List<PayStubDto> = emptyList(),
    val payStubSummary: PayStubSummaryDto = PayStubSummaryDto(),
    val template: LearnedTemplateDto = LearnedTemplateDto(),
)
