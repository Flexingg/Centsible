package app.canopy.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Calm, airy palette in the spirit of modern personal-finance apps: a warm neutral
 * canvas, white cards, one warm accent. Green and red only carry meaning (money in,
 * money out, overspent); they're never decoration.
 */
@Immutable
data class CanopyColors(
    val canvas: Color,
    val card: Color,
    val cardMuted: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val accentSoft: Color,
    val positive: Color,
    val positiveSoft: Color,
    val negative: Color,
    val negativeSoft: Color,
    val warning: Color,
    val track: Color,
    val isDark: Boolean,
)

val LightCanopyColors = CanopyColors(
    canvas = Color(0xFFF6F5F2),
    card = Color(0xFFFFFFFF),
    cardMuted = Color(0xFFF9F8F6),
    border = Color(0xFFE9E6E1),
    textPrimary = Color(0xFF1B1D22),
    textSecondary = Color(0xFF5F636D),
    textTertiary = Color(0xFF9A9DA5),
    accent = Color(0xFFEF6A3A),
    accentSoft = Color(0xFFFDEDE6),
    positive = Color(0xFF1C9A62),
    positiveSoft = Color(0xFFE3F5EC),
    negative = Color(0xFFDC3F42),
    negativeSoft = Color(0xFFFCE8E8),
    warning = Color(0xFFE59A1C),
    track = Color(0xFFEEECE8),
    isDark = false,
)

val DarkCanopyColors = CanopyColors(
    canvas = Color(0xFF101114),
    card = Color(0xFF1A1C20),
    cardMuted = Color(0xFF15171A),
    border = Color(0xFF2A2D33),
    textPrimary = Color(0xFFF1F1F3),
    textSecondary = Color(0xFFB0B3BA),
    textTertiary = Color(0xFF7C8089),
    accent = Color(0xFFFF8457),
    accentSoft = Color(0xFF3A241B),
    positive = Color(0xFF3CCB8A),
    positiveSoft = Color(0xFF15301F),
    negative = Color(0xFFFF6B6E),
    negativeSoft = Color(0xFF3A1B1C),
    warning = Color(0xFFF5B342),
    track = Color(0xFF2A2D33),
    isDark = true,
)

val LocalCanopyColors = staticCompositionLocalOf { LightCanopyColors }
