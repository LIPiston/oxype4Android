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
import dev.oxype.client.data.session.ThemeMode

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

internal val LightOxypeColors = OxypeColors(
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

internal val DarkOxypeColors = OxypeColors(
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
    primary = LightOxypeColors.accent,
    onPrimary = Color.White,
    secondary = LightOxypeColors.accent,
    background = LightOxypeColors.appBackground,
    onBackground = LightOxypeColors.textPrimary,
    surface = LightOxypeColors.surface,
    onSurface = LightOxypeColors.textPrimary,
    surfaceVariant = LightOxypeColors.surfaceRaised,
    onSurfaceVariant = LightOxypeColors.textSecondary,
    outline = LightOxypeColors.divider,
    error = LightOxypeColors.danger
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = DarkOxypeColors.accent,
    onPrimary = Color(0xFF10231A),
    secondary = DarkOxypeColors.accent,
    background = DarkOxypeColors.appBackground,
    onBackground = DarkOxypeColors.textPrimary,
    surface = DarkOxypeColors.surface,
    onSurface = DarkOxypeColors.textPrimary,
    surfaceVariant = DarkOxypeColors.surfaceRaised,
    onSurfaceVariant = DarkOxypeColors.textSecondary,
    outline = DarkOxypeColors.divider,
    error = DarkOxypeColors.danger
)

val LocalOxypeColors = staticCompositionLocalOf { LightOxypeColors }

@Composable
fun OxypeTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkOxypeColors else LightOxypeColors
    CompositionLocalProvider(LocalOxypeColors provides colors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            content = content
        )
    }
}

/**
 * The single rule mapping the stored [ThemeMode] preference to a boolean theme.
 *
 * `LIGHT`/`DARK` are the user's explicit choice and win over the system; `SYSTEM`
 * follows the platform. Both the composition and the window background consult this,
 * so they can never disagree about which theme is showing.
 */
internal fun resolveDarkTheme(mode: ThemeMode, systemInDark: Boolean): Boolean = when (mode) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> systemInDark
}

/**
 * The opaque colour painted behind the animated destination.
 *
 * Every screen transition cross-fades, so mid-transition the composited frame is less
 * than opaque. Whatever is painted underneath shows through at that moment: it must be
 * this theme's own app background, or a dark theme blends through the light window
 * background and the screen flashes white. Opaque by construction, since it is a solid
 * palette token.
 */
internal fun appFrameColor(darkTheme: Boolean): Color {
    val colors = if (darkTheme) DarkOxypeColors else LightOxypeColors
    return colors.appBackground
}

/** Shorthand for the non-Material tokens. */
val oxypeColors: OxypeColors
    @Composable get() = LocalOxypeColors.current
