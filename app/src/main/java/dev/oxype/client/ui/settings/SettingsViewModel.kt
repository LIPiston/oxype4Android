package dev.oxype.client.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.oxype.client.data.OxypeRepository
import dev.oxype.client.data.session.ThemeMode
import dev.oxype.client.ui.ThemeSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val serverUrl: String = "",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val userId: Long? = null,
    val username: String? = null,
    val avatarUrl: String? = null,
    val isBusy: Boolean = false,
    val message: String? = null,
    val signedOut: Boolean = false
)

/** Settings state: server address, theme, account actions. */
class SettingsViewModel(
    private val repository: OxypeRepository,
    private val themeSettings: ThemeSettings
) : ViewModel() {

    private val _state = MutableStateFlow(
        SettingsUiState(
            serverUrl = repository.baseUrl(),
            themeMode = themeSettings.themeMode.value,
            userId = repository.currentUserId(),
            username = repository.storedUsername()
        )
    )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    /**
     * Whether the address field holds text the user typed but has not saved.
     *
     * The address is configuration, not credentials: the auth screen can save it while
     * this screen already exists, so an observed change has to refresh the field. A
     * pending edit is the exception — overwriting what the user is typing would be a
     * surprise, and saving that text afterwards must store *their* value, not the
     * repository's previous one.
     */
    private var serverUrlEdited: Boolean = false

    init {
        // The account fields and the address are not a snapshot: the screen is created
        // with the app, while the address is often configured on the auth screen and
        // the account only appears after sign-in. Both have to follow the repository,
        // or saving settings would put an old default address back.
        viewModelScope.launch {
            repository.credentials.collect { credentials ->
                val current = _state.value
                _state.value = current.copy(
                    // The sign-out flag is a one-shot navigation hint. If it was never
                    // consumed because the screen was removed first, a later successful
                    // sign-in must not replay it — otherwise opening settings again
                    // would bounce back to auth.
                    signedOut = if (credentials != null) false else current.signedOut
                ).withRepositoryValues()
            }
        }
    }

    /**
     * Re-reads the address and account from the repository.
     *
     * The credentials flow only fires when the login changes; an address edited on the
     * auth screen while signed out is not a credential change. Calling this when the
     * screen becomes visible keeps the field from showing a value the user already
     * replaced elsewhere.
     */
    fun syncFromRepository() {
        _state.value = _state.value.withRepositoryValues()
    }

    private fun SettingsUiState.withRepositoryValues(): SettingsUiState = copy(
        serverUrl = if (serverUrlEdited) serverUrl else repository.baseUrl(),
        userId = repository.currentUserId(),
        username = repository.storedUsername(),
        // Whatever the profile page last read. The settings row is a shortcut to that
        // page, so it shows the same picture instead of loading a second copy.
        avatarUrl = repository.profile.value?.avatarUrl
    )

    fun updateServerUrl(value: String) {
        serverUrlEdited = true
        _state.value = _state.value.copy(serverUrl = value, message = null)
    }

    fun setThemeMode(mode: ThemeMode) {
        themeSettings.setThemeMode(mode)
        _state.value = _state.value.copy(themeMode = mode)
    }

    /** Applies the address immediately; the repository normalises scheme and slashes. */
    fun saveServerUrl() {
        // Without an edit the field only mirrors the repository, so the repository is
        // the authority. Saving the mirrored text instead could restore a default that
        // the auth screen has already replaced.
        val raw = if (serverUrlEdited) _state.value.serverUrl else repository.baseUrl()
        if (raw.isBlank()) {
            _state.value = _state.value.copy(message = "服务器地址不能为空")
            return
        }
        repository.updateBaseUrl(raw)
        serverUrlEdited = false
        _state.value = _state.value.copy(
            serverUrl = repository.baseUrl(),
            message = "服务器地址已保存"
        )
    }

    fun logout() {
        if (_state.value.isBusy) return
        _state.value = _state.value.copy(isBusy = true, message = null)
        viewModelScope.launch {
            repository.logout()
            _state.value = _state.value.copy(
                isBusy = false,
                message = null,
                signedOut = true,
                userId = null,
                username = null
            )
        }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    /**
     * Clears the one-shot sign-out flag.
     *
     * Without this the flag stays true after a later sign-in, and opening settings
     * again would replay the notification for a session that is now valid.
     */
    fun consumeSignedOut() {
        _state.value = _state.value.copy(signedOut = false)
    }
}
