package app.canopy.core.engine.bridge

import app.canopy.core.domain.Session
import app.canopy.core.model.BudgetId
import app.canopy.core.model.BudgetPot
import app.canopy.core.model.BudgetType
import app.canopy.core.model.CategoryId
import app.canopy.core.model.DeviceId
import app.canopy.core.model.Feature
import app.canopy.core.model.Member
import app.canopy.core.model.MemberId
import app.canopy.core.model.Money
import app.canopy.core.model.Role
import app.canopy.core.model.YearMonth
import app.canopy.core.network.BridgeApi
import app.canopy.core.network.BridgeClient
import app.canopy.core.testing.InMemorySessionStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeBudgetEngineTest {
    private val fixtures = File(System.getProperty("contract.fixtures") ?: "../../../contract/fixtures")
    private val session = Session("https://b.example.com", "a", "r", null, null, Member(MemberId("m"), "Jo", Role.Owner, false, emptyList()), DeviceId("d"))
    private val requests = mutableListOf<HttpRequestData>()

    private fun engine(respondWith: (HttpRequestData) -> String): BridgeBudgetEngine {
        val client = BridgeClient(InMemorySessionStore(session), MockEngine { req ->
            requests += req
            respond(respondWith(req), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        return BridgeBudgetEngine(BridgeApi(client))
    }

    private fun fixture(name: String) = File(fixtures, "$name.json").readText()

    @Test
    fun `maps the recorded envelope month`() = runTest {
        val month = engine { fixture("budget-month") }.budgetMonth(BudgetId("b"), YearMonth("2026-09"))
        assertEquals(BudgetType.Envelope, month.budgetType)
        val food = month.groups.flatMap { it.categories }.first { it.name == "Food" }
        assertEquals(Money(60000), food.budgeted)
        assertTrue(food.carryover)
        assertTrue(month.incomeGroups.isNotEmpty())
    }

    @Test
    fun `maps recorded capabilities`() = runTest {
        val caps = engine { fixture("capabilities") }.capabilities()
        assertTrue(caps.has(Feature.BudgetEnvelope))
        assertFalse(caps.has(Feature.BudgetTracking))
        assertEquals("26.9.0", caps.actualServerVersion)
    }

    @Test
    fun `move money sends to-budget and the idempotency key`() = runTest {
        engine { fixture("budget-month") }.moveMoney(
            BudgetId("b"), YearMonth("2026-09"), BudgetPot.ToBudget, BudgetPot.Envelope(CategoryId("food")), Money(500), "key-123456",
        )
        val req = requests.single()
        assertEquals("/v1/budgets/b/months/2026-09/transfers", req.url.encodedPath)
        assertEquals("key-123456", req.headers["Idempotency-Key"])
        val body = (req.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        assertEquals("""{"from":"to-budget","to":"food","amount":500}""", body)
    }

    @Test
    fun `maps recorded transactions with splits`() = runTest {
        val page = engine { fixture("transactions-page") }.transactions(BudgetId("b"))
        assertTrue(page.items.isNotEmpty())
    }

    @Test
    fun `patch omits kept fields and sends explicit nulls`() = runTest {
        engine { fixture("transaction") }.updateTransaction(
            BudgetId("b"),
            app.canopy.core.model.TransactionId("t1"),
            app.canopy.core.model.TransactionPatch(
                amount = app.canopy.core.model.Update.Set(Money(-1750)),
                categoryId = app.canopy.core.model.Update.Set(null),
                splits = app.canopy.core.model.Update.Set(
                    listOf(app.canopy.core.model.SplitEdit(app.canopy.core.model.TransactionId("s1"), Money(-1750), CategoryId("food"))),
                ),
            ),
        )
        val req = requests.single()
        assertEquals("PATCH", req.method.value)
        val body = (req.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        assertEquals(
            """{"amount":-1750,"categoryId":null,"subtransactions":[{"id":"s1","amount":-1750,"categoryId":"food","notes":null}]}""",
            body,
        )
    }

    @Test
    fun `search and uncategorized filters become query parameters`() = runTest {
        engine { fixture("transactions-page") }.transactions(
            BudgetId("b"),
            app.canopy.core.domain.TransactionQuery(search = " trader ", uncategorized = true, limit = 20),
        )
        val url = requests.single().url
        assertEquals("trader", url.parameters["q"])
        assertEquals("true", url.parameters["uncategorized"])
        assertEquals("20", url.parameters["limit"])
    }
}
