package dev.oxype.client.ui.groupinfo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.oxype.client.data.OxypeRepository
import dev.oxype.client.data.oxype.GroupInfo
import dev.oxype.client.data.oxype.OxypeException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The group info page's state.
 *
 * [sessionId] is kept even while loading or failed so a retry knows what to ask for, and
 * [currentUserId] lets the member rows mark the account's own row without another lookup.
 */
data class GroupInfoUiState(
    val sessionId: Long = 0L,
    val isLoading: Boolean = true,
    val info: GroupInfo? = null,
    val error: String? = null,
    val currentUserId: Long = 0L
)

/**
 * Loading, retrying and error state for one group info page.
 *
 * The page is a leaf: it does not poll and it writes nothing outside itself, so its only
 * real hazard is a load that answers after the user already left for another page. That
 * is what [generation] guards.
 */
class GroupInfoViewModel(private val repository: OxypeRepository) : ViewModel() {

    private val _state = MutableStateFlow(
        GroupInfoUiState(currentUserId = repository.currentUserId() ?: 0L)
    )
    val state: StateFlow<GroupInfoUiState> = _state.asStateFlow()

    /**
     * Bumped on every [open].
     *
     * A load captures it before its first suspension and drops its answer once the value
     * moved, so a slow page for A cannot replace the page the user opened afterwards.
     */
    private var generation: Long = 0L
    private var loading: Job? = null

    /**
     * The login/server the currently shown page was loaded for.
     *
     * Compared with [authKey] before a re-bind: a cached page is only reusable for the
     * exact identity that produced it.
     */
    private var boundAuthKey: String? = null

    /**
     * Identity a cached page belongs to.
     *
     * A user id is not enough: signing the same account in again issues a new token, and
     * the same ids on another server are a different group entirely. Both the generation
     * and the server address take part, so any of those changes forces a reload instead
     * of showing the previous login's roster, roles and `（我）` marker.
     */
    private fun authKey(): String {
        val owner = repository.authOwner()
        val generation = owner?.generation ?: -1L
        val userId = owner?.credentials?.userId ?: 0L
        return "$generation|$userId|${repository.baseUrl()}"
    }

    /** Binds the page to [sessionId] and loads it. */
    fun open(sessionId: Long, reload: Boolean = false) {
        if (sessionId <= 0L) return
        val currentUserId = repository.currentUserId() ?: 0L
        val key = authKey()
        if (
            !reload &&
            sessionId == _state.value.sessionId &&
            _state.value.info != null &&
            key == boundAuthKey
        ) {
            // Re-binding the same page (a rotation) reuses what is already loaded:
            // refetching would flash the spinner over content the user has already seen.
            // Everything the page shows — `（我）`, the role badges, the roster — belongs
            // to one login on one server, so a new key always reloads. `reload = true` is
            // a fresh visit (returning from the chat) and asks the server again, because
            // names, roles and membership change while the app is open.
            return
        }
        boundAuthKey = key
        _state.value = GroupInfoUiState(
            sessionId = sessionId,
            isLoading = true,
            info = null,
            error = null,
            currentUserId = currentUserId
        )
        load(sessionId)
    }

    /** Re-runs the current page's load, keeping what is on screen until it answers. */
    fun refresh() {
        val sessionId = _state.value.sessionId
        if (sessionId <= 0L || _state.value.isLoading) return
        _state.value = _state.value.copy(isLoading = true, error = null)
        load(sessionId)
    }

    fun consumeError() {
        if (_state.value.error == null) return
        _state.value = _state.value.copy(error = null)
    }

    private fun load(sessionId: Long) {
        val generation = ++generation
        loading?.cancel()
        loading = viewModelScope.launch {
            try {
                val info = repository.loadGroupInfo(sessionId)
                if (!isCurrent(generation, sessionId)) return@launch
                _state.value = _state.value.copy(
                    info = info,
                    isLoading = false,
                    error = null,
                    currentUserId = repository.currentUserId() ?: 0L
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isCurrent(generation, sessionId)) return@launch
                // A 401 already moved the app to auth through the credential signal;
                // painting an error over a page that is about to disappear would flash.
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = if (repository.currentUserId() == null) null else readable(e)
                )
            }
        }
    }

    /** True while [generation]/[sessionId] is still the page on screen. */
    private fun isCurrent(generation: Long, sessionId: Long): Boolean =
        generation == this.generation && _state.value.sessionId == sessionId

    private fun readable(error: Throwable): String =
        (error as? OxypeException)?.message ?: error.message ?: "无法加载群聊信息"
}
