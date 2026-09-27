package app.centsible.core.domain

/** Failures the UI knows how to present. Mapped from the bridge's problem codes. */
sealed class BridgeException(message: String) : Exception(message) {
    class Unauthorized(message: String) : BridgeException(message)
    class Forbidden(message: String) : BridgeException(message)
    class NotFound(message: String) : BridgeException(message)
    class Validation(message: String) : BridgeException(message)
    class Conflict(message: String) : BridgeException(message)
    class FeatureUnavailable(message: String) : BridgeException(message)
    class ActualUnavailable(message: String) : BridgeException(message)
    class RateLimited(message: String) : BridgeException(message)
    class Network(message: String, cause: Throwable? = null) : BridgeException(message) {
        init { cause?.let(::initCause) }
    }
    class Unexpected(message: String) : BridgeException(message)
    /** The bridge was unreachable, so the change was saved on the phone to send later. */
    class QueuedOffline(message: String = "Saved offline. It will sync when you're back online.") : BridgeException(message)
}

/** Short, human-readable text for snackbars and error states. */
fun Throwable.userMessage(): String = when (this) {
    is BridgeException.QueuedOffline -> message ?: "Saved offline"
    is BridgeException.Network -> "Can't reach your bridge. Check your connection."
    is BridgeException.Unauthorized -> "This device was signed out. Pair it again."
    is BridgeException.Forbidden -> "You don't have permission to do that."
    is BridgeException.FeatureUnavailable -> "Your Actual server doesn't support this yet."
    is BridgeException.ActualUnavailable -> "The bridge can't reach your Actual server."
    is BridgeException -> message ?: "Something went wrong."
    else -> "Something went wrong."
}
