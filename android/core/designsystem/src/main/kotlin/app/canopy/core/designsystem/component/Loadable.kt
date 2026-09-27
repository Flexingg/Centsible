package app.canopy.core.designsystem.component

/** Screen data that loads from the bridge. */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Failed(val message: String) : Loadable<Nothing>
    data class Ready<T>(val value: T, val refreshing: Boolean = false) : Loadable<T>

    val valueOrNull: T? get() = (this as? Ready<T>)?.value
}
