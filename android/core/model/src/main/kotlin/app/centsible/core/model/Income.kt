package app.centsible.core.model

enum class IncomeMode { All, Categories }

enum class DeductionKind(val wire: String, val label: String) {
    Retirement("retirement", "401(k) / Retirement"),
    Tax("tax", "Tax"),
    Benefit("benefit", "Benefit"),
    Other("other", "Other"),
    ;

    companion object {
        fun of(wire: String?) = entries.firstOrNull { it.wire == wire } ?: Other
    }
}

enum class DeductionMode(val wire: String, val label: String) {
    Fixed("fixed", "Fixed ($)"),
    Percent("percent", "% of gross"),
    ;

    companion object {
        fun of(wire: String?) = entries.firstOrNull { it.wire == wire }
    }
}

data class PayStubDeduction(
    val name: String,
    val kind: DeductionKind,
    val amount: Money,
    val mode: DeductionMode? = null,
    val rate: Double? = null,
)

data class PayStubInput(
    val date: String,
    val employer: String? = null,
    val hours: Double? = null,
    val hourlyRate: Money? = null,
    val gross: Money,
    val deductions: List<PayStubDeduction>,
    val notes: String? = null,
)

data class PayStub(
    val id: String,
    val date: String,
    val employer: String?,
    val hours: Double?,
    val hourlyRate: Money?,
    val gross: Money,
    val deductions: List<PayStubDeduction>,
    val net: Money,
    val notes: String?,
)

data class LearnedDeduction(
    val name: String,
    val kind: DeductionKind,
    val mode: DeductionMode,
    val amount: Money,
    val rate: Double,
)

data class LearnedTemplate(
    val hourlyRate: Money?,
    val employer: String?,
    val deductions: List<LearnedDeduction>,
)

data class IncomeMonth(
    val month: YearMonth,
    val amount: Money,
    val count: Int,
    val deltaFromPrev: Money?,
    val deltaPercent: Double?,
)

data class IncomeMonthPoint(val month: YearMonth, val amount: Money)

data class IncomeSummary(
    val ytdTotal: Money,
    val prevYearTotal: Money,
    val monthlyAverage: Money,
    val projectedAnnual: Money,
    val elapsedMonths: Int,
    val remainingMonths: Int,
    val variationPercent: Double,
    val minMonth: IncomeMonthPoint?,
    val maxMonth: IncomeMonthPoint?,
)

data class PayStubByKind(
    val tax: Money,
    val retirement: Money,
    val benefit: Money,
    val other: Money,
)

data class DeductionBreakdown(
    val name: String,
    val kind: DeductionKind,
    val total: Money,
    val avgPercentOfGross: Double,
)

data class PayStubSummary(
    val stubCount: Int,
    val totalHours: Double,
    val totalGross: Money,
    val totalNet: Money,
    val totalDeductions: Money,
    val effectiveTaxRate: Double,
    val takeHomeRate: Double,
    val byKind: PayStubByKind,
    val byDeduction: List<DeductionBreakdown>,
    val projectedAnnualGross: Money,
    val projectedAnnualRetirement: Money,
    val projectedAnnualTax: Money,
)

data class IncomeOverview(
    val year: Int,
    val mode: IncomeMode,
    val categoryIds: List<CategoryId>,
    val months: List<IncomeMonth>,
    val summary: IncomeSummary,
    val payStubs: List<PayStub>,
    val payStubSummary: PayStubSummary,
    val template: LearnedTemplate,
)
