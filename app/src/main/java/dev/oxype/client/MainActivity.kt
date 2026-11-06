package dev.oxype.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.oxype.client.ui.AppRoot
import dev.oxype.client.ui.OxypeApplication

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
        setContent {
            AppRoot(container = container)
        }
    }
}
