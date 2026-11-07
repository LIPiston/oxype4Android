package dev.oxype.client.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.oxype.client.data.AuthOwner
import dev.oxype.client.data.OxypeRepository
import dev.oxype.client.data.ProfileSnapshot
import dev.oxype.client.data.oxype.OxypeErrorKind
import dev.oxype.client.data.oxype.OxypeException
import dev.oxype.client.data.oxype.UserProfile
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The profile page's state.
 *
 * Everything the page draws comes from one snapshot: the name, the id, the avatar URL and
 * the server's own upload limits. The upload/removal flags are here rather than in the
 * screen so a rotation keeps showing the same spinner, and [message] is a one-shot banner
 * the screen consumes after showing it.
 */
data class ProfileUiState(
    val userId: Long? = null,
    val username: String? = null,
    /** Already absolute; `null` means "no picture", which is a normal state. */
    val avatarUrl: String? = null,
    val hasAvatar: Boolean = false,
    val maxBytes: Long = UserProfile.DEFAULT_AVATAR_MAX_BYTES,
    val maxEdge: Int = UserProfile.DEFAULT_AVATAR_MAX_EDGE,
    val formats: List<String> = UserProfile.DEFAULT_AVATAR_FORMATS,
    val isLoading: Boolean = false,
    val isUploading: Boolean = false,
    val isRemoving: Boolean = false,
    /** The address the profile was read from; shown on the page and used by "服务器" card. */
    val serverUrl: String = "",
    /** A one-shot result for the snackbar; the screen clears it after showing it. */
    val message: String? = null,
    /** A recoverable load failure; the page stays usable. */
    val error: String? = null
)

/**
 * Loading and avatar actions for the signed-in account.
 *
 * Three guards keep a late answer from painting the wrong page:
 *
 * - the repository already refuses to publish for a login/server that moved on;
 * - [generation] is bumped on every [open] and [close], so an upload started on one visit
 *   cannot land on the next one;
 * - a 401 has already moved the app to the sign-in screen, so no error text is painted
 *   over a page that is about to disappear.
 */
class ProfileViewModel(private val repository: OxypeRepository) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState(serverUrl = repository.baseUrl()))
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    /** Bumped on every [open] and [close]; answers from an older visit are dropped. */
    private var generation: Long = 0L
    private var loading: Job? = null
    private var action: Job? = null

    /**
     * Loads the profile for a fresh visit.
     *
     * Called from the screen on first composition. A rotation re-runs it, and the cached
     * snapshot in the repository is shown immediately while a background refresh runs —
     * the picture should not flash away just because the device was turned.
     */
    fun open() {
        applyCachedProfile()
        refresh()
    }    /** Re-reads the profile without hiding what is already on screen. */
    fun refresh() {
        if (_state.value.isLoading) return
        val generation = ++this.generation
        _state.value = _state.value.copy(
            isLoading = true,
            error = null,
            serverUrl = repository.baseUrl()
        )
        loading?.cancel()
        loading = viewModelScope.launch {
            try {
                val profile = repository.loadProfile()
                if (!isCurrent(generation)) return@launch
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = null,
                    serverUrl = repository.baseUrl()
                ).withProfile(profile)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isCurrent(generation)) return@launch
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = if (repository.currentUserId() == null) null else readable(e),
                    serverUrl = repository.baseUrl()
                )
            }
        }
    }

    /** Shows a reason from the picker (an unreadable or unsupported file). */
    fun report(reason: String) {
        _state.value = _state.value.copy(message = reason)
    }

    /**
     * Validates [bytes] locally, then uploads them as the account's avatar.
     *
     * The gate runs before any request: a picture the server would refuse is answered
     * with its reason instead of a round trip. The previous avatar stays on screen until
     * the server accepts the new one, so a failure never looks like a save.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun setAvatar(bytes: ByteArray) {
        if (_state.value.isUploading) return
        val rejection = AvatarUploadRules.rejection(bytes)
        if (rejection != null) {
            _state.value = _state.value.copy(message = rejection)
            return
        }
        val generation = this.generation
        val owner: AuthOwner = repository.authOwner() ?: run {
            _state.value = _state.value.copy(message = "请先登录")
            return
        }
        _state.value = _state.value.copy(isUploading = true)
        action?.cancel()
        action = viewModelScope.launch {
            try {
                val encoded = Base64.encode(bytes)
                val profile = repository.uploadAvatar(encoded)
                if (!isCurrent(generation)) return@launch
                _state.value = _state.value.copy(
                    isUploading = false,
                    message = "头像已更新"
                ).withProfile(profile)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isCurrent(generation)) return@launch
                _state.value = _state.value.copy(
                    isUploading = false,
                    message = if (!repository.isCurrentAuth(owner)) null else readable(e)
                )
            }
        }
    }

    /** Removes the avatar; disabled while it is in flight or when there is none. */
    fun removeAvatar() {
        if (_state.value.isRemoving || _state.value.isUploading) return
        if (!_state.value.hasAvatar) return
        val generation = this.generation
        val owner: AuthOwner = repository.authOwner() ?: run {
            _state.value = _state.value.copy(message = "请先登录")
            return
        }
        _state.value = _state.value.copy(isRemoving = true)
        action?.cancel()
        action = viewModelScope.launch {
            try {
                val profile = repository.removeAvatar()
                if (!isCurrent(generation)) return@launch
                _state.value = _state.value.copy(
                    isRemoving = false,
                    message = "头像已移除"
                ).withProfile(profile)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isCurrent(generation)) return@launch
                _state.value = _state.value.copy(
                    isRemoving = false,
                    message = if (!repository.isCurrentAuth(owner)) null else readable(e)
                )
            }
        }
    }

    /** The page left the screen: late answers from it must not be written. */
    fun close() {
        generation++
        loading?.cancel()
        loading = null
        action?.cancel()
        action = null
        _state.value = _state.value.copy(isLoading = false, isUploading = false, isRemoving = false)
    }

    fun consumeMessage() {
        if (_state.value.message == null) return
        _state.value = _state.value.copy(message = null)
    }

    fun consumeError() {
        if (_state.value.error == null) return
        _state.value = _state.value.copy(error = null)
    }

    /** Shows whatever the repository already knows while a refresh is in flight. */
    private fun applyCachedProfile() {
        val cached = repository.profile.value ?: return
        _state.value = _state.value.withProfile(cached)
    }

    private fun isCurrent(generation: Long): Boolean = generation == this.generation

    private fun ProfileUiState.withProfile(profile: ProfileSnapshot): ProfileUiState = copy(
        userId = profile.userId,
        username = profile.username,
        avatarUrl = profile.avatarUrl,
        hasAvatar = profile.hasAvatar,
        maxBytes = profile.maxBytes,
        maxEdge = profile.maxEdge,
        formats = profile.formats
    )

    /**
     * A message for the user.
     *
     * A 401 is deliberately silent: the credential flow already moved the app to the
     * sign-in screen, and a banner on a page that is being replaced only flashes.
     */
    private fun readable(error: Throwable): String? {
        val oxype = error as? OxypeException
        return when {
            oxype?.kind == OxypeErrorKind.UNAUTHORIZED -> null
            oxype != null -> oxype.message
            else -> error.message ?: "操作失败，请稍后再试"
        }
    }
}
