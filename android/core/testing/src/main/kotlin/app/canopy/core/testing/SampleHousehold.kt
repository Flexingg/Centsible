package app.canopy.core.testing

import app.canopy.core.model.Account
import app.canopy.core.model.AccountId
import app.canopy.core.model.Budget
import app.canopy.core.model.BudgetCategory
import app.canopy.core.model.BudgetGroup
import app.canopy.core.model.BudgetId
import app.canopy.core.model.BudgetMonth
import app.canopy.core.model.BudgetType
import app.canopy.core.model.CategoryGroupId
import app.canopy.core.model.CategoryId
import app.canopy.core.model.Money
import app.canopy.core.model.PayeeId
import app.canopy.core.model.Transaction
import app.canopy.core.model.TransactionId
import app.canopy.core.model.YearMonth
import app.canopy.core.model.sum

/** Realistic household data for previews, screenshot tests and fakes. */
object SampleHousehold {
    val budget = Budget(BudgetId("budget-household"), "Household", encrypted = false)
    val month = YearMonth("2026-09")

    val accounts = listOf(
        Account(AccountId("acc-checking"), "Joint Checking", offBudget = false, closed = false, balance = Money(832_166)),
        Account(AccountId("acc-savings"), "High-Yield Savings", offBudget = false, closed = false, balance = Money(2_450_000)),
        Account(AccountId("acc-visa"), "Visa Signature", offBudget = false, closed = false, balance = Money(-61_734)),
        Account(AccountId("acc-amex"), "Amex Gold", offBudget = false, closed = false, balance = Money(-18_220)),
        Account(AccountId("acc-401k"), "401(k)", offBudget = true, closed = false, balance = Money(8_812_500)),
        Account(AccountId("acc-brokerage"), "Brokerage", offBudget = true, closed = false, balance = Money(1_250_000)),
        Account(AccountId("acc-mortgage"), "Mortgage", offBudget = true, closed = false, balance = Money(-31_240_000)),
    )

    private fun cat(id: String, name: String, budgeted: Long, spent: Long, rollover: Long = 0, carryover: Boolean = false) =
        BudgetCategory(
            id = CategoryId(id),
            name = name,
            hidden = false,
            budgeted = Money(budgeted),
            spent = Money(-spent),
            balance = Money(budgeted - spent + rollover),
            received = Money.Zero,
            carryover = carryover,
        )

    private fun group(id: String, name: String, vararg cats: BudgetCategory) = BudgetGroup(
        id = CategoryGroupId(id),
        name = name,
        isIncome = false,
        hidden = false,
        budgeted = cats.map { it.budgeted }.sum(),
        spent = cats.map { it.spent }.sum(),
        balance = cats.map { it.balance }.sum(),
        received = Money.Zero,
        categories = cats.toList(),
    )

    val groups = listOf(
        group(
            "g-home", "Home",
            cat("c-rent", "Mortgage", 245_000, 245_000),
            cat("c-utilities", "Utilities", 32_000, 21_450),
            cat("c-internet", "Internet & Phone", 14_000, 13_998),
        ),
        group(
            "g-food", "Food",
            cat("c-groceries", "Groceries", 90_000, 61_220, rollover = 4_300, carryover = true),
            cat("c-dining", "Dining Out", 30_000, 34_580),
            cat("c-coffee", "Coffee", 6_000, 2_150),
        ),
        group(
            "g-transport", "Transportation",
            cat("c-gas", "Gas", 20_000, 12_760),
            cat("c-car", "Car Maintenance", 10_000, 0, rollover = 25_000),
        ),
        group(
            "g-life", "Lifestyle",
            cat("c-shopping", "Shopping", 25_000, 18_990),
            cat("c-fun", "Entertainment", 15_000, 4_599),
            cat("c-kids", "Kids", 20_000, 11_340),
        ),
        group(
            "g-goals", "Savings Goals",
            cat("c-vacation", "Vacation", 40_000, 0, rollover = 160_000),
            cat("c-emergency", "Emergency Fund", 50_000, 0, rollover = 600_000),
        ),
    )

    private val income = BudgetGroup(
        id = CategoryGroupId("g-income"),
        name = "Income",
        isIncome = true,
        hidden = false,
        budgeted = Money.Zero,
        spent = Money.Zero,
        balance = Money.Zero,
        received = Money(1_120_000),
        categories = listOf(
            BudgetCategory(CategoryId("c-salary"), "Salary", false, Money.Zero, Money.Zero, Money.Zero, Money(1_060_000), false),
            BudgetCategory(CategoryId("c-other-income"), "Other Income", false, Money.Zero, Money.Zero, Money.Zero, Money(60_000), false),
        ),
    )

    val budgetMonth: BudgetMonth
        get() {
            val budgeted = groups.map { it.budgeted }.sum()
            val spent = groups.map { it.spent }.sum()
            return BudgetMonth(
                month = month,
                budgetType = BudgetType.Envelope,
                toBudget = Money(1_120_000 + 164_000) - budgeted,
                incomeAvailable = Money(1_120_000 + 164_000),
                lastMonthOverspent = Money.Zero,
                forNextMonth = Money.Zero,
                fromLastMonth = Money(164_000),
                totalBudgeted = budgeted,
                totalIncome = Money(1_120_000),
                totalSpent = spent,
                totalBalance = groups.map { it.balance }.sum(),
                groups = groups + income,
            )
        }

    private var seq = 0
    private fun tx(date: String, payee: String, amount: Long, category: String?, account: String = "acc-visa", notes: String? = null) =
        Transaction(
            id = TransactionId("tx-${seq++}"),
            accountId = AccountId(account),
            date = date,
            amount = Money(amount),
            payeeId = PayeeId("p-$payee"),
            payeeName = payee,
            categoryId = category?.let(::CategoryId),
            notes = notes,
            cleared = true,
            reconciled = false,
            isTransfer = false,
            isParent = false,
            subtransactions = emptyList(),
        )

    val transactions = listOf(
        tx("2026-09-26", "Trader Joe's", -8_734, "c-groceries"),
        tx("2026-09-26", "Blue Bottle Coffee", -1_150, "c-coffee", account = "acc-amex"),
        tx("2026-09-25", "Shell", -5_210, "c-gas"),
        tx("2026-09-24", "Netflix", -2_299, "c-fun", notes = "#subscriptions"),
        tx("2026-09-24", "Chipotle", -3_418, "c-dining", account = "acc-amex"),
        tx("2026-09-22", "Target", -6_790, "c-shopping"),
        tx("2026-09-20", "PG&E", -12_450, "c-utilities", account = "acc-checking"),
        tx("2026-09-15", "Employer Payroll", 530_000, "c-salary", account = "acc-checking"),
        tx("2026-09-12", "Costco", -15_870, "c-groceries"),
        tx("2026-09-10", "Soccer Club", -11_340, "c-kids", account = "acc-checking"),
    )
}
