package app.centsible.feature.planning

import app.centsible.core.model.AccountId
import app.centsible.core.model.CategoryId
import app.centsible.core.model.Money
import app.centsible.core.model.PayeeId
import app.centsible.core.model.PayeeStat
import app.centsible.core.model.Rule
import app.centsible.core.model.RuleClause
import app.centsible.core.model.RuleValue
import app.centsible.core.model.Transaction
import app.centsible.core.model.TransactionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `matchesKeyword is case-insensitive, ignores blank keywords, and respects exclusions`() {
        assertTrue(MerchantRuleHelper.matchesKeyword("TST* TRADER JOE'S #089", "trader joe"))
        assertFalse(MerchantRuleHelper.matchesKeyword("Whole Foods", "trader joe"))
        assertFalse(MerchantRuleHelper.matchesKeyword("Whole Foods", "   "))
        assertTrue(MerchantRuleHelper.matchesKeyword("SHELL OIL 57442", "SHELL", listOf("SHELLFISH")))
        assertFalse(MerchantRuleHelper.matchesKeyword("SHELLFISH MARKET", "SHELL", listOf("SHELLFISH")))
    }

    @Test
    fun `extractExclusionTerm picks the clearest distinguishing word`() {
        assertEquals("SHELLFISH", MerchantRuleHelper.extractExclusionTerm("SHELL", "SHELLFISH MARKET #42"))
        assertEquals("STORE", MerchantRuleHelper.extractExclusionTerm("APPLE", "APPLE STORE #R123"))
        assertEquals("PRIME", MerchantRuleHelper.extractExclusionTerm("AMAZON", "AMAZON PRIME VIDEO"))
    }

    @Test
    fun `buildDraft switches to AND when exclusions or notes filter are present`() {
        val draft = MerchantRuleHelper.buildDraft(
            mode = MatchMode.Keyword,
            payeeId = PayeeId("p1"),
            keyword = "APPLE",
            categoryId = CategoryId("c-subs"),
            excludeKeywords = listOf("STORE", "store"),
            notesContains = "iCloud",
        )
        assertEquals("and", draft.conditionsOp)
        assertEquals(
            listOf(
                RuleClause("payee", "contains", RuleValue.Text("APPLE"), "string"),
                RuleClause("payee", "doesNotContain", RuleValue.Text("STORE"), "string"),
                RuleClause("notes", "contains", RuleValue.Text("iCloud"), "string"),
            ),
            draft.conditions,
        )
    }

    @Test
    fun `buildDraft supports exact merchant split by notes`() {
        val draft = MerchantRuleHelper.buildDraft(
            mode = MatchMode.Exact,
            payeeId = PayeeId("p-paypal"),
            keyword = "PayPal",
            categoryId = CategoryId("c-gym"),
            notesContains = "Gym",
        )
        assertEquals("and", draft.conditionsOp)
        assertEquals(
            listOf(
                RuleClause("payee", "is", RuleValue.Text("p-paypal"), "id"),
                RuleClause("notes", "contains", RuleValue.Text("Gym"), "string"),
            ),
            draft.conditions,
        )
    }

    private fun tx(id: String, amountMinor: Long, date: String) = Transaction(
        id = TransactionId(id),
        accountId = AccountId("a1"),
        date = date,
        amount = Money(amountMinor),
        payeeId = PayeeId("p1"),
        payeeName = "Netflix",
        categoryId = null,
        notes = null,
        cleared = true,
        reconciled = false,
        transferId = null,
        isParent = false,
        subtransactions = emptyList(),
    )

    @Test
    fun `computeStats returns sum, average, median, mode, flow counts, range, and dates`() {
        val txs = listOf(
            tx("1", -1599, "2026-01-15"),
            tx("2", -1599, "2026-02-15"),
            tx("3", -1599, "2026-03-15"),
            tx("4", -2500, "2026-04-15"),
            tx("5", 1000, "2026-05-15"),
        )
        val stats = MerchantRuleHelper.computeStats(txs, totalMatchCount = 12)!!
        assertEquals(5, stats.count)
        assertEquals(12, stats.totalMatchCount)
        assertTrue(stats.isSampled)
        assertEquals(Money(-6297), stats.sum)
        assertEquals(Money(-1259), stats.average)
        assertEquals(Money(-1599), stats.median)
        assertEquals(Money(-1599), stats.mode)
        assertEquals(3, stats.modeCount)
        assertEquals(4, stats.expenseCount)
        assertEquals(1, stats.incomeCount)
        assertEquals(Money(-2500), stats.minAmount)
        assertEquals(Money(1000), stats.maxAmount)
        assertEquals("2026-01-15", stats.firstDate)
        assertEquals("2026-05-15", stats.lastDate)
    }

    @Test
    fun `computeStats omits mode when no amount repeats`() {
        val txs = listOf(
            tx("1", -1000, "2026-01-01"),
            tx("2", -2000, "2026-02-01"),
        )
        val stats = MerchantRuleHelper.computeStats(txs)!!
        assertNull(stats.mode)
        assertEquals(0, stats.modeCount)
        assertEquals(Money(-1500), stats.median)
        assertFalse(stats.isSampled)
        assertNull(MerchantRuleHelper.computeStats(emptyList()))
    }
}
