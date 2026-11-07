package dev.oxype.client.ui.theme

import androidx.compose.ui.graphics.luminance
import dev.oxype.client.data.session.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The colour painted *behind* the animated destinations.
 *
 * Every destination transition cross-fades, so mid-fade the composited screen is less
 * than opaque and whatever sits underneath shows through. That underneath is the frame
 * colour: it has to be the themed app background and it has to be opaque, otherwise a
 * dark theme blends through the light window background and the screen flashes white.
 */
class ThemeFrameTest {

    @Test
    fun `both frames are opaque`() {
        assertEquals(1f, appFrameColor(darkTheme = false).alpha)
        assertEquals(1f, appFrameColor(darkTheme = true).alpha)
    }

    @Test
    fun `the frame is the app background of the resolved theme`() {
        assertEquals(LightOxypeColors.appBackground, appFrameColor(darkTheme = false))
        assertEquals(DarkOxypeColors.appBackground, appFrameColor(darkTheme = true))
    }

    @Test
    fun `the two frames are the theme's own light and dark backgrounds`() {
        // Guards against reintroducing a single hardcoded window colour: the dark frame
        // must be genuinely dark and the light one genuinely light.
        assertNotEquals(appFrameColor(darkTheme = false), appFrameColor(darkTheme = true))
        assertTrue(
            "dark frame must be dark, was ${appFrameColor(darkTheme = true).luminance()}",
            appFrameColor(darkTheme = true).luminance() < 0.15f
        )
        assertTrue(
            "light frame must be light, was ${appFrameColor(darkTheme = false).luminance()}",
            appFrameColor(darkTheme = false).luminance() > 0.7f
        )
    }

    @Test
    fun `an explicit choice wins over the system`() {
        assertEquals(false, resolveDarkTheme(ThemeMode.LIGHT, systemInDark = true))
        assertEquals(true, resolveDarkTheme(ThemeMode.DARK, systemInDark = false))
    }

    @Test
    fun `system mode follows the system`() {
        assertEquals(true, resolveDarkTheme(ThemeMode.SYSTEM, systemInDark = true))
        assertEquals(false, resolveDarkTheme(ThemeMode.SYSTEM, systemInDark = false))
    }
}
