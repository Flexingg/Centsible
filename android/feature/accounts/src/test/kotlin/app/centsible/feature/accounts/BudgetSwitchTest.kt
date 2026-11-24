package app.centsible.feature.accounts

import app.centsible.core.domain.SelectedBudget
import app.centsible.core.domain.Session
import app.centsible.core.model.Account
import app.centsible.core.model.AccountId
import app.centsible.core.model.AccountType
import app.centsible.core.model.CategoryId
import app.centsible.core.model.DeviceId
import app.centsible.core.model.Member
import app.centsible.core.model.MemberId
import app.centsible.core.model.Money
import app.centsible.core.model.Role
import app.centsible.core.testing.FakeAccountServices
import app.centsible.core.testing.FakeBudgetEngine
import app.centsible.core.testing.InMemorySessionStore
import app.centsible.core.testing.SampleHousehold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetSwitchTest {
    private fun account(name: String, offBudget: Boolean = false, type: AccountType? = null, closed: Boolean = false) =
        Account(AccountId(name), name, offBudget, closed, Money(100), type = type)

    @Test fun `investments, mortgages and loans on budget are suggested, unless kept there`() {
        assertTrue(account("Roth IRA").shouldBeOffBudget(emptySet())) // guessed from the name
        assertTrue(account("Wells 1234", type = AccountType.Mortgage).shouldBeOffBudget(emptySet()))
        assertTrue(account("Car", type = AccountType.Loan).shouldBeOffBudget(emptySet()))
        assertFalse(account("Roth IRA", offBudget = true).shouldBeOffBudget(emptySet()))
        assertFalse(account("Joint Checking").shouldBeOffBudget(emptySet()))
        assertFalse(account("Roth IRA").shouldBeOffBudget(setOf(AccountId("Roth IRA"))))
        assertFalse(account("Roth IRA", closed = true).shouldBeOffBudget(emptySet()))
        // A type set by hand wins over the name.
        assertFalse(account("401k", type = AccountType.Savings).shouldBeOffBudget(emptySet()))
    }

    private val engine = FakeBudgetEngine()
    private val services = FakeAccountServices()
    private val messages = mutableListOf<String>()
    private val session = Session(
        "https://b.example.com", "a", "r", null, null, Member(MemberId("m"), "Jo", Role.Owner, false, emptyList()), DeviceId("d"),
        selectedBudget = SampleHousehold.budget.id,
    )
    private val switcher = BudgetSwitcher(CoroutineScope(Dispatchers.Unconfined), engine, services, SelectedBudget(InMemorySessionStore(session))) { messages += it }

    @Test fun `moving off budget offers the usual category, and confirming a guess keeps its type`() = runBlocking {
        val checking = engine.accounts(SampleHousehold.budget.id).first { it.name == "Joint Checking" }
        val ira = engine.createAccount(SampleHousehold.budget.id, "Roth IRA", false, Money(100))
        switcher.start(ira, suggested = true)
        val s = switcher.current.value!!
        assertTrue(s.toOffBudget)
        assertEquals(CategoryId("c-savings"), s.category) // the one its transfers mostly use
        switcher.category(CategoryId("c-dining"))
        switcher.confirm()
        assertNull(switcher.current.value)
        assertEquals(Triple(ira.id, true, CategoryId("c-dining")), services.switched.single())
        assertEquals(AccountType.Investment, engine.accounts(SampleHousehold.budget.id).first { it.id == ira.id }.type)
        assertEquals("Roth IRA is off budget · 9 transfers categorized", messages.single())
        assertFalse(checking.offBudget)
    }

    @Test fun `keeping it on budget is remembered, and moving back on sends no category`() = runBlocking {
        val loan = engine.createAccount(SampleHousehold.budget.id, "Car loan", false, Money(-100), AccountType.Loan)
        switcher.start(loan, suggested = true)
        switcher.confirm(keep = true)
        assertEquals(Triple(loan.id, false, null), services.switched.single())
        assertTrue(loan.id in services.keptOnBudget(SampleHousehold.budget.id))
        assertEquals("Car loan stays on budget", messages.single())

        services.offBudget[loan.id] = true
        switcher.start(loan.copy(offBudget = true))
        assertFalse(switcher.current.value!!.toOffBudget)
        switcher.confirm()
        assertEquals(Triple(loan.id, false, null), services.switched.last())
        assertEquals("Car loan is on budget · 12 transfers uncategorized", messages.last())
    }
}
