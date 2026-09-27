package app.centsible.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object CentsibleTheme {
    val colors: CentsibleColors
        @Composable @ReadOnlyComposable get() = LocalCentsibleColors.current
}

private val base = Typography()

/** Tabular numerals keep columns of money aligned. */
private const val TABULAR = "tnum"

val CentsibleTypography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = TABULAR, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = TABULAR, letterSpacing = (-0.25).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = TABULAR),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Medium),
    bodyLarge = base.bodyLarge,
    bodyMedium = base.bodyMedium,
    bodySmall = base.bodySmall,
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
    labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

val MoneyStyle = TextStyle(fontFeatureSettings = TABULAR)

@Composable
fun CentsibleTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkCentsibleColors else LightCentsibleColors
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.card,
            primaryContainer = colors.accentSoft,
            // Segmented buttons, chips and nav indicators use the secondary container.
            secondaryContainer = colors.accentSoft,
            onSecondaryContainer = colors.textPrimary,
            background = colors.canvas,
            surface = colors.card,
            surfaceContainer = colors.card,
            surfaceContainerLow = colors.cardMuted,
            surfaceContainerHigh = colors.card,
            onSurface = colors.textPrimary,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.border,
            outlineVariant = colors.border,
            error = colors.negative,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.card,
            primaryContainer = colors.accentSoft,
            // Segmented buttons, chips and nav indicators use the secondary container.
            secondaryContainer = colors.accentSoft,
            onSecondaryContainer = colors.textPrimary,
            background = colors.canvas,
            surface = colors.card,
            surfaceContainer = colors.card,
            surfaceContainerLow = colors.cardMuted,
            surfaceContainerHigh = colors.card,
            onSurface = colors.textPrimary,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.border,
            outlineVariant = colors.border,
            error = colors.negative,
        )
    }
    CompositionLocalProvider(LocalCentsibleColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = CentsibleTypography, content = content)
    }
}
