package app.centsible.core.designsystem.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Material 3 Expressive + Material You dynamic color tokens for home-screen widgets
 * and custom chart cards. On Android 12+ these derive directly from the user's wallpaper.
 */
@Immutable
data class MaterialExpressiveTokens(
    val colors: CentsibleColors,
    val surfaceContainer: Color,
    val surfaceContainerLow: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val error: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val palette: List<Color>,
)

private val FallbackExpressiveLight = lightColorScheme(
    primary = Color(0xFF9C4321),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBCE),
    onPrimaryContainer = Color(0xFF380D00),
    secondary = Color(0xFF77574B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDBCE),
    onSecondaryContainer = Color(0xFF2C160D),
    tertiary = Color(0xFF47664A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC8ECC8),
    onTertiaryContainer = Color(0xFF04210B),
    surface = Color(0xFFFFF8F6),
    surfaceContainerLow = Color(0xFFFFF1EC),
    surfaceContainer = Color(0xFFFCEAE4),
    surfaceContainerHigh = Color(0xFFF6E4DE),
    surfaceContainerHighest = Color(0xFFF0DED8),
    onSurface = Color(0xFF231917),
    onSurfaceVariant = Color(0xFF53433E),
    outline = Color(0xFF85736D),
    outlineVariant = Color(0xFFD8C2BA),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val FallbackExpressiveDark = darkColorScheme(
    primary = Color(0xFFFFB59A),
    onPrimary = Color(0xFF5A1C00),
    primaryContainer = Color(0xFF7E2C0C),
    onPrimaryContainer = Color(0xFFFFDBCE),
    secondary = Color(0xFFE7BDB0),
    onSecondary = Color(0xFF442A20),
    secondaryContainer = Color(0xFF5D4035),
    onSecondaryContainer = Color(0xFFFFDBCE),
    tertiary = Color(0xFFACD0AD),
    onTertiary = Color(0xFF19371E),
    tertiaryContainer = Color(0xFF304E33),
    onTertiaryContainer = Color(0xFFC8ECC8),
    surface = Color(0xFF1A110F),
    surfaceContainerLow = Color(0xFF231917),
    surfaceContainer = Color(0xFF271D1B),
    surfaceContainerHigh = Color(0xFF322825),
    surfaceContainerHighest = Color(0xFF3D322F),
    onSurface = Color(0xFFF1DFD9),
    onSurfaceVariant = Color(0xFFD8C2BA),
    outline = Color(0xFFA08D86),
    outlineVariant = Color(0xFF53433E),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/** Builds Material You / Material 3 Expressive tokens from the device wallpaper (Android 12+) or fallback. */
fun materialExpressiveTokens(context: Context, darkTheme: Boolean): MaterialExpressiveTokens {
    val scheme: ColorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (darkTheme) FallbackExpressiveDark else FallbackExpressiveLight
    }
    val base = if (darkTheme) DarkCentsibleColors else LightCentsibleColors
    val centsibleColors = CentsibleColors(
        canvas = scheme.surface,
        card = scheme.surfaceContainerLow,
        cardMuted = scheme.surfaceContainerHigh,
        border = scheme.outlineVariant,
        textPrimary = scheme.onSurface,
        textSecondary = scheme.onSurfaceVariant,
        textTertiary = scheme.outline,
        accent = scheme.primary,
        accentSoft = scheme.primaryContainer,
        // Keep financial positive/negative clearly legible while harmonizing with M3.
        positive = base.positive,
        positiveSoft = scheme.tertiaryContainer,
        negative = scheme.error,
        negativeSoft = scheme.errorContainer,
        warning = base.warning,
        track = scheme.surfaceContainerHighest,
        series1 = scheme.primary,
        series2 = scheme.tertiary,
        chartGrid = scheme.outlineVariant.copy(alpha = 0.55f),
        isDark = darkTheme,
    )
    return MaterialExpressiveTokens(
        colors = centsibleColors,
        surfaceContainer = scheme.surfaceContainer,
        surfaceContainerLow = scheme.surfaceContainerLow,
        surfaceContainerHigh = scheme.surfaceContainerHigh,
        surfaceContainerHighest = scheme.surfaceContainerHighest,
        primary = scheme.primary,
        onPrimary = scheme.onPrimary,
        primaryContainer = scheme.primaryContainer,
        onPrimaryContainer = scheme.onPrimaryContainer,
        secondary = scheme.secondary,
        secondaryContainer = scheme.secondaryContainer,
        onSecondaryContainer = scheme.onSecondaryContainer,
        tertiary = scheme.tertiary,
        tertiaryContainer = scheme.tertiaryContainer,
        onTertiaryContainer = scheme.onTertiaryContainer,
        error = scheme.error,
        errorContainer = scheme.errorContainer,
        onErrorContainer = scheme.onErrorContainer,
        palette = listOf(
            scheme.primary,
            scheme.tertiary,
            scheme.secondary,
            base.positive,
            base.warning,
            scheme.outline,
        ),
    )
}

@Composable
fun rememberMaterialExpressiveTokens(darkTheme: Boolean = isSystemInDarkTheme()): MaterialExpressiveTokens {
    val context = LocalContext.current
    return remember(context, darkTheme) { materialExpressiveTokens(context, darkTheme) }
}
