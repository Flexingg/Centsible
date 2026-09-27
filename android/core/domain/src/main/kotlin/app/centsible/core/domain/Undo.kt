package app.centsible.core.domain

import app.centsible.core.model.NewTransaction
import app.centsible.core.model.Transaction
import app.centsible.core.model.TransactionId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Something that just happened and can be taken back from a snackbar. */
class Undoable(val message: String, val undo: suspend () -> Unit)

/**
 * App-wide, because the screen that did the thing (an editor) has usually closed by
 * the time the snackbar shows on the screen underneath.
 */
@Singleton
class UndoCenter @Inject constructor() {
    private val events = MutableSharedFlow<Undoable>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val offers: SharedFlow<Undoable> = events.asSharedFlow()

    fun offer(message: String, undo: suspend () -> Unit) {
        events.tryEmit(Undoable(message, undo))
    }
}

/**
 * Rebuilds a deleted transaction. Actual keeps a tombstone for the old id, so it comes
 * back under a new one. Splits come back as splits; a transfer comes back as a transfer
 * because its payee is the other account's transfer payee.
 */
fun Transaction.recreate(newId: TransactionId = TransactionId(UUID.randomUUID().toString())): NewTransaction = NewTransaction(
    id = newId,
    accountId = accountId,
    date = date,
    amount = amount,
    payeeId = payeeId,
    payeeName = payeeName.takeIf { payeeId == null },
    categoryId = categoryId.takeIf { subtransactions.isEmpty() },
    notes = notes,
    cleared = cleared,
    splits = subtransactions.map { NewTransaction.Split(it.amount, it.categoryId, it.notes) },
)
