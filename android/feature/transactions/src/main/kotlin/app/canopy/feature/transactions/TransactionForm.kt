package app.canopy.feature.transactions

import app.canopy.core.designsystem.component.MoneyInput
import app.canopy.core.model.AccountId
import app.canopy.core.model.CategoryId
import app.canopy.core.model.Money
import app.canopy.core.model.NewTransaction
import app.canopy.core.model.Payee
import app.canopy.core.model.PayeeId
import app.canopy.core.model.SplitEdit
import app.canopy.core.model.Transaction
import app.canopy.core.model.TransactionId
import app.canopy.core.model.TransactionPatch
import app.canopy.core.model.Update
import java.time.LocalDate

enum class TxKind { Expense, Income, Transfer }

data class SplitRow(
    val key: Long,
    val id: TransactionId? = null,
    val amount: String = "",
    val categoryId: CategoryId? = null,
    val notes: String = "",
)

/**
 * What the editor shows, independent of Compose. Amounts are typed unsigned; [kind]
 * decides the sign (expenses and outgoing transfers are negative in Actual).
 */
data class TransactionForm(
    val kind: TxKind = TxKind.Expense,
    val amount: String = "",
    val payee: String = "",
    val accountId: AccountId? = null,
    /** Transfer counterpart account. */
    val transferAccountId: AccountId? = null,
    /** For transfers: true when money comes into [accountId] from [transferAccountId]. */
    val transferIn: Boolean = false,
    val categoryId: CategoryId? = null,
    val date: LocalDate = LocalDate.now(),
    val notes: String = "",
    val cleared: Boolean = true,
    val splits: List<SplitRow> = emptyList(),
) {
    val isSplit get() = splits.isNotEmpty()
    val parsedAmount: Money? get() = MoneyInput.parse(amount)?.abs()

    private val sign: Int get() = when (kind) {
        TxKind.Expense -> -1
        TxKind.Income -> 1
        TxKind.Transfer -> if (transferIn) 1 else -1
    }

    val signedAmount: Money? get() = parsedAmount?.let { Money(it.minor * sign) }

    /** Unassigned part of the total while splitting (unsigned). */
    val splitRemaining: Money?
        get() {
            val total = parsedAmount ?: return null
            val assigned = splits.sumOf { MoneyInput.parse(it.amount)?.abs()?.minor ?: 0 }
            return Money(total.minor - assigned)
        }

    /** First problem that blocks saving, or null. */
    fun problem(): String? = when {
        parsedAmount == null || parsedAmount!!.isZero -> "Enter an amount"
        accountId == null -> "Choose an account"
        kind == TxKind.Transfer && transferAccountId == null -> "Choose the other account"
        kind == TxKind.Transfer && transferAccountId == accountId -> "Pick two different accounts"
        isSplit && splits.any { MoneyInput.parse(it.amount) == null } -> "Every split needs an amount"
        isSplit && splitRemaining?.isZero != true -> "Splits must add up to the total"
        else -> null
    }

    private fun signedSplits() = splits.map { s ->
        SplitEdit(s.id, Money((MoneyInput.parse(s.amount)?.abs()?.minor ?: 0) * sign), s.categoryId, s.notes.ifBlank { null })
    }

    fun toNew(id: TransactionId, payees: List<Payee>): NewTransaction {
        val transferPayee = transferPayee(payees)
        return NewTransaction(
            id = id,
            accountId = accountId!!,
            date = date.toString(),
            amount = signedAmount!!,
            payeeId = transferPayee,
            payeeName = payee.trim().takeIf { transferPayee == null && it.isNotEmpty() },
            categoryId = categoryId.takeIf { kind != TxKind.Transfer && !isSplit },
            notes = notes.ifBlank { null },
            cleared = cleared,
            splits = if (kind == TxKind.Transfer) emptyList() else signedSplits().map { NewTransaction.Split(it.amount, it.categoryId, it.notes) },
        )
    }

    /** Only what changed, so concurrent edits by another household member survive. */
    fun toPatch(original: Transaction, payees: List<Payee>): TransactionPatch {
        val before = from(original, payees)
        fun <T> changed(now: T, then: T): Update<T> = if (now != then) Update.Set(now) else Update.Keep
        val transferPayee = transferPayee(payees)
        val payeeChanged = kind != before.kind || transferAccountId != before.transferAccountId || payee.trim() != before.payee.trim()
        return TransactionPatch(
            accountId = if (accountId != before.accountId && accountId != null) Update.Set(accountId) else Update.Keep,
            date = changed(date.toString(), before.date.toString()),
            amount = changed(signedAmount!!, before.signedAmount!!),
            payeeId = when {
                !payeeChanged -> Update.Keep
                transferPayee != null -> Update.Set(transferPayee)
                payee.isBlank() -> Update.Set(null)
                else -> Update.Keep
            },
            payeeName = if (payeeChanged && transferPayee == null && payee.isNotBlank()) Update.Set(payee.trim()) else Update.Keep,
            categoryId = when {
                kind == TxKind.Transfer || isSplit -> Update.Keep
                else -> changed(categoryId, before.categoryId)
            },
            notes = changed(notes.ifBlank { null }, before.notes.ifBlank { null }),
            cleared = changed(cleared, before.cleared),
            splits = if (isSplit != before.isSplit || (isSplit && signedSplits() != before.signedSplits())) {
                Update.Set(if (kind == TxKind.Transfer) emptyList() else signedSplits())
            } else {
                Update.Keep
            },
        )
    }

    private fun transferPayee(payees: List<Payee>): PayeeId? =
        if (kind == TxKind.Transfer) payees.firstOrNull { it.transferAccountId == transferAccountId }?.id else null

    companion object {
        fun from(t: Transaction, payees: List<Payee>): TransactionForm {
            val transferTo = t.payeeId?.let { id -> payees.firstOrNull { it.id == id }?.transferAccountId }
            val kind = when {
                t.isTransfer || transferTo != null -> TxKind.Transfer
                t.amount.minor > 0 -> TxKind.Income
                else -> TxKind.Expense
            }
            var n = 0L
            return TransactionForm(
                kind = kind,
                amount = MoneyInput.toInput(t.amount.abs()),
                payee = if (kind == TxKind.Transfer) "" else t.payeeName.orEmpty(),
                accountId = t.accountId,
                transferAccountId = transferTo,
                transferIn = kind == TxKind.Transfer && t.amount.minor > 0,
                categoryId = t.categoryId,
                date = runCatching { LocalDate.parse(t.date) }.getOrDefault(LocalDate.now()),
                notes = t.notes.orEmpty(),
                cleared = t.cleared,
                splits = t.subtransactions.map { s ->
                    SplitRow(n++, s.id, MoneyInput.toInput(s.amount.abs()), s.categoryId, s.notes.orEmpty())
                },
            )
        }
    }
}
