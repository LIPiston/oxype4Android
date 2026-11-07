package dev.oxype.client

import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.toArgb
import dev.oxype.client.ui.AppRoot
import dev.oxype.client.ui.OxypeApplication
import dev.oxype.client.ui.theme.appFrameColor
import dev.oxype.client.ui.theme.resolveDarkTheme

/**
 * The app's only Activity.
 *
 * All screens are Compose destinations inside it, so there is no activity stack to
 * manage and the back behaviour stays in one place.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as OxypeApplication).container
        // The window is painted before the first Compose frame (and behind every
        // cross-fade), so it must already be the resolved theme colour; otherwise a dark
        // theme starts on the light `windowBackground` and flashes white.
        val systemInDark = (resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val darkTheme = resolveDarkTheme(container.sessionStore.themeMode, systemInDark)
        window.setBackgroundDrawable(
            ColorDrawable(appFrameColor(darkTheme).toArgb())
        )
        setContent {
            AppRoot(container = container)
        }
    }
}
