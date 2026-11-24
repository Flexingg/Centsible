package app.centsible.core.assistant

import app.centsible.core.model.CategoryId
import app.centsible.core.model.Role
import app.centsible.core.testing.FakeBudgetEngine
import app.centsible.core.testing.SampleHousehold
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The agent end to end: the real Anthropic SDK against a stand-in server that streams the
 * Messages API's own events, with the app's gateways faked over the sample household.
 */
class AgentTest {
    private val server = MockWebServer()
    private val engine = FakeBudgetEngine()
    private val settings = MemorySettings()
    private val notes = MemoryNotes()
    private val store = MemoryConversations()
    private var changed = 0
    private lateinit var agent: AssistantAgent

    @Before fun setUp() {
        server.start()
        val client = SdkClaudeClient().apply { baseUrl = server.url("/").toString().trimEnd('/') }
        agent = agent(client, sessionStore())
    }

    private fun agent(client: ClaudeClient, sessions: app.centsible.core.domain.SessionStore) = AssistantAgent(
        ToolCatalog(gateways(engine)), client, settings, MemoryKeys(), notes, store, sessions, engine, unused(), { changed++ },
    ).also { it.today = { java.time.LocalDate.of(2026, 9, 18) } }

    @After fun tearDown() = server.close()

    private fun reply(body: String) = server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream").body(body).build())

    private fun RecordedRequest.json(): JsonObject = Json.parseToJsonElement(body!!.utf8()).jsonObject
    private fun JsonObject.messages() = this["messages"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.blocks() = (this["content"] as? JsonArray)?.map { it.jsonObject }.orEmpty()

    private fun run(block: suspend () -> Unit) = runBlocking { block() }

    @Test fun `reads with a tool, answers, and sends the thinking back unchanged`() = run {
        reply(sse(Block.Thinking("Check the budget first."), Block.Text("Let me look."), Block.ToolUse("toolu_1", "get_budget_month", input("month" to "2026-09"))))
        reply(sse(Block.Text("You have **$64.30** left in Groceries.")))

        val id = agent.send(null, "How much is left in groceries?")
        agent.awaitIdle(id)

        assertEquals(2, server.requestCount)
        val first = server.takeRequest()
        val body = first.json()
        assertEquals("claude-opus-5-5", body["model"]!!.jsonPrimitive.content)
        // The system prompt is cached, and so is the conversation's tail.
        assertEquals("ephemeral", body["system"]!!.jsonArray[0].jsonObject["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("ephemeral", body["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("adaptive", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("summarized", body["thinking"]!!.jsonObject["display"]!!.jsonPrimitive.content)
        assertEquals("high", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        // Refusals fall back to another model on Anthropic's side.
        assertEquals("default", body["fallbacks"]!!.jsonPrimitive.content)
        assertTrue(first.headers["anthropic-beta"].orEmpty().contains("server-side-fallback-2026-07-01"))
        assertEquals("sk-ant-test", first.headers["x-api-key"])
        assertNull("API keys don't go in Authorization", first.headers["Authorization"])
        val tools = body["tools"]!!.jsonArray.map { it.jsonObject }
        val budgetTool = tools.first { it["name"]?.jsonPrimitive?.content == "get_budget_month" }
        assertEquals("true", budgetTool["eager_input_streaming"].toString())
        assertEquals("false", budgetTool["input_schema"]!!.jsonObject["additionalProperties"].toString())
        assertTrue("code execution for calculations", tools.any { it["type"]?.jsonPrimitive?.content == "code_execution_20260521" })
        // The question, then the app's context note as a system message (with the budget's ids).
        val m1 = body.messages()
        assertEquals(listOf("user", "system"), m1.map { it["role"]!!.jsonPrimitive.content })
        val context = m1[1]["content"].toString()
        assertTrue(context, context.contains("Today is Friday, 2026-09-18"))
        assertTrue(context, context.contains("Permissions: read only"))
        assertTrue(context, context.contains("[c-groceries]"))

        val m2 = server.takeRequest().json().messages()
        assertEquals(listOf("user", "system", "assistant", "user"), m2.map { it["role"]!!.jsonPrimitive.content })
        val thinking = m2[2].blocks().first { it["type"]!!.jsonPrimitive.content == "thinking" }
        assertEquals("Check the budget first.", thinking["thinking"]!!.jsonPrimitive.content)
        assertEquals("sig-" + "Check the budget first.".hashCode(), thinking["signature"]!!.jsonPrimitive.content)
        val result = m2[3].blocks().single()
        assertEquals("toolu_1", result["tool_use_id"]!!.jsonPrimitive.content)
        assertTrue(result["content"].toString().contains("to_budget"))

        val conv = store.get(id)!!
        val items = Transcript.items(conv)
        assertEquals(
            listOf("User", "Thinking", "Reply", "Lookup", "Reply"),
            items.map { it::class.simpleName },
        )
        assertEquals("You have **$64.30** left in Groceries.", (items.last() as ChatItem.Reply).markdown)
        assertEquals("How much is left in groceries?", conv.title)
        assertTrue(conv.usage.cacheRead > 0)
    }

    @Test fun `a change waits for approval, runs when approved, and can be undone`() = run {
        settings.state.value = AssistantSettings(writable = setOf(ToolGroup.Budget))
        val dining = CategoryId("c-dining")
        val before = engine.budgetMonth(SampleHousehold.budget.id, SampleHousehold.month).groups.flatMap { it.categories }.first { it.id == dining }.budgeted
        reply(sse(Block.Text("I'll raise Dining Out."), Block.ToolUse("toolu_b", "set_budget_amounts", input("month" to "2026-09", "amounts" to listOf(input("category_id" to "c-dining", "amount" to 400))))))

        val id = agent.send(null, "Give dining out $400 this month")
        agent.awaitIdle(id)
        var conv = store.get(id)!!
        assertTrue(conv.awaitingApproval)
        val card = conv.changes.getValue("toolu_b")
        assertEquals("Budget September 2026", card.title)
        assertEquals(listOf("Dining Out: ${before.text()} → $400.00"), card.details)
        assertEquals(1, server.requestCount) // nothing more until it's decided
        assertEquals(before, budgeted(dining))

        reply(sse(Block.Text("Done: Dining Out has $400.00.")))
        agent.approve(id, "toolu_b")
        agent.awaitIdle(id)
        conv = store.get(id)!!
        assertEquals(40_000L, budgeted(dining).minor)
        assertEquals(ChangeStatus.Done, conv.changes.getValue("toolu_b").status)
        assertTrue(changed > 0)
        server.takeRequest()
        val result = server.takeRequest().json().messages().last().blocks().single()
        assertEquals("toolu_b", result["tool_use_id"]!!.jsonPrimitive.content)
        assertFalse(result["is_error"]?.jsonPrimitive?.content == "true")

        agent.undo(id, "toolu_b")
        agent.awaitIdle(id)
        assertEquals(before, budgeted(dining))
        conv = store.get(id)!!
        assertEquals(ChangeStatus.Undone, conv.changes.getValue("toolu_b").status)

        // Claude hears about the undo with the next message, in the person's turn.
        reply(sse(Block.Text("Noted.")))
        agent.send(id, "Thanks")
        agent.awaitIdle(id)
        val last = server.takeRequest().json().messages().last { it["role"]!!.jsonPrimitive.content == "user" }
        assertTrue(last.toString(), last.toString().contains("undid a change"))
        assertEquals("Thanks", (Transcript.items(store.get(id)!!).filterIsInstance<ChatItem.User>().last()).text)
    }

    private suspend fun budgeted(id: CategoryId) = engine.budgetMonth(SampleHousehold.budget.id, SampleHousehold.month).groups.flatMap { it.categories }.first { it.id == id }.budgeted

    @Test fun `with the permission off, Claude is told which one to turn on, and nothing changes`() = run {
        reply(sse(Block.ToolUse("toolu_x", "set_budget_amounts", input("amounts" to listOf(input("category_id" to "c-dining", "amount" to 1))))))
        reply(sse(Block.Text("You'll need to turn on Edit budget amounts.")))
        val id = agent.send(null, "Budget dining at $1")
        agent.awaitIdle(id)
        server.takeRequest()
        val result = server.takeRequest().json().messages().last().blocks().single()
        assertEquals("true", result["is_error"]!!.jsonPrimitive.content)
        assertTrue(result["content"].toString().contains("Edit budget amounts"))
        assertTrue(store.get(id)!!.changes.isEmpty())
    }

    @Test fun `a viewer can't change anything, whatever the permissions say`() = run {
        settings.state.value = AssistantSettings(writable = ToolGroup.entries.toSet())
        val viewer = agent(SdkClaudeClient().apply { baseUrl = server.url("/").toString().trimEnd('/') }, sessionStore(Role.Viewer))
        reply(sse(Block.ToolUse("toolu_v", "set_budget_amounts", input("amounts" to listOf(input("category_id" to "c-dining", "amount" to 1))))))
        reply(sse(Block.Text("Viewers can't change the budget.")))
        val id = viewer.send(null, "Budget dining at $1")
        viewer.awaitIdle(id)
        server.takeRequest()
        assertTrue(server.takeRequest().json().messages().last().toString().contains("can view the budget but not change it"))
    }

    @Test fun `writing instead of approving declines the card, in the same message as the new words`() = run {
        settings.state.value = AssistantSettings(writable = setOf(ToolGroup.Budget))
        reply(sse(Block.ToolUse("toolu_c", "set_budget_amounts", input("amounts" to listOf(input("category_id" to "c-dining", "amount" to 500))))))
        val id = agent.send(null, "Budget dining at $500")
        agent.awaitIdle(id)
        reply(sse(Block.Text("OK, leaving it.")))
        agent.send(id, "Actually, never mind")
        agent.awaitIdle(id)
        server.takeRequest()
        val msgs = server.takeRequest().json().messages()
        val lastUser = msgs.last { it["role"]!!.jsonPrimitive.content == "user" }.blocks()
        assertEquals(listOf("tool_result", "text"), lastUser.map { it["type"]!!.jsonPrimitive.content })
        assertTrue(lastUser[0].toString().contains("didn't approve"))
        assertEquals(ChangeStatus.Declined, store.get(id)!!.changes.getValue("toolu_c").status)
    }

    @Test fun `memory is kept, and offered to the next chat`() = run {
        reply(sse(Block.ToolUse("toolu_m", "save_memory", input("text" to "Saving for a house deposit by 2028."))))
        reply(sse(Block.Text("I'll remember that.")))
        agent.awaitIdle(agent.send(null, "We're saving for a house by 2028"))
        assertEquals(listOf("Saving for a house deposit by 2028."), notes.all().map { it.text })

        server.takeRequest(); server.takeRequest()
        reply(sse(Block.Text("Hi!")))
        agent.awaitIdle(agent.send(null, "Hi"))
        val context = server.takeRequest().json().messages()[1].toString()
        assertTrue(context, context.contains("Saving for a house deposit by 2028."))
    }

    @Test fun `a turned-down key is reported, and nothing is lost`() = run {
        server.enqueue(MockResponse.Builder().code(401).addHeader("Content-Type", "application/json").body("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""").build())
        val id = agent.send(null, "Hello")
        agent.awaitIdle(id)
        val live = agent.live(id).first()
        assertTrue(live.badKey)
        assertTrue(live.error!!.contains("API key"))
        // The question is saved: Retry sends it again.
        assertEquals(1, Transcript.items(store.get(id)!!).size)
        reply(sse(Block.Text("Hello!")))
        agent.retry(id)
        agent.awaitIdle(id)
        assertNull(agent.live(id).first().error)
        assertEquals("Hello!", (Transcript.items(store.get(id)!!).last() as ChatItem.Reply).markdown)
    }

    @Test fun `bad tool input goes back to Claude as an error to fix`() = run {
        reply(sse(Block.ToolUse("toolu_bad", "get_budget_month", input("month" to "September"))))
        reply(sse(Block.Text("Sorry.")))
        val id = agent.send(null, "Budget?")
        agent.awaitIdle(id)
        server.takeRequest()
        val result = server.takeRequest().json().messages().last().blocks().single()
        assertEquals("true", result["is_error"]!!.jsonPrimitive.content)
        assertTrue(result["content"].toString().contains("2026-11"))
    }
}
