package app.centsible.core.engine.bridge

import app.centsible.core.domain.Session
import app.centsible.core.model.Automation
import app.centsible.core.model.AutomationSource
import app.centsible.core.model.BudgetId
import app.centsible.core.model.CategoryId
import app.centsible.core.model.DeviceId
import app.centsible.core.model.Member
import app.centsible.core.model.MemberId
import app.centsible.core.model.Money
import app.centsible.core.model.Role
import app.centsible.core.model.YearMonth
import app.centsible.core.network.AutomationsApi
import app.centsible.core.network.BridgeClient
import app.centsible.core.testing.InMemorySessionStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeAutomationsTest {
    private val fixtures = File(System.getProperty("contract.fixtures") ?: "../../../contract/fixtures")
    private val session = Session("https://b.example.com", "a", "r", null, null, Member(MemberId("m"), "Jo", Role.Owner, false, emptyList()), DeviceId("d"))

    private fun gateway(respondWith: (HttpRequestData) -> String) = BridgeAutomations(
        AutomationsApi(BridgeClient(InMemorySessionStore(session), MockEngine { req -> respond(respondWith(req), headers = headersOf(HttpHeaders.ContentType, "application/json")) })),
    ) {}

    private fun fixture(name: String) = File(fixtures, "$name.json").readText()

    @Test
    fun `maps a recorded category's automations`() = runTest {
        val c = gateway { fixture("category-automations") }.get(BudgetId("b"), CategoryId("food"), YearMonth("2026-09"))
        assertEquals(AutomationSource.Automations, c.source)
        val average = c.automations[0] as Automation.Average
        assertEquals(3, average.months)
        assertEquals(Automation.Adjustment.Percent(10.0), average.adjustment)
        val refill = c.automations[1] as Automation.Refill
        assertEquals(Money(60000), refill.cap.amount)
        assertTrue(refill.cap.hold)
        assertTrue(c.automations[2] is Automation.Goal)
        assertEquals(c.automations.size, c.perAutomation!!.size)
    }

    @Test
    fun `maps the recorded list`() = runTest {
        val list = gateway { fixture("automations") }.list(BudgetId("b"), YearMonth("2026-09"))
        assertTrue(list.any { it.isIncome && it.source == AutomationSource.None })
        assertTrue(list.first { it.name == "Food" }.projected!!.minor > 0)
    }

    @Test
    fun `every kind survives the trip to the bridge and back`() {
        val all = listOf(
            Automation.Fixed(1, Money(185000), Automation.Cap(Money(200000)), "Rent"),
            Automation.Fixed(0, null, Automation.Cap(Money(5000), hold = true, period = Automation.CapPeriod.Daily)),
            Automation.Periodic(0, Money(2500), Automation.PeriodUnit.Week, 2, "2026-09-01", Automation.Cap(Money(9000), period = Automation.CapPeriod.Weekly, start = "2026-09-07")),
            Automation.SaveBy(0, Money(120000), YearMonth("2027-03"), Automation.Repeat(yearly = true, count = 1), null),
            Automation.SaveBy(2, Money(30000), YearMonth("2026-12"), null, YearMonth("2026-10")),
            Automation.CoverSchedule(0, "Internet", full = true, adjustment = Automation.Adjustment.Fixed(Money(-500))),
            Automation.Average(3, 6, Automation.Adjustment.Percent(-10.0)),
            Automation.Copy(0, 12),
            Automation.PercentOfIncome(4, 12.5, "income-cat", previousMonth = true),
            Automation.Refill(0, Automation.Cap(Money(60000))),
            Automation.Remainder(2.0, null),
            Automation.Goal(Money(1_000_000)),
        )
        assertEquals(all, all.map { it.toDto().toModel() })
    }
}
