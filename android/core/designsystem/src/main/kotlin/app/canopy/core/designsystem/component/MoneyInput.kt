package app.canopy.core.designsystem.component

import app.canopy.core.model.Money
import java.math.BigDecimal
import java.math.RoundingMode

object MoneyInput {
    /** "1,234.5" → Money(123450). Returns null for anything that isn't a plain amount. */
    fun parse(text: String): Money? {
        val cleaned = text.trim().replace(",", "").removePrefix("$")
        if (cleaned.isEmpty() || !Regex("""^-?\d+(\.\d{0,2})?$""").matches(cleaned)) return null
        return Money(BigDecimal(cleaned).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact())
    }

    /** Money(123450) → "1234.50" for pre-filling an input. */
    fun toInput(money: Money): String = BigDecimal(money.minor).movePointLeft(2).toPlainString()
}
