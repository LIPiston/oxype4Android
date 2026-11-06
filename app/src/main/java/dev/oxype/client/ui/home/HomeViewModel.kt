package dev.oxype.client.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.oxype.client.data.AuthOwner
import dev.oxype.client.data.OxypeRepository
import dev.oxype.client.data.oxype.OxypeErrorKind
import dev.oxype.client.data.oxype.OxypeException
import dev.oxype.client.data.oxype.SessionSummary
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeUiState(
    val sessions: List<SessionSummary> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val isBusy: Boolean = false,
    val createdInvite: String? = null,
    val info: String? = null
) {
    val isEmpty: Boolean
        get() = !isLoading && error == null && sessions.isEmpty()
}

/**
 * Conversation list state, plus the create/join actions the list entry points need.
 *
 * The list is not loaded from `init`. The ViewModel is created before the stored token
 * has been verified, so loading immediately would race the restore and report
 * "请先登录". Instead it follows [OxypeRepository.credentials] and refreshes once a
 * token actually exists, which covers both a restart and a fresh sign-in.
 */
class HomeViewModel(private val repository: OxypeRepository) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /**
     * The in-flight list refresh.
     *
     * It is kept so a login change can cancel the previous account's load. Leaving it
     * running would let A's answer land after B signed in, and the "already loading"
     * guard would then also refuse to start B's own refresh.
     */
    private var loading: Job? = null

    /** The login the current list state belongs to. */
    private var boundOwner: AuthOwner? = repository.authOwner()

    init {
        viewModelScope.launch {
            repository.credentials.collect { credentials ->
                if (credentials == null) {
                    // Signed out: drop rows, and cancel whatever A was still loading so
                    // its late answer cannot repopulate the list.
                    loading?.cancel()
                    loading = null
                    boundOwner = null
                    _state.value = HomeUiState()
                } else {
                    val owner = repository.authOwner()
                    if (owner != boundOwner) {
                        // A different login (or the same account with a new token): the
                        // rows on screen describe the previous session and every request
                        // in flight belongs to it.
                        loading?.cancel()
                        loading = null
                        boundOwner = owner
                        _state.value = HomeUiState()
                    }
                    refresh()
                }
            }
        }
    }

    fun refresh() {
        if (_state.value.isLoading) return
        val owner = repository.authOwner()
        if (owner == null) {
            // Not restored or signed out yet: there is nothing to ask for, and this is
            // not an error worth showing.
            _state.value = _state.value.copy(isLoading = false)
            return
        }
        _state.value = _state.value.copy(isLoading = true, error = null)
        loading = viewModelScope.launch {
            try {
                repository.refreshSessions()
                // A refresh that finishes after the login changed says nothing about the
                // account now on screen; publishing it would show A's conversations.
                if (!isCurrentOwner(owner)) return@launch
                _state.value = _state.value.copy(
                    sessions = repository.sessions.value,
                    isLoading = false,
                    error = null
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isCurrentOwner(owner)) return@launch
                if (isRejectedToken(e)) {
                    // The login is gone: return to auth through the credential signal
                    // instead of leaving an impossible list on screen. Nothing is shown
                    // as a list error, because none of it is actionable here. The
                    // invalidation names the login that was rejected, so a late 401 for
                    // A cannot sign B out.
                    _state.value = HomeUiState()
                    repository.invalidateCredentials(owner)
                    return@launch
                }
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = readable(e)
                )
            }
        }
    }

    fun createSession(name: String, description: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || _state.value.isBusy) return
        val owner = repository.authOwner()
        _state.value = _state.value.copy(isBusy = true, error = null, info = null)
        viewModelScope.launch {
            try {
                val created = repository.createSession(trimmed, description.trim())
                // The creation is a result for the login that asked for it. After an
                // account switch the announcement (and the invite id) must not appear
                // over the new account's list.
                if (!isCurrentOwner(owner)) return@launch
                _state.value = _state.value.copy(
                    sessions = repository.sessions.value,
                    isBusy = false,
                    createdInvite = created.inviteUuid.takeIf { it.isNotBlank() },
                    info = "已创建「${created.name}」"
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isCurrentOwner(owner)) return@launch
                _state.value = _state.value.copy(isBusy = false, error = readable(e))
                invalidateOnRejectedToken(e, owner)
            }
        }
    }

    fun joinSession(uuid: String) {
        val trimmed = uuid.trim()
        if (trimmed.isEmpty() || _state.value.isBusy) return
        val owner = repository.authOwner()
        _state.value = _state.value.copy(isBusy = true, error = null, info = null)
        viewModelScope.launch {
            try {
                val detail = repository.joinSession(trimmed)
                if (!isCurrentOwner(owner)) return@launch
                _state.value = _state.value.copy(
                    sessions = repository.sessions.value,
                    isBusy = false,
                    info = "已加入「${detail.name}」"
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isCurrentOwner(owner)) return@launch
                _state.value = _state.value.copy(isBusy = false, error = readable(e))
                invalidateOnRejectedToken(e, owner)
            }
        }
    }

    fun consumeCreatedInvite() {
        _state.value = _state.value.copy(createdInvite = null)
    }

    fun consumeInfo() {
        _state.value = _state.value.copy(info = null)
    }

    fun consumeError() {
        _state.value = _state.value.copy(error = null)
    }

    fun onSessionsChanged(sessions: List<SessionSummary>) {
        _state.value = _state.value.copy(sessions = sessions)
    }

    /** True while [owner] still describes the login this screen is showing. */
    private fun isCurrentOwner(owner: AuthOwner?): Boolean = repository.isCurrentAuth(owner)

    private fun readable(error: Throwable): String =
        (error as? OxypeException)?.message ?: error.message ?: "发生了未知错误"

    /**
     * A 401 means the login is gone. Clearing it through the repository moves the whole
     * app back to the auth screen (the same credential signal used by logout and
     * revocation), and also drops any cached chat state.
     *
     * A 403 is deliberately not included: Oxype returns it for an authenticated caller
     * that lacks permission, so signing out would discard a valid session over a list
     * the user simply is not allowed to see. It stays a visible list error instead.
     */
    private fun invalidateOnRejectedToken(error: Throwable, owner: AuthOwner?) {
        if (isRejectedToken(error)) repository.invalidateCredentials(owner)
    }

    private fun isRejectedToken(error: Throwable): Boolean {
        val kind = (error as? OxypeException)?.kind ?: return false
        return kind == OxypeErrorKind.UNAUTHORIZED
    }
}
