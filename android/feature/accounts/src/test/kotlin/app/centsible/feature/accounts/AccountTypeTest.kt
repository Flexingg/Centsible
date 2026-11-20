package app.centsible.feature.accounts

import app.centsible.core.model.Account
import app.centsible.core.model.AccountId
import app.centsible.core.model.AccountType
import app.centsible.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountTypeTest {
    private fun account(name: String, balance: Long = 100, offBudget: Boolean = false, type: AccountType? = null) =
        Account(AccountId(name), name, offBudget, closed = false, balance = Money(balance), type = type)

    @Test fun `a type set in Centsible wins over the guess`() {
        assertEquals(AccountType.Savings, account("Visa card", -500, type = AccountType.Savings).kind())
        assertEquals(AccountType.Mortgage, account("Wells Fargo 6975", -30_000_000, offBudget = true, type = AccountType.Mortgage).kind())
    }

    @Test fun `without one, the name and balance decide`() {
        assertEquals(AccountType.Mortgage, account("Home mortgage", -1, offBudget = true).kind())
        assertEquals(AccountType.Loan, account("Auto loan", -1, offBudget = true).kind())
        assertEquals(AccountType.Credit, account("Discover it").kind())
        assertEquals(AccountType.Investment, account("Roth IRA", offBudget = true).kind())
        assertEquals(AccountType.Savings, account("High-Yield Savings").kind())
        assertEquals(AccountType.Cash, account("Joint Checking").kind())
        assertEquals(AccountType.Credit, account("Everyday 1234", -50).kind())
        assertEquals(AccountType.Investment, account("Fidelity", offBudget = true).kind())
    }

    @Test fun `sections follow the types`() {
        val summary = AccountsSummary.from(listOf(account("Joint Checking"), account("Brokerage", offBudget = true, type = AccountType.Savings), account("Rainy day")))
        assertEquals(listOf(AccountType.Cash, AccountType.Savings), summary.sections.map { it.kind })
        assertEquals(2, summary.sections.first { it.kind == AccountType.Savings }.accounts.size)
    }
}
