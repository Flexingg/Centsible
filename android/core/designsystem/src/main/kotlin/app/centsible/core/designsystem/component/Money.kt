package app.centsible.core.designsystem.component

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.Money
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
    /**
     * Hero numbers count up from zero when first shown, then roll to each new value,
     * glowing green (up) or red (down) for a moment.
     */
    animate: Boolean = false,
) {
    val colors = CentsibleTheme.colors
    val shown = if (animate) Money(app.centsible.core.designsystem.motion.animateMinorUnits(amount.minor)) else amount
    val glow = if (animate) changeGlow(amount.minor) else 0f to 0
    val base = when {
        color != Color.Unspecified -> color
        tone == MoneyTone.Positive -> colors.positive
        tone == MoneyTone.Negative -> colors.negative
        tone == MoneyTone.Signed && amount.minor > 0 -> colors.positive
        tone == MoneyTone.Signed && amount.minor < 0 -> colors.textPrimary
        else -> Color.Unspecified
    }
    val resolved = if (glow.first > 0f) {
        val tint = if (glow.second > 0) colors.positive else colors.negative
        androidx.compose.ui.graphics.lerp(if (base == Color.Unspecified) androidx.compose.material3.LocalContentColor.current else base, tint, glow.first)
    } else {
        base
    }
    Text(
        text = MoneyFormat.format(shown, showCents = showCents, signed = signed),
        modifier = modifier,
        style = style.copy(fontFeatureSettings = "tnum"),
        color = resolved,
        fontWeight = fontWeight,
        maxLines = 1,
    )
}

/** A brief tint after the value changes (not on first show): (strength 0..1, direction). */
@Composable
private fun changeGlow(value: Long): Pair<Float, Int> {
    val reduced = app.centsible.core.designsystem.motion.reducedMotion
    val previous = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Long?>(null) }
    val strength = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    val direction = androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(value) {
        val before = previous.value
        previous.value = value
        if (before == null || before == value || reduced) return@LaunchedEffect
        direction.intValue = if (value > before) 1 else -1
        strength.snapTo(0.9f)
        strength.animateTo(0f, androidx.compose.animation.core.tween(1200, easing = app.centsible.core.designsystem.motion.Motion.EaseOut))
    }
    return strength.value to direction.intValue
}
