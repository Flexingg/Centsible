package app.centsible.core.model

import kotlin.math.abs

/** Integer minor units (cents), exactly as Actual stores them. Never a Double. */
@JvmInline
value class Money(val minor: Long) : Comparable<Money> {
    operator fun plus(other: Money) = Money(minor + other.minor)
    operator fun minus(other: Money) = Money(minor - other.minor)
    operator fun unaryMinus() = Money(-minor)
    override fun compareTo(other: Money) = minor.compareTo(other.minor)

    val isNegative get() = minor < 0
    val isZero get() = minor == 0L
    fun abs() = Money(abs(minor))

    companion object {
        val Zero = Money(0)
    }
}

fun Iterable<Money>.sum(): Money = Money(sumOf { it.minor })

/**
 * Formats money for display. Locale-aware formatting lives in the design system;
 * this is the dependency-free default used by domain logic and tests.
 */
fun Money.format(symbol: String = "$", showPlus: Boolean = false): String {
    val sign = when {
        minor < 0 -> "-"
        showPlus && minor > 0 -> "+"
        else -> ""
    }
    val a = abs(minor)
    val whole = (a / 100).toString().reversed().chunked(3).joinToString(",").reversed()
    val cents = (a % 100).toString().padStart(2, '0')
    return "$sign$symbol$whole.$cents"
}
