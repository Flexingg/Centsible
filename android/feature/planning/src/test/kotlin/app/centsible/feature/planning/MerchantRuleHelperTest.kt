package app.centsible.feature.planning

import app.centsible.core.model.CategoryId
import app.centsible.core.model.PayeeId
import app.centsible.core.model.PayeeStat
import app.centsible.core.model.Rule
import app.centsible.core.model.RuleClause
import app.centsible.core.model.RuleValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MerchantRuleHelperTest {
    @Test
    fun `clean merchant defaults to exact mode with no rename`() {
        val s = MerchantRuleHelper.suggest("Trader Joe's")
        assertEquals(MatchMode.Exact, s.mode)
        assertEquals("Trader Joe's", s.keyword)
        assertEquals("", s.renameTo)
    }

    @Test
    fun `noisy bank description suggests keyword mode and clean rename`() {
        val s = MerchantRuleHelper.suggest("TST* TRADER JOE'S #142")
        assertEquals(MatchMode.Keyword, s.mode)
        assertEquals("TRADER JOE'S", s.keyword)
        assertEquals("Trader Joe's", s.renameTo)
    }

    @Test
    fun `strips amazon order hash and suggests keyword`() {
        val s = MerchantRuleHelper.suggest("AMZN MKTP US*2X9F1")
        assertEquals(MatchMode.Keyword, s.mode)
        assertEquals("AMZN MKTP US", s.keyword)
        assertEquals("Amzn Mktp Us", s.renameTo)
    }

    @Test
    fun `strips square prefix and trailing store digits`() {
        val s = MerchantRuleHelper.suggest("SQ *BLUE BOTTLE 0412")
        assertEquals(MatchMode.Keyword, s.mode)
        assertEquals("BLUE BOTTLE", s.keyword)
        assertEquals("Blue Bottle", s.renameTo)
    }

    @Test
    fun `all-caps clean merchant stays exact with title-case rename suggestion`() {
        val s = MerchantRuleHelper.suggest("COSTCO")
        assertEquals(MatchMode.Exact, s.mode)
        assertEquals("COSTCO", s.keyword)
        assertEquals("Costco", s.renameTo)
    }

    @Test
    fun `isCoveredByRules detects direct ruleCount, payee match, and keyword contains`() {
        val p1 = PayeeStat(PayeeId("p1"), "TST* TRADER JOE'S #142", null, 5, ruleCount = 0)
        val p2 = PayeeStat(PayeeId("p2"), "Netflix", null, 3, ruleCount = 1)
        val p3 = PayeeStat(PayeeId("p3"), "Local Bakery", null, 2, ruleCount = 0)

        val keywordRule = Rule(
            id = "r1",
            stage = null,
            conditionsOp = "or",
            conditions = listOf(RuleClause("imported_payee", "contains", RuleValue.Text("trader joe"), "string")),
            actions = listOf(RuleClause("category", "set", RuleValue.Text("c-groceries"), "id")),
            scheduleId = null,
        )
        val scheduleRule = Rule(
            id = "r-sched",
            stage = null,
            conditionsOp = "and",
            conditions = listOf(RuleClause("payee", "is", RuleValue.Text("p3"), "id")),
            actions = listOf(RuleClause(null, "link-schedule", RuleValue.Text("s1"), null)),
            scheduleId = "s1",
        )

        assertTrue(MerchantRuleHelper.isCoveredByRules(p1, listOf(keywordRule)))
        assertTrue(MerchantRuleHelper.isCoveredByRules(p2, emptyList()))
        assertFalse(MerchantRuleHelper.isCoveredByRules(p3, listOf(keywordRule, scheduleRule)))
    }

    @Test
    fun `buildDraft produces exact payee rule`() {
        val draft = MerchantRuleHelper.buildDraft(
            mode = MatchMode.Exact,
            payeeId = PayeeId("p1"),
            keyword = "Netflix",
            categoryId = CategoryId("c-ent"),
        )
        assertEquals("and", draft.conditionsOp)
        assertEquals(listOf(RuleClause("payee", "is", RuleValue.Text("p1"), "id")), draft.conditions)
        assertEquals(listOf(RuleClause("category", "set", RuleValue.Text("c-ent"), "id")), draft.actions)
    }

    @Test
    fun `buildDraft produces keyword OR rule with optional payee rename action`() {
        val draft = MerchantRuleHelper.buildDraft(
            mode = MatchMode.Keyword,
            payeeId = PayeeId("p1"),
            keyword = " TRADER JOE ",
            categoryId = CategoryId("c-groceries"),
            targetPayeeId = PayeeId("p-tj"),
        )
        assertEquals("or", draft.conditionsOp)
        assertEquals(
            listOf(
                RuleClause("imported_payee", "contains", RuleValue.Text("TRADER JOE"), "string"),
                RuleClause("payee", "contains", RuleValue.Text("TRADER JOE"), "string"),
            ),
            draft.conditions,
        )
        assertEquals(
            listOf(
                RuleClause("payee", "set", RuleValue.Text("p-tj"), "id"),
                RuleClause("category", "set", RuleValue.Text("c-groceries"), "id"),
            ),
            draft.actions,
        )
    }

    @Test
    fun `matchesKeyword is case-insensitive and ignores blank keywords`() {
        assertTrue(MerchantRuleHelper.matchesKeyword("TST* TRADER JOE'S #089", "trader joe"))
        assertFalse(MerchantRuleHelper.matchesKeyword("Whole Foods", "trader joe"))
        assertFalse(MerchantRuleHelper.matchesKeyword("Whole Foods", "   "))
    }
}
