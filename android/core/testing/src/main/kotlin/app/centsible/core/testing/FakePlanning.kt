package app.centsible.core.testing

import app.centsible.core.domain.AccountServices
import app.centsible.core.domain.PlanningGateway
import app.centsible.core.domain.ReportsGateway
import app.centsible.core.model.AccountId
import app.centsible.core.model.AmountOp
import app.centsible.core.model.BudgetId
import app.centsible.core.model.CashFlowMonth
import app.centsible.core.model.CategoryId
import app.centsible.core.model.Frequency
import app.centsible.core.model.ImportOptions
import app.centsible.core.model.ImportPreview
import app.centsible.core.model.ImportResult
import app.centsible.core.model.ImportRow
import app.centsible.core.model.Job
import app.centsible.core.model.JobStatus
import app.centsible.core.model.Money
import app.centsible.core.model.NetWorthPoint
import app.centsible.core.model.PayeeId
import app.centsible.core.model.PayeeStat
import app.centsible.core.model.ReconcileResult
import app.centsible.core.model.ReconcileStatus
import app.centsible.core.model.Recurrence
import app.centsible.core.model.Rule
import app.centsible.core.model.RuleClause
import app.centsible.core.model.RuleDraft
import app.centsible.core.model.RuleValue
import app.centsible.core.model.Schedule
import app.centsible.core.model.ScheduleDraft
import app.centsible.core.model.SpendingCategory
import app.centsible.core.model.SpendingReport
import app.centsible.core.model.Tag
import app.centsible.core.model.YearMonth

object SamplePlanning {
    private fun monthly(day: Int) = Recurrence(Frequency.Monthly, 1, "2026-01-%02d".format(day))

    private fun bill(id: String, name: String, payee: String, amount: Long, next: String, day: Int, account: String = "acc-checking", auto: Boolean = false) = Schedule(
        id = id,
        name = name,
        nextDate = next,
        completed = false,
        postsTransaction = auto,
        payeeId = PayeeId(payee),
        accountId = AccountId(account),
        amount = Money(amount),
        amountMax = null,
        amountOp = AmountOp.IsApprox,
        recurrence = monthly(day),
        date = null,
        upcoming = listOf(next, next.replaceRange(5, 7, "%02d".format(next.substring(5, 7).toInt() % 12 + 1))),
    )

    val schedules = listOf(
        bill("s-mortgage", "Mortgage", "p-bank", -245_000, "2026-10-01", 1, auto = true),
        bill("s-pge", "PG&E", "p-pge", -12_450, "2026-10-20", 20),
        bill("s-netflix", "Netflix", "p-netflix", -2_299, "2026-09-29", 29, account = "acc-visa"),
        bill("s-phone", "Phone", "p-verizon", -8_500, "2026-10-04", 4, account = "acc-visa"),
        bill("s-gym", "Gym", "p-gym", -4_900, "2026-10-12", 12, account = "acc-amex"),
        Schedule(
            id = "s-payroll",
            name = "Payroll",
            nextDate = "2026-10-09",
            completed = false,
            postsTransaction = true,
            payeeId = PayeeId("p-employer"),
            accountId = AccountId("acc-checking"),
            amount = Money(530_000),
            amountMax = null,
            amountOp = AmountOp.Is,
            recurrence = Recurrence(Frequency.Weekly, 2, "2026-09-11"),
            date = null,
            upcoming = listOf("2026-10-09", "2026-10-23", "2026-11-06"),
        ),
    )

    val payeeNames = mapOf(
        "p-bank" to "Home Loans Inc", "p-pge" to "PG&E", "p-netflix" to "Netflix", "p-verizon" to "Verizon",
        "p-gym" to "Crunch Fitness", "p-employer" to "Employer Payroll", "p-Trader Joe's" to "Trader Joe's", "p-Costco" to "Costco",
    )

    val rules = listOf(
        Rule(
            "r-1", null, "and",
            listOf(RuleClause("payee", "is", RuleValue.Text("p-Trader Joe's"), "id")),
            listOf(RuleClause("category", "set", RuleValue.Text("c-groceries"), "id")),
            null,
        ),
        Rule(
            "r-2", "pre", "or",
            listOf(RuleClause("imported_payee", "contains", RuleValue.Text("AMZN"), "string"), RuleClause("imported_payee", "contains", RuleValue.Text("Amazon"), "string")),
            listOf(RuleClause("payee", "set", RuleValue.Text("p-amazon"), "id")),
            null,
        ),
        Rule(
            "r-3", null, "and",
            listOf(RuleClause("notes", "contains", RuleValue.Text("#kids"), "string"), RuleClause("amount", "lt", RuleValue.Number(-5_000), "number")),
            listOf(RuleClause("category", "set", RuleValue.Text("c-kids"), "id"), RuleClause("notes", "append-notes", RuleValue.Text(" (reviewed)"), "string")),
            null,
        ),
        Rule("r-sched", null, "and", emptyList(), listOf(RuleClause(null, "link-schedule", RuleValue.Text("s-netflix"), "id")), "s-netflix"),
    )

    val payees = listOf(
        PayeeStat(PayeeId("p-Trader Joe's"), "Trader Joe's", null, 42, 1),
        PayeeStat(PayeeId("p-Costco"), "Costco", null, 18, 0),
        PayeeStat(PayeeId("p-amazon"), "Amazon", null, 31, 1),
        PayeeStat(PayeeId("p-amzn-mktp"), "AMZN Mktp US", null, 3, 0),
        PayeeStat(PayeeId("p-netflix"), "Netflix", null, 12, 1),
        PayeeStat(PayeeId("p-pge"), "PG&E", null, 12, 0),
        PayeeStat(PayeeId("p-old"), "Old Coffee Shop", null, 0, 0),
        PayeeStat(PayeeId("p-to-savings"), "High-Yield Savings", AccountId("acc-savings"), 6, 0),
    )

    val tags = listOf(Tag("t1", "groceries", "#1baf7a", null), Tag("t2", "vacation", "#2a78d6", "Trips"), Tag("t3", "kids", null, null))

    val cashFlow = listOf(
        "2026-04" to (1_060_000L to -812_300L), "2026-05" to (1_060_000L to -905_550L), "2026-06" to (1_120_000L to -1_190_400L),
        "2026-07" to (1_060_000L to -870_120L), "2026-08" to (1_090_000L to -798_600L), "2026-09" to (1_120_000L to -426_106L),
    ).map { (m, v) -> CashFlowMonth(YearMonth(m), Money(v.first), Money(v.second), Money(v.first + v.second)) }

    val spending = SpendingReport(
        Money(-426_106),
        listOf(
            SpendingCategory(CategoryId("c-rent"), "Mortgage", "Home", Money(-245_000)),
            SpendingCategory(CategoryId("c-groceries"), "Groceries", "Food", Money(-61_220)),
            SpendingCategory(CategoryId("c-dining"), "Dining Out", "Food", Money(-34_580)),
            SpendingCategory(CategoryId("c-utilities"), "Utilities", "Home", Money(-21_450)),
            SpendingCategory(CategoryId("c-shopping"), "Shopping", "Lifestyle", Money(-18_990)),
            SpendingCategory(CategoryId("c-internet"), "Internet & Phone", "Home", Money(-13_998)),
            SpendingCategory(CategoryId("c-gas"), "Gas", "Transportation", Money(-12_760)),
            SpendingCategory(CategoryId("c-kids"), "Kids", "Lifestyle", Money(-11_340)),
            SpendingCategory(null, "Uncategorized", null, Money(-6_768)),
        ),
    )

    val netWorth = listOf(-19_890_000L, -19_620_000L, -19_410_000L, -19_020_000L, -18_710_000L, -18_430_000L, -18_390_000L, -18_120_000L, -18_050_000L, -17_830_000L, -17_990_000L, -17_975_300L)
        .mapIndexed { i, nw -> NetWorthPoint(YearMonth("2025-10").plus(i), Money(13_000_000 + i * 60_000L), Money(nw - 13_000_000 - i * 60_000L), Money(nw)) }
}

class FakePlanningGateway : PlanningGateway {
    val schedules = SamplePlanning.schedules.toMutableList()
    val rules = SamplePlanning.rules.toMutableList()
    val createdRules = mutableListOf<RuleDraft>()
    val notes = mutableMapOf<CategoryId, String?>()

    override suspend fun schedules(budget: BudgetId, upcoming: Int) = schedules.toList()
    override suspend fun createSchedule(budget: BudgetId, draft: ScheduleDraft) = draft.toSchedule("s-${schedules.size}").also { schedules += it }
    override suspend fun updateSchedule(budget: BudgetId, id: String, draft: ScheduleDraft) = draft.toSchedule(id).also { s -> schedules.replaceAll { if (it.id == id) s else it } }
    override suspend fun deleteSchedule(budget: BudgetId, id: String) { schedules.removeAll { it.id == id } }
    override suspend fun skipSchedule(budget: BudgetId, id: String) = schedules.first { it.id == id }.let { s -> s.copy(nextDate = s.upcoming.getOrNull(1), upcoming = s.upcoming.drop(1)) }.also { s -> schedules.replaceAll { if (it.id == id) s else it } }
    override suspend fun postSchedule(budget: BudgetId, id: String) = skipSchedule(budget, id)

    override suspend fun rules(budget: BudgetId) = rules.toList()
    override suspend fun createRule(budget: BudgetId, draft: RuleDraft): Rule {
        createdRules += draft
        return Rule("r-new-${rules.size}", draft.stage, draft.conditionsOp, draft.conditions, draft.actions, null).also { rules += it }
    }
    override suspend fun updateRule(budget: BudgetId, id: String, draft: RuleDraft) = Rule(id, draft.stage, draft.conditionsOp, draft.conditions, draft.actions, null).also { r -> rules.replaceAll { if (it.id == id) r else it } }
    override suspend fun deleteRule(budget: BudgetId, id: String) { rules.removeAll { it.id == id } }

    override suspend fun payeeStats(budget: BudgetId) = SamplePlanning.payees
    override suspend fun renamePayee(budget: BudgetId, id: PayeeId, name: String) = Unit
    override suspend fun mergePayees(budget: BudgetId, into: PayeeId, merge: List<PayeeId>) = Unit
    override suspend fun deletePayee(budget: BudgetId, id: PayeeId) = Unit

    override suspend fun tags(budget: BudgetId) = SamplePlanning.tags
    override suspend fun createTag(budget: BudgetId, tag: String, color: String?) = Tag("t-new", tag, color, null)
    override suspend fun updateTag(budget: BudgetId, id: String, tag: String?, color: String?) = Tag(id, tag ?: "", color, null)
    override suspend fun deleteTag(budget: BudgetId, id: String) = Unit

    override suspend fun categoryNote(budget: BudgetId, category: CategoryId) = notes[category]
    override suspend fun setCategoryNote(budget: BudgetId, category: CategoryId, note: String?) { notes[category] = note }
    override suspend fun applyTemplates(budget: BudgetId, month: YearMonth, overwrite: Boolean) = SampleHousehold.budgetMonth to "Goals applied"

    private fun ScheduleDraft.toSchedule(id: String) = Schedule(
        id, name, recurrence?.start ?: date, false, postsTransaction, payeeId, accountId, amount, amountMax, amountOp, recurrence, date,
        listOfNotNull(recurrence?.start ?: date),
    )
}

class FakeAccountServices : AccountServices {
    var jobStatus = JobStatus.Succeeded
    val imported = mutableListOf<String>()
    override suspend fun startBankSync(budget: BudgetId, account: AccountId?) = Job("job-1", JobStatus.Running, null, null)
    override suspend fun job(id: String) = Job(id, jobStatus, if (jobStatus == JobStatus.Failed) "Not linked" else null, 3)
    override suspend fun previewImport(budget: BudgetId, account: AccountId, fileName: String, bytes: ByteArray, options: ImportOptions) = ImportPreview(
        rows = listOf(
            ImportRow("2026-09-20", Money(-4_567), "Farmers Market", "Saturday"),
            ImportRow("2026-09-21", Money(-800), "Parking", null),
            ImportRow("2026-09-22", Money(-1_250), "Bookstore", "novel"),
        ),
        errors = emptyList(),
        columns = emptyList(),
        mapping = null,
        newCount = 2,
        matchedCount = 1,
    )
    override suspend fun importFile(budget: BudgetId, account: AccountId, fileName: String, bytes: ByteArray, options: ImportOptions) =
        ImportResult(2, 1, emptyList()).also { imported += fileName }
    override suspend fun reconcileStatus(budget: BudgetId, account: AccountId) = ReconcileStatus(Money(-58_234), Money(-3_500), Money(-61_734))
    override suspend fun reconcile(budget: BudgetId, account: AccountId, statementBalance: Money, createAdjustment: Boolean): ReconcileResult {
        val diff = statementBalance - Money(-58_234)
        return if (!diff.isZero && !createAdjustment) ReconcileResult(false, diff, null, 0) else ReconcileResult(true, diff, null, 14)
    }
}

class FakeReports : ReportsGateway {
    override suspend fun cashFlow(budget: BudgetId, start: YearMonth, end: YearMonth) = SamplePlanning.cashFlow.filter { it.month in start..end }
    override suspend fun spending(budget: BudgetId, start: YearMonth, end: YearMonth) = SamplePlanning.spending
    override suspend fun netWorth(budget: BudgetId, months: Int) = SamplePlanning.netWorth.takeLast(months)
}
