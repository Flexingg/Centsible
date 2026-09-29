package app.centsible.feature.planning

import app.centsible.core.model.Rule
import app.centsible.core.model.RuleClause
import app.centsible.core.model.RuleValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleFormCodecTest {
    private fun opts(s: String?): JsonElement? = s?.let { Json.parseToJsonElement(it) }

    /** Same clauses, comparing options as JSON (key order doesn't matter). */
    private fun assertSame(expected: List<RuleClause>, actual: List<RuleClause>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) ->
            assertEquals(e.copy(optionsJson = null), a.copy(optionsJson = null))
            assertEquals(opts(e.optionsJson), opts(a.optionsJson))
        }
    }

    private val rule = Rule(
        id = "r1",
        stage = "pre",
        conditionsOp = "or",
        conditions = listOf(
            RuleClause("payee", "oneOf", RuleValue.Items(listOf("p1", "p2")), "id"),
            RuleClause("amount", "isbetween", RuleValue.Raw("""{"num1":1000,"num2":5000}"""), "number", """{"outflow":true}"""),
            RuleClause("notes", "contains", RuleValue.Text("#trip"), "string"),
            RuleClause("account", "onBudget", RuleValue.Null, "id"),
        ),
        actions = listOf(
            RuleClause("category", "set", RuleValue.Text("c-food"), "id"),
            RuleClause("notes", "set", RuleValue.Text(""), "string", """{"formula":"=UPPER(payee_name)"}"""),
            RuleClause(null, "append-notes", RuleValue.Text(" (auto)"), "string"),
            RuleClause(null, "set-split-amount", RuleValue.Number(2500), "number", """{"splitIndex":1,"method":"fixed-amount"}"""),
            RuleClause("category", "set", RuleValue.Text("c-fun"), "id", """{"splitIndex":1}"""),
            RuleClause(null, "set-split-amount", RuleValue.Null, "number", """{"splitIndex":2,"method":"remainder"}"""),
            RuleClause("notes", "set", RuleValue.Text(""), "string", """{"splitIndex":2,"template":"{{payee_name}} rest"}"""),
            RuleClause(null, "link-schedule", RuleValue.Text("s1"), null),
        ),
        scheduleId = null,
    )

    @Test
    fun `a rule survives the form and back`() {
        val form = RuleFormCodec.from(rule)
        assertTrue(form.anyOf)
        assertEquals(AmountSign.Out, form.conditions[1].sign)
        assertEquals(ValueMode.Formula, form.actions[1].mode)
        assertEquals(2, form.splits.size)
        assertEquals(SplitMethod.Fixed, form.splits[0].method)
        assertEquals(ValueMode.Template, form.splits[1].actions.single().mode)
        assertEquals(1, form.kept.size) // link-schedule carried through
        assertNull(RuleFormCodec.problem(form))
        val draft = RuleFormCodec.toDraft(form)
        assertEquals("pre", draft.stage)
        assertEquals("or", draft.conditionsOp)
        assertSame(rule.conditions, draft.conditions)
        assertSame(rule.actions, draft.actions)
    }

    @Test
    fun `says what's missing`() {
        val empty = RuleFormState()
        assertEquals("Fill in every condition", RuleFormCodec.problem(empty))
        val badFormula = RuleFormState(
            conditions = listOf(CondRow("payee", "is", RuleValue.Text("p1"))),
            actions = listOf(ActRow("set", "notes", RuleValue.Null, ValueMode.Formula, "UPPER(payee_name)")),
        )
        assertEquals("A formula starts with =", RuleFormCodec.problem(badFormula))
    }
}
