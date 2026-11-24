package app.centsible.core.assistant

import app.centsible.core.model.CategoryId
import app.centsible.core.model.TransactionId
import app.centsible.core.testing.FakeBudgetEngine
import app.centsible.core.testing.SampleHousehold
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolsTest {
    private val engine = FakeBudgetEngine()
    private val catalog = ToolCatalog(gateways(engine))
    private val budget = SampleHousehold.budget.id
    private fun ctx() = ToolContext(budget, catalog.names(budget), java.time.LocalDate.of(2026, 9, 18))
    private fun tool(name: String) = catalog.find(name) ?: error("no tool $name")

    @Test fun `every tool is well formed for the API`() {
        val tools = catalog.tools
        assertTrue("a tool for everything the app shows", tools.size > 70)
        for (t in tools) {
            assertTrue(t.name, Regex("^[a-z0-9_]{1,64}$").matches(t.name))
            assertTrue("${t.name} says when to use it", t.description.length > 20)
            assertEquals(t.name, "object", t.schema["type"]!!.jsonPrimitive.content)
            assertEquals(t.name, "false", t.schema["additionalProperties"].toString())
            val props = t.schema["properties"]!!.jsonObject
            (t.schema["required"] as? JsonArray)?.forEach { assertTrue("${t.name}: required ${it} is a property", it.jsonPrimitive.content in props) }
            props.forEach { (k, v) -> assertTrue("${t.name}.$k is described", v.jsonObject.containsKey("description")) }
            // It converts to the SDK's tool type.
            with(SdkClaudeClient) { t.toSdk() }
        }
        // Every group has something to switch on.
        ToolGroup.entries.forEach { g -> assertTrue("$g has tools", tools.any { it.group == g }) }
        // Bank sync is out of reach on purpose.
        assertFalse(tools.any { it.name.contains("sync") || it.name.contains("plaid") })
    }

    @Test fun `read tools answer from the budget`() = runBlocking {
        val c = ctx()
        val month = tool("get_budget_month").run(Args(input("month" to "2026-09")), c).content.jsonObject
        assertEquals("2026-09", month["month"]!!.jsonPrimitive.content)
        val food = month["groups"]!!.let { it as JsonArray }.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == "Food" }
        val groceries = (food["categories"] as JsonArray).map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == "c-groceries" }
        // A parent shows its own figures and its branch's.
        assertEquals("500.00", groceries["budgeted"]!!.jsonPrimitive.content)
        assertEquals("900.00", groceries["branch_budgeted"]!!.jsonPrimitive.content)
        val costco = (food["categories"] as JsonArray).map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == "c-costco" }
        assertEquals(3, costco["level"]!!.jsonPrimitive.content.toInt())

        val accounts = tool("list_accounts").run(Args(input()), c).content as JsonArray
        assertTrue(accounts.isNotEmpty())

        val tree = tool("get_categories").run(Args(input()), c).content as JsonArray
        assertTrue(tree.toString().contains("\"level\":4"))

        val tx = tool("search_transactions").run(Args(input("limit" to 5)), c).content.jsonObject
        assertTrue((tx["transactions"] as JsonArray).size <= 5)

        listOf("list_budget_months", "list_recurring", "list_rules", "list_merchants", "list_tags").forEach { name -> tool(name).run(Args(input()), c) }
        listOf("get_cash_flow", "get_spending_by_category", "get_money_flow").forEach { name -> tool(name).run(Args(input("start" to "2026-07", "end" to "2026-09")), c) }
    }

    @Test fun `bad input explains itself`() = runBlocking {
        val e = runCatching { tool("get_budget_month").run(Args(input("month" to "Sept")), ctx()) }.exceptionOrNull()
        assertTrue(e is ToolInputException)
        assertTrue(e!!.message!!.contains("2026-11"))
        val missing = runCatching { tool("get_cash_flow").run(Args(input("start" to "2026-01")), ctx()) }.exceptionOrNull()
        assertTrue(missing!!.message!!.contains("\"end\" is required"))
    }

    @Test fun `a transaction edit previews before and after, and undoes back to an empty category`() = runBlocking {
        val c = ctx()
        val t = engine.createTransaction(budget, app.centsible.core.model.NewTransaction(TransactionId("t-new"), app.centsible.core.model.AccountId("acc-visa"), "2026-09-17", app.centsible.core.model.Money(-2500), payeeName = "Market"))
        val args = Args(input("id" to t.id.raw, "category_id" to "c-groceries", "notes" to "weekly shop"))
        val p = tool("update_transaction").preview(args, c)
        assertEquals("Edit a transaction", p.title)
        assertTrue(p.details.toString(), p.details.any { it == "Category: none → Groceries" })
        val out = tool("update_transaction").run(args, c)
        assertEquals(CategoryId("c-groceries"), engine.transaction(budget, t.id).categoryId)
        val undo = Json.parseToJsonElement(out.undo!!.input).jsonObject
        assertEquals("explicit null puts the category back to none", JsonNull, undo["category_id"])
        tool(out.undo!!.name).run(Args(undo), c)
        assertEquals(null, engine.transaction(budget, t.id).categoryId)
    }

    @Test fun `adding a transaction is undone by deleting it, and splits must add up`() = runBlocking {
        val c = ctx()
        val before = engine.transactions.size
        val out = tool("create_transaction").run(Args(input("account_id" to "acc-visa", "date" to "2026-09-18", "amount" to -12.34, "payee_name" to "Cafe", "category_id" to "c-coffee")), c)
        assertEquals(before + 1, engine.transactions.size)
        assertEquals(-1234L, engine.transactions.first().amount.minor)
        assertEquals("delete_transaction", out.undo!!.name)
        tool("delete_transaction").run(Args(Json.parseToJsonElement(out.undo!!.input).jsonObject), c)
        assertEquals(before, engine.transactions.size)

        val bad = runCatching {
            tool("create_transaction").run(
                Args(input("account_id" to "acc-visa", "date" to "2026-09-18", "amount" to -10, "splits" to listOf(input("amount" to -4, "category_id" to "c-coffee"), input("amount" to -5)))),
                c,
            )
        }.exceptionOrNull()
        assertTrue(bad?.message.orEmpty(), bad is ToolInputException && bad.message!!.contains("add up"))
    }

    @Test fun `budget amounts preview the change and undo to what was there`() = runBlocking {
        val c = ctx()
        val args = Args(input("month" to "2026-09", "amounts" to listOf(input("category_id" to "c-coffee", "amount" to 75), input("category_id" to "c-gas", "amount" to 250))))
        val p = tool("set_budget_amounts").preview(args, c)
        assertEquals(listOf("Coffee: $60.00 → $75.00", "Gas: $200.00 → $250.00"), p.details)
        val out = tool("set_budget_amounts").run(args, c)
        val undo = Json.parseToJsonElement(out.undo!!.input).jsonObject
        assertEquals(2, (undo["amounts"] as JsonArray).size)
        tool("set_budget_amounts").run(Args(undo), c)
        val coffee = engine.budgetMonth(budget, SampleHousehold.month).groups.flatMap { it.categories }.first { it.id == CategoryId("c-coffee") }
        assertEquals(6_000L, coffee.budgeted.minor)
    }

    @Test fun `a sub-category is made under its parent and undone by deleting it`() = runBlocking {
        val c = ctx()
        val p = tool("create_category").preview(Args(input("name" to "Bakery", "parent_id" to "c-groceries")), c)
        assertEquals(listOf("Bakery in Groceries"), p.details)
        val out = tool("create_category").run(Args(input("name" to "Bakery", "parent_id" to "c-groceries")), c)
        val made = engine.categoryGroups(budget).flatMap { it.categories }.first { it.name == "Bakery" }
        assertEquals(CategoryId("c-groceries"), made.parentId)
        assertEquals("delete_category", out.undo!!.name)
    }

    @Test fun `ids that don't exist are caught before anything is proposed`() = runBlocking {
        val e = runCatching { tool("set_budget_amounts").preview(Args(input("amounts" to listOf(input("category_id" to "nope", "amount" to 1)))), ctx()) }.exceptionOrNull()
        assertTrue(e is ToolInputException)
        assertTrue(e!!.message!!.contains("get_categories"))
    }

    @Test fun `results too big to send are cut with a note`() {
        val big = JsonArray((1..10_000).map { input("n" to it, "pad" to "x".repeat(20)) })
        val cut = big.limited().jsonObject
        assertEquals("true", cut["truncated"].toString())
        assertNotNull(cut["note"])
    }

    @Test fun `the transcript shows a proposed change as a card`() {
        val conv = Conversation(
            id = "c", title = "t", budgetId = budget.raw, createdAt = 0, updatedAt = 0,
            messages = listOf(
                StoredMessage(StoredMessage.Role.User, """{"role":"user","content":[{"type":"text","text":"[From the app: something]\n\nBudget it"}]}""", 0),
                StoredMessage(StoredMessage.Role.System, """{"role":"system","content":"Context"}""", 0, context = true),
                StoredMessage(StoredMessage.Role.Assistant, """{"role":"assistant","content":[{"type":"text","text":"Sure."},{"type":"tool_use","id":"t1","name":"set_budget_amounts","input":{}}]}""", 0),
            ),
            changes = mapOf("t1" to ProposedChange("t1", "set_budget_amounts", "budget", "Budget", listOf("A → B"), "{}")),
        )
        val items = Transcript.items(conv)
        assertEquals(listOf("User", "Reply", "Change"), items.map { it::class.simpleName })
        assertEquals("Budget it", (items[0] as ChatItem.User).text)
        assertEquals(listOf("t1"), Transcript.toolUseIds(conv.messages[2].json))
    }

    @Test fun `money reads and writes as decimal currency`() {
        assertEquals("-12.34", app.centsible.core.model.Money(-1234).decimal().content)
        assertEquals(-1234L, moneyOf(-12.34).minor)
        assertEquals(1L, moneyOf(0.005).minor)
        assertEquals("-$1,234.50", app.centsible.core.model.Money(-123450).text())
        assertTrue(TransactionId("x").raw == "x")
    }
}
