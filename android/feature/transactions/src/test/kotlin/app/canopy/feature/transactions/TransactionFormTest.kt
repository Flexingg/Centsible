package app.canopy.feature.transactions

import app.canopy.core.model.AccountId
import app.canopy.core.model.CategoryId
import app.canopy.core.model.Money
import app.canopy.core.model.Payee
import app.canopy.core.model.PayeeId
import app.canopy.core.model.SplitEdit
import app.canopy.core.model.TransactionId
import app.canopy.core.model.Update
import app.canopy.core.testing.SampleHousehold
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionFormTest {
    private val checking = AccountId("acc-checking")
    private val savings = AccountId("acc-savings")
    private val payees = listOf(
        Payee(PayeeId("p-tj"), "Trader Joe's", null),
        Payee(PayeeId("p-to-savings"), "High-Yield Savings", savings),
        Payee(PayeeId("p-to-checking"), "Joint Checking", checking),
    )
    private val groceries = CategoryId("c-groceries")

    @Test
    fun `expenses are negative and income positive`() {
        val f = TransactionForm(amount = "12.34", accountId = checking)
        assertEquals(Money(-1234), f.signedAmount)
        assertEquals(Money(1234), f.copy(kind = TxKind.Income).signedAmount)
        assertEquals(Money(1234), f.copy(kind = TxKind.Transfer, transferIn = true).signedAmount)
    }

    @Test
    fun `new expense carries payee name and category`() {
        val t = TransactionForm(amount = "45.23", payee = " Trader Joe's ", accountId = checking, categoryId = groceries, date = LocalDate.of(2026, 9, 3))
            .toNew(TransactionId("t1"), payees)
        assertEquals(Money(-4523), t.amount)
        assertEquals("Trader Joe's", t.payeeName)
        assertNull(t.payeeId)
        assertEquals(groceries, t.categoryId)
        assertEquals("2026-09-03", t.date)
    }

    @Test
    fun `new transfer uses the other account's transfer payee and no category`() {
        val t = TransactionForm(kind = TxKind.Transfer, amount = "200", accountId = checking, transferAccountId = savings, categoryId = groceries)
            .toNew(TransactionId("t1"), payees)
        assertEquals(PayeeId("p-to-savings"), t.payeeId)
        assertNull(t.payeeName)
        assertNull(t.categoryId)
        assertEquals(Money(-20000), t.amount)
    }

    @Test
    fun `validation catches the common mistakes`() {
        assertEquals("Enter an amount", TransactionForm(accountId = checking).problem())
        assertEquals("Choose the other account", TransactionForm(kind = TxKind.Transfer, amount = "5", accountId = checking).problem())
        assertEquals(
            "Pick two different accounts",
            TransactionForm(kind = TxKind.Transfer, amount = "5", accountId = checking, transferAccountId = checking).problem(),
        )
        val split = TransactionForm(amount = "10", accountId = checking, splits = listOf(SplitRow(1, amount = "6"), SplitRow(2, amount = "3")))
        assertEquals("Splits must add up to the total", split.problem())
        assertEquals(Money(100), split.splitRemaining)
        assertNull(split.copy(splits = listOf(SplitRow(1, amount = "6"), SplitRow(2, amount = "4"))).problem())
    }

    @Test
    fun `patch contains only what changed`() {
        val original = SampleHousehold.transactions.first() // Trader Joe's, -87.34, groceries
        val form = TransactionForm.from(original, payees)
        assertTrue(form.toPatch(original, payees).isEmpty)

        val patch = form.copy(amount = "90.00", notes = "wine").toPatch(original, payees)
        assertEquals(Update.Set(Money(-9000)), patch.amount)
        assertEquals(Update.Set("wine"), patch.notes)
        assertEquals(Update.Keep, patch.categoryId)
        assertEquals(Update.Keep, patch.payeeName)
        assertEquals(Update.Keep, patch.date)
    }

    @Test
    fun `patch clears a category explicitly`() {
        val original = SampleHousehold.transactions.first()
        val patch = TransactionForm.from(original, payees).copy(categoryId = null).toPatch(original, payees)
        assertEquals(Update.Set(null), patch.categoryId)
    }

    @Test
    fun `patch turns an expense into a transfer`() {
        val original = SampleHousehold.transactions.first()
        val patch = TransactionForm.from(original, payees).copy(kind = TxKind.Transfer, transferAccountId = savings).toPatch(original, payees)
        assertEquals(Update.Set(PayeeId("p-to-savings")), patch.payeeId)
        assertEquals(Update.Keep, patch.payeeName)
    }

    @Test
    fun `splitting sends signed splits and unsplitting sends an empty list`() {
        val original = SampleHousehold.transactions.first()
        val form = TransactionForm.from(original, payees)
        val split = form.copy(splits = listOf(SplitRow(1, amount = "80.00", categoryId = groceries), SplitRow(2, amount = "7.34", categoryId = CategoryId("c-shopping"))))
        val patch = split.toPatch(original, payees)
        assertEquals(
            Update.Set(listOf(SplitEdit(null, Money(-8000), groceries), SplitEdit(null, Money(-734), CategoryId("c-shopping")))),
            patch.splits,
        )
        val parent = original.copy(isParent = true, categoryId = null, subtransactions = listOf(original.copy(id = TransactionId("s1"))))
        val unsplit = TransactionForm.from(parent, payees).copy(splits = emptyList(), categoryId = groceries).toPatch(parent, payees)
        assertEquals(Update.Set(emptyList<SplitEdit>()), unsplit.splits)
    }

    @Test
    fun `an existing incoming transfer loads as receive from the other account`() {
        val incoming = SampleHousehold.transactions.first().copy(
            accountId = savings,
            amount = Money(20000),
            payeeId = PayeeId("p-to-checking"),
            transferId = TransactionId("other-side"),
            categoryId = null,
        )
        val f = TransactionForm.from(incoming, payees)
        assertEquals(TxKind.Transfer, f.kind)
        assertTrue(f.transferIn)
        assertEquals(checking, f.transferAccountId)
        assertEquals(Money(20000), f.signedAmount)
    }

    @Test fun rememberCategory_offeredOnlyForNamedSingleCategoryNonTransfer() {
        val base = EditorUiState(loading = false, canEdit = true, canCreateRules = true,
            form = TransactionForm(amount = "5", payee = "Cafe", categoryId = app.canopy.core.model.CategoryId("c1")))
        org.junit.Assert.assertTrue(base.canRememberCategory)
        org.junit.Assert.assertFalse(base.copy(canCreateRules = false).canRememberCategory)
        org.junit.Assert.assertFalse(base.copy(form = base.form.copy(payee = " ")).canRememberCategory)
        org.junit.Assert.assertFalse(base.copy(form = base.form.copy(kind = TxKind.Transfer)).canRememberCategory)
        org.junit.Assert.assertFalse(base.copy(form = base.form.copy(categoryId = null)).canRememberCategory)
    }
}
