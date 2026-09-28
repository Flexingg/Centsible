package app.centsible.core.engine.bridge

import app.centsible.core.domain.BridgeException
import app.centsible.core.domain.ClaimBridge
import app.centsible.core.domain.userMessage
import app.centsible.core.model.ActualSetup
import app.centsible.core.model.BridgeAddress
import app.centsible.core.model.Money
import app.centsible.core.model.Role
import app.centsible.core.model.YearMonth
import app.centsible.core.network.BridgeApi
import app.centsible.core.network.BridgeClient
import app.centsible.core.testing.InMemorySessionStore
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate

/**
 * A first-time household, from the app: a brand-new Actual server with no password, a
 * bridge with no owner and no ACTUAL_PASSWORD. Setup, then the first budget.
 *
 *   cd bridge && npx tsx test/support/e2e-server.ts 8788 --fresh
 *   E2E_SETUP_URL=http://127.0.0.1:8788 E2E_SETUP_CODE=E2E-SETUP ./gradlew :core:engine-bridge:test --tests '*FirstRunEndToEndTest*'
 */
class FirstRunEndToEndTest {
    @Test
    fun `a new household sets up the bridge and its first budget from the app`() = runBlocking {
        val url = System.getProperty("e2e.setupUrl")
        val code = System.getProperty("e2e.setupCode")
        assumeTrue("E2E_SETUP_URL/E2E_SETUP_CODE not set; skipping", url != null && code != null)

        val sessions = InMemorySessionStore()
        val api = BridgeApi(BridgeClient(sessions, OkHttp.create()))
        val setup = BridgeSetupGateway(api)
        val engine = BridgeBudgetEngine(api)
        val address = BridgeAddress(url!!)

        val status = setup.status(address)
        assertTrue("fresh bridge needs an owner", status.needsOwner)
        assertEquals(ActualSetup.NeedsPassword, status.actual)

        // A typo in the code explains itself instead of "signed out".
        val wrong = runCatching { setup.claim(address, "NOPE-NOPE", "Jo", "Pixel 9", "household-pass") }.exceptionOrNull()
        assertTrue("wrong code: $wrong", wrong is BridgeException.Validation && wrong.userMessage().contains("setup code"))

        ClaimBridge(setup, sessions, engine)(address, code!!.lowercase(), "Jo", "Pixel 9", "household-pass")
        val session = sessions.current()!!
        assertEquals(Role.Owner, session.member.role)
        assertEquals(false, setup.status(address).needsOwner)

        // No budgets on a new server: create the first one, as the budget picker does.
        assertEquals(emptyList<Any>(), engine.budgets())
        val budget = engine.createBudget("Household")
        sessions.selectBudget(budget.id)
        assertEquals(listOf("Household"), engine.budgets().map { it.name })

        val account = engine.createAccount(budget.id, "Checking", offBudget = false, initialBalance = Money(250000))
        assertEquals(Money(250000), engine.accounts(budget.id).single { it.id == account.id }.balance)
        val month = YearMonth.of(LocalDate.now().year, LocalDate.now().monthValue)
        val m = engine.budgetMonth(budget.id, month)
        assertTrue("the starting balance is money to budget: ${m.toBudget}", m.toBudget.minor >= 250000)
    }
}
