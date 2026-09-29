package app.centsible.core.model

/** This person's Home: widget ids in their order, and the hidden ones (empty = the default). */
data class HomeLayout(val order: List<String> = emptyList(), val hidden: Set<String> = emptySet())

/** A category's (or group's) color and emoji, chosen by the household. Null means the default. */
data class Appearance(val color: Long? = null, val emoji: String? = null)
