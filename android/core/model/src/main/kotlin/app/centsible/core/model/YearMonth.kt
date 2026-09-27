package app.centsible.core.model

/** A budget month, "YYYY-MM". */
@JvmInline
value class YearMonth(val raw: String) : Comparable<YearMonth> {
    init {
        require(PATTERN.matches(raw)) { "Invalid month: $raw" }
    }

    val year get() = raw.substring(0, 4).toInt()
    val month get() = raw.substring(5, 7).toInt()

    fun plus(months: Int): YearMonth {
        val index = year * 12 + (month - 1) + months
        return of(index / 12, index % 12 + 1)
    }

    override fun compareTo(other: YearMonth) = raw.compareTo(other.raw)

    companion object {
        private val PATTERN = Regex("""^\d{4}-(0[1-9]|1[0-2])$""")
        fun of(year: Int, month: Int) = YearMonth("%04d-%02d".format(year, month))
    }
}
