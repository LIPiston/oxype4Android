package dev.oxype.client.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colours that Material's scheme does not name: the chat surface, the two bubble
 * roles, and the divider. Components read these tokens instead of hardcoding values,
 * so the palette can change in one place.
 */
@Immutable
data class OxypeColors(
    val appBackground: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val divider: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val incomingBubble: Color,
    val outgoingBubble: Color,
    val incomingText: Color,
    val outgoingText: Color,
    val danger: Color
)

private val LightColors = OxypeColors(
    appBackground = Color(0xFFF4F6F5),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFEDF1EF),
    divider = Color(0xFFE1E7E3),
    textPrimary = Color(0xFF18201C),
    textSecondary = Color(0xFF66756D),
    accent = Color(0xFF3E6B57),
    incomingBubble = Color(0xFFFFFFFF),
    outgoingBubble = Color(0xFFDCEAE1),
    incomingText = Color(0xFF18201C),
    outgoingText = Color(0xFF14241B),
    danger = Color(0xFFA83B33)
)

private val DarkColors = OxypeColors(
    appBackground = Color(0xFF111614),
    surface = Color(0xFF191F1C),
    surfaceRaised = Color(0xFF212925),
    divider = Color(0xFF2B3530),
    textPrimary = Color(0xFFE7EDE9),
    textSecondary = Color(0xFF9AA9A0),
    accent = Color(0xFF74A98D),
    incomingBubble = Color(0xFF212925),
    outgoingBubble = Color(0xFF2C4436),
    incomingText = Color(0xFFE7EDE9),
    outgoingText = Color(0xFFE9F3EC),
    danger = Color(0xFFE0837B)
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = LightColors.accent,
    onPrimary = Color.White,
    secondary = LightColors.accent,
    background = LightColors.appBackground,
    onBackground = LightColors.textPrimary,
    surface = LightColors.surface,
    onSurface = LightColors.textPrimary,
    surfaceVariant = LightColors.surfaceRaised,
    onSurfaceVariant = LightColors.textSecondary,
    outline = LightColors.divider,
    error = LightColors.danger
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = DarkColors.accent,
    onPrimary = Color(0xFF10231A),
    secondary = DarkColors.accent,
    background = DarkColors.appBackground,
    onBackground = DarkColors.textPrimary,
    surface = DarkColors.surface,
    onSurface = DarkColors.textPrimary,
    surfaceVariant = DarkColors.surfaceRaised,
    onSurfaceVariant = DarkColors.textSecondary,
    outline = DarkColors.divider,
    error = DarkColors.danger
)

val LocalOxypeColors = staticCompositionLocalOf { LightColors }

@Composable
fun OxypeTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColors else LightColors
    CompositionLocalProvider(LocalOxypeColors provides colors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            content = content
        )
    }
}

/** Shorthand for the non-Material tokens. */
val oxypeColors: OxypeColors
    @Composable get() = LocalOxypeColors.current
