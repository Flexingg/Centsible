package app.centsible.core.domain

import app.centsible.core.model.AccountId
import app.centsible.core.model.CategoryId
import app.centsible.core.model.Money
import app.centsible.core.model.PayeeId
import app.centsible.core.model.Transaction
import app.centsible.core.model.TransactionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UndoTest {
    private fun tx(id: String, amount: Long, category: String?, subs: List<Transaction> = emptyList()) = Transaction(
        TransactionId(id), AccountId("acc"), "2026-09-20", Money(amount), PayeeId("p1"), "Target", category?.let(::CategoryId),
        "note", true, false, null, subs.isNotEmpty(), subs,
    )

    @Test fun `a split comes back as the same split under a new id`() {
        val original = tx("t1", -5000, null, listOf(tx("s1", -3000, "c1"), tx("s2", -2000, "c2")))
        val again = original.recreate(TransactionId("t9"))
        assertEquals(TransactionId("t9"), again.id)
        assertEquals(listOf(Money(-3000), Money(-2000)), again.splits.map { it.amount })
        assertEquals(listOf(CategoryId("c1"), CategoryId("c2")), again.splits.map { it.categoryId })
        assertNull(again.categoryId)
        assertEquals(PayeeId("p1"), again.payeeId)
        assertNull("payee id is enough; a name would create a duplicate payee", again.payeeName)
    }
}
