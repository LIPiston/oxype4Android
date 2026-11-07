package dev.oxype.client.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.oxype.client.data.OxypeRepository
import dev.oxype.client.data.oxype.OxypeApi
import dev.oxype.client.data.oxype.OxypeHttpClient
import dev.oxype.client.data.session.SessionStore
import dev.oxype.client.data.session.ThemeMode
import dev.oxype.client.ui.auth.AuthViewModel
import dev.oxype.client.ui.chat.ChatViewModel
import dev.oxype.client.ui.groupinfo.GroupInfoViewModel
import dev.oxype.client.ui.home.HomeViewModel
import dev.oxype.client.ui.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The theme choice, as the settings screen needs it.
 *
 * Kept as an interface so the screen's state logic can be exercised on the JVM without
 * an Android [Application] or a real [SessionStore].
 */
interface ThemeSettings {
    val themeMode: StateFlow<ThemeMode>
    fun setThemeMode(mode: ThemeMode)
}

/**
 * Process-wide wiring for the single repository.
 *
 * There is one repository and four small ViewModels, which does not justify a DI
 * framework; the container is built once in [OxypeApplication] and handed to a
 * factory.
 */
class AppContainer(application: Application) : ThemeSettings {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val sessionStore = SessionStore(application)

    /**
     * The theme choice as observable state. It is mirrored from [SessionStore] so the
     * UI recomposes when settings change it, rather than needing an activity restart.
     */
    private val _themeMode = MutableStateFlow(sessionStore.themeMode)
    override val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    override fun setThemeMode(mode: ThemeMode) {
        sessionStore.themeMode = mode
        _themeMode.value = mode
    }


    val httpClient = OxypeHttpClient(baseUrl = sessionStore.baseUrl)
    val repository = OxypeRepository(
        api = OxypeApi(httpClient),
        http = httpClient,
        sessionStore = sessionStore,
        scope = scope
    )

    fun dispose() {
        scope.cancel()
    }
}

class OxypeApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Creates the app's ViewModels from [AppContainer]. */
class OxypeViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val repository = container.repository
        return when {
            modelClass.isAssignableFrom(AuthViewModel::class.java) ->
                AuthViewModel(repository) as T

            modelClass.isAssignableFrom(HomeViewModel::class.java) ->
                HomeViewModel(repository) as T

            modelClass.isAssignableFrom(ChatViewModel::class.java) ->
                ChatViewModel(repository) as T

            modelClass.isAssignableFrom(GroupInfoViewModel::class.java) ->
                GroupInfoViewModel(repository) as T

            modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
                SettingsViewModel(repository, container) as T

            else -> error("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
