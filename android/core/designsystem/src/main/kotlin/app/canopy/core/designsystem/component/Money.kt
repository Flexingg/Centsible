package app.canopy.core.designsystem.component

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import app.canopy.core.designsystem.theme.CanopyTheme
import app.canopy.core.model.Money
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlin.math.abs

/** Locale-aware money formatting. Actual budgets are single-currency; USD by default. */
object MoneyFormat {
    var currency: Currency = Currency.getInstance("USD")

    fun format(money: Money, showCents: Boolean = true, signed: Boolean = false, locale: Locale = Locale.getDefault()): String {
        val nf = NumberFormat.getCurrencyInstance(locale).apply {
            currency = this@MoneyFormat.currency
            maximumFractionDigits = if (showCents) 2 else 0
            minimumFractionDigits = if (showCents) 2 else 0
        }
        val text = nf.format(abs(money.minor) / 100.0)
        return when {
            money.minor < 0 -> "-$text"
            signed && money.minor > 0 -> "+$text"
            else -> text
        }
    }

    /** "$1.2K", "$84.2K", "$1.3M" for dense cards and charts. */
    fun compact(money: Money): String {
        val v = abs(money.minor) / 100.0
        val sign = if (money.minor < 0) "-" else ""
        val symbol = currency.getSymbol(Locale.getDefault())
        return sign + when {
            v >= 1_000_000 -> "%s%.1fM".format(symbol, v / 1_000_000)
            v >= 10_000 -> "%s%.1fK".format(symbol, v / 1_000)
            else -> format(Money(abs(money.minor)), showCents = false)
        }
    }
}

enum class MoneyTone { Neutral, Signed, Positive, Negative }

@Composable
fun MoneyText(
    amount: Money,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    tone: MoneyTone = MoneyTone.Neutral,
    showCents: Boolean = true,
    signed: Boolean = false,
    fontWeight: FontWeight? = null,
    color: Color = Color.Unspecified,
) {
    val colors = CanopyTheme.colors
    val resolved = when {
        color != Color.Unspecified -> color
        tone == MoneyTone.Positive -> colors.positive
        tone == MoneyTone.Negative -> colors.negative
        tone == MoneyTone.Signed && amount.minor > 0 -> colors.positive
        tone == MoneyTone.Signed && amount.minor < 0 -> colors.textPrimary
        else -> Color.Unspecified
    }
    Text(
        text = MoneyFormat.format(amount, showCents = showCents, signed = signed),
        modifier = modifier,
        style = style.copy(fontFeatureSettings = "tnum"),
        color = resolved,
        fontWeight = fontWeight,
        maxLines = 1,
    )
}
