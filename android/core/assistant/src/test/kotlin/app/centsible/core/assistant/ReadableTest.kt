package app.centsible.core.assistant

import app.centsible.core.model.RuleClause
import app.centsible.core.model.RuleDraft
import app.centsible.core.model.RuleValue
import app.centsible.core.testing.SampleHousehold
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadableTest {
    private val costco = "543da01c-8faf-4329-b00f-d19e7b4d98d8"
    private val groceries = "266169a3-2834-4d2f-8883-6bb261bf38db"
    private val labels = mapOf(costco to "Costco", groceries to "Food › Groceries")

    @Test fun `ids become names, quotes and all`() {
        assertEquals(
            "If merchant is one of [Costco, 9f0c2a11-0000-4000-8000-000000000000], set Food › Groceries",
            Readable.humanize("If merchant is one of [\"$costco\", 9f0c2a11-0000-4000-8000-000000000000], set \"${groceries.uppercase()}\"", labels),
        )
        assertEquals("No ids here", Readable.humanize("No ids here", labels))
    }

    @Test fun `a rule reads in plain words`() = runBlocking {
        val names = ToolCatalog(gateways()).names(SampleHousehold.budget.id)
        val rule = RuleDraft(
            conditionsOp = "and",
            conditions = listOf(
                RuleClause("account", "is", RuleValue.Text("acc-checking"), "id"),
                RuleClause("amount", "isbetween", RuleValue.Raw("""{"num1":-5000,"num2":-1000}"""), "number"),
                RuleClause("payee", "oneOf", RuleValue.Items(listOf("gone-1")), "id"),
                RuleClause("notes", "contains", RuleValue.Text("membership"), "string"),
            ),
            actions = listOf(RuleClause("category", "set", RuleValue.Text("c-costco"), "id")),
        )
        val lines = Readable.rule(rule, names)
        assertTrue(lines[0], lines[0].startsWith("If account is Joint Checking, and amount is between -$50.00 and -$10.00"))
        assertTrue(lines[0], lines[0].contains("merchant is one of (something no longer here)"))
        assertTrue(lines[0], lines[0].contains("notes contains “membership”"))
        assertTrue(lines[1], lines[1].startsWith("Then set the category to") && lines[1].contains("Costco"))
        assertFalse(lines.joinToString(), lines.joinToString().contains("c-costco"))
    }

    @Test fun `cost is priced per model`() {
        assertEquals(0.001256, AssistantModel.Opus.cost(100, 42, 80, 0), 1e-9)
        assertEquals(0.000628, AssistantModel.Sonnet.cost(100, 42, 80, 0), 1e-9)
        // Cache writes cost a quarter more than input; a web search is a cent.
        assertEquals(1000 * 4 * 1.25 / 1e6 + 0.01, AssistantModel.Opus.cost(0, 0, 0, 1000, webSearches = 1), 1e-9)
        // Haiku prices long prompts higher.
        assertEquals(AssistantModel.Haiku.cost(100_001, 0, 0, 0), 5 * 100_001 * 0.10 / 1e6, 1e-9)
        assertEquals(AssistantModel.Sonnet, AssistantModel.served("claude-sonnet-5-5"))
        assertEquals(null, AssistantModel.served("claude-opus-5"))
    }
}
