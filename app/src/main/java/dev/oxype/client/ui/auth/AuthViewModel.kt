package dev.oxype.client.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.oxype.client.data.OxypeRepository
import dev.oxype.client.data.oxype.OxypeException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AuthMode {
    LOGIN,
    REGISTER
}

data class AuthUiState(
    val mode: AuthMode = AuthMode.LOGIN,
    val userId: String = "",
    val username: String = "",
    val password: String = "",
    val serverUrl: String = "",
    val serverNotice: String? = null,
    val isBusy: Boolean = false,
    val error: String? = null,
    val notice: String? = null
) {
    val isValid: Boolean
        get() = password.length in MIN_PASSWORD..MAX_PASSWORD && when (mode) {
            AuthMode.LOGIN -> userId.toLongOrNull()?.let { it > 0 } == true
            AuthMode.REGISTER -> username.isNotBlank() && username.length <= MAX_USERNAME
        }

    companion object {
        const val MIN_PASSWORD = 6
        const val MAX_PASSWORD = 20
        const val MAX_USERNAME = 15
    }
}

/**
 * Login/registration state.
 *
 * The two modes differ in what the server needs: login takes an existing numeric user
 * id plus password, registration takes a new username plus password.
 */
class AuthViewModel(private val repository: OxypeRepository) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState(serverUrl = repository.baseUrl()))
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    init {
        // The address can also be changed in Settings; when a session ends, the auth
        // screen must show the address that is actually configured, not the one it was
        // created with.
        viewModelScope.launch {
            repository.credentials.collect { credentials ->
                if (credentials == null) {
                    _state.value = _state.value.copy(serverUrl = repository.baseUrl())
                }
            }
        }
    }

    fun setMode(mode: AuthMode) {
        if (mode == _state.value.mode) return
        _state.value = _state.value.copy(mode = mode, error = null, notice = null)
    }

    /** Edits the address shown on the auth screen, before any account exists. */
    fun updateServerUrl(value: String) {
        _state.value = _state.value.copy(serverUrl = value, serverNotice = null)
    }

    /**
     * Applies the address immediately.
     *
     * The default `10.0.2.2:12345` is only reachable from an emulator, so the address
     * has to be settable while signed out or a user with a real server cannot get past
     * the auth screen at all.
     */
    fun saveServerUrl() {
        val raw = _state.value.serverUrl
        if (raw.isBlank()) {
            _state.value = _state.value.copy(serverNotice = "服务器地址不能为空")
            return
        }
        repository.updateBaseUrl(raw)
        _state.value = _state.value.copy(
            serverUrl = repository.baseUrl(),
            serverNotice = "服务器地址已保存"
        )
    }

    fun consumeServerNotice() {
        _state.value = _state.value.copy(serverNotice = null)
    }

    fun setUserId(value: String) {
        // Keep only digits: the server parses this path segment as a number.
        _state.value = _state.value.copy(
            userId = value.filter { it.isDigit() }.take(18),
            error = null
        )
    }

    fun setUsername(value: String) {
        _state.value = _state.value.copy(
            username = value.take(AuthUiState.MAX_USERNAME),
            error = null
        )
    }

    fun setPassword(value: String) {
        _state.value = _state.value.copy(
            password = value.take(AuthUiState.MAX_PASSWORD),
            error = null
        )
    }

    /** Clears a message that has already been shown (e.g. a session-expiry notice). */
    fun consumeNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    /** Called after the repository reports a dead stored token. */
    fun showExpiredNotice() {
        _state.value = _state.value.copy(
            notice = "登录已失效，请重新登录",
            error = null
        )
    }

    fun submit() {
        val snapshot = _state.value
        if (snapshot.isBusy || !snapshot.isValid) return
        _state.value = snapshot.copy(isBusy = true, error = null, notice = null)

        viewModelScope.launch {
            try {
                when (snapshot.mode) {
                    AuthMode.LOGIN -> {
                        val userId = snapshot.userId.toLongOrNull()
                        if (userId == null) {
                            _state.value = _state.value.copy(
                                isBusy = false,
                                error = "请输入数字用户编号"
                            )
                            return@launch
                        }
                        repository.login(userId, snapshot.password)
                    }

                    AuthMode.REGISTER -> repository.register(
                        snapshot.username.trim(),
                        snapshot.password
                    )
                }
                // Signing in clears these values so a switch to the other mode does not
                // carry the previous password around. The server address is
                // configuration, not credentials, so it stays.
                _state.value = AuthUiState(serverUrl = repository.baseUrl())
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Leaving the screen cancels the submit; that is not a failed login.
                throw e
            } catch (e: OxypeException) {
                _state.value = _state.value.copy(isBusy = false, error = e.message)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isBusy = false,
                    error = e.message ?: "操作失败"
                )
            }
        }
    }
}
