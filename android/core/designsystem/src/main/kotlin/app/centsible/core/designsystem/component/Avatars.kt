package app.centsible.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.centsible.core.designsystem.theme.CentsibleTheme
import kotlin.math.absoluteValue

/**
 * Actual has no category icons yet, so pick an emoji from the name. The
 * "category icons" extension (Phase 4) will let people choose their own.
 */
object CategoryEmoji {
    private val rules = listOf(
        listOf("grocer", "food", "supermarket") to "🛒",
        listOf("dining", "restaurant", "eating out", "takeout") to "🍽️",
        listOf("coffee", "cafe") to "☕",
        listOf("rent", "mortgage", "housing", "home") to "🏠",
        listOf("utilit", "electric", "power", "water") to "💡",
        listOf("internet", "phone", "mobile", "wifi") to "📶",
        listOf("gas", "fuel") to "⛽",
        listOf("car", "auto", "vehicle", "transport") to "🚗",
        listOf("shopping", "clothes", "clothing") to "🛍️",
        listOf("entertain", "fun", "movies", "streaming") to "🎬",
        listOf("kid", "child", "school", "daycare") to "🧸",
        listOf("vacation", "travel", "trip") to "✈️",
        listOf("emergency", "savings", "saving") to "🛟",
        listOf("health", "medical", "doctor", "pharmacy") to "🩺",
        listOf("gift", "donation", "charity") to "🎁",
        listOf("pet", "dog", "cat") to "🐾",
        listOf("insurance") to "🛡️",
        listOf("salary", "income", "paycheck", "payroll") to "💰",
        listOf("bill") to "🧾",
        listOf("subscription") to "🔁",
        listOf("fitness", "gym") to "🏋️",
        listOf("general", "misc") to "📦",
    )

    fun forName(name: String): String {
        // Respect an emoji the user already put at the start of the category name.
        val first = name.codePointAt(0).takeIf { name.isNotEmpty() }
        if (first != null && Character.getType(first) == Character.OTHER_SYMBOL.toInt()) return String(Character.toChars(first))
        val lower = name.lowercase()
        return rules.firstOrNull { (keys, _) -> keys.any { it in lower } }?.second ?: "🏷️"
    }
}

@Composable
fun CategoryAvatar(name: String, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    Box(
        modifier.size(size).background(CentsibleTheme.colors.cardMuted, RoundedCornerShape(size / 3.2f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(CategoryEmoji.forName(name), fontSize = (size.value * 0.48f).sp)
    }
}

private val merchantPalette = listOf(
    Color(0xFF5B7CFA), Color(0xFF2FB39A), Color(0xFFEF6A3A), Color(0xFF9B6BF2),
    Color(0xFFE0A21B), Color(0xFFE2557A), Color(0xFF3D9BE9), Color(0xFF6A8F3C),
)

/** Initials on a stable color derived from the merchant name. */
@Composable
fun MerchantAvatar(name: String?, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    val label = name.orEmpty().ifBlank { "?" }
    val initials = label.split(' ', '-', '&').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
    val color = merchantPalette[label.lowercase().hashCode().absoluteValue % merchantPalette.size]
    Box(modifier.size(size).background(color.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
        Text(initials, color = color, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.36f).sp)
    }
}
