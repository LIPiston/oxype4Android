package dev.oxype.client.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.oxype.client.data.AuthRestoreState
import dev.oxype.client.data.ChatSnapshot
import dev.oxype.client.data.OxypeRepository
import dev.oxype.client.data.oxype.ChatMessage
import dev.oxype.client.data.oxype.SessionMember
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class ChatUiState(
    val sessionId: Long = 0L,
    val title: String = "",
    val subtitle: String = "",
    val messages: List<ChatMessage> = emptyList(),
    /** Sender directory for group labels; empty means numeric fallbacks. */
    val participants: Map<Long, SessionMember> = emptyMap(),
    val isLoadingInitial: Boolean = false,
    val isLoadingOlder: Boolean = false,
    val isSending: Boolean = false,
    val reachedTop: Boolean = true,
    val draft: String = "",
    val error: String? = null,
    val currentUserId: Long = 0L,
    /** The message this composer is answering, or `null` for a plain message. */
    val replyTarget: ReplyTarget? = null
) {
    /** True when another account participates, so sender labels and avatars apply. */
    val isGroup: Boolean
        get() = isGroupConversation(participants, messages, currentUserId)
}

/**
 * The message the composer is answering.
 *
 * Only [messageId] is sent: the server resolves and freezes the quoted author and text
 * itself. [authorName] and [body] exist so the strip above the input can show what is
 * being answered without another lookup — and [body] is clamped because a long quote
 * must not push the input off screen.
 */
data class ReplyTarget(
    val messageId: Long,
    val authorName: String,
    val body: String
)

/**
 * Chat state for one conversation.
 *
 * It owns the polling lifetime, but only through an explicit foreground switch:
 * [onForeground] starts polling, [onBackground] stops it, and [close] stops it for good.
 * The screen binds these to the lifecycle so leaving the app, rotating, or switching
 * conversations never leaves a poll loop talking to the server.
 */
class ChatViewModel(private val repository: OxypeRepository) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState(currentUserId = repository.currentUserId() ?: 0L))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var watching: Job? = null
    private var opening: Job? = null
    private var isForeground: Boolean = true

    /**
     * Bumped whenever the screen binds to a different conversation or drops it.
     *
     * Every async tail captures it before its first suspension and refuses to write
     * back once it moved. Without this a send, a paging request or a manual refresh
     * started for conversation A could restore its draft, clear the busy flag or expose
     * its error after the user already switched to B.
     */
    private var bindingGeneration: Long = 0L

    /**
     * How many times the composer's quote selection changed.
     *
     * A send captures it and compares it on success: the composer is still usable while
     * the request is in flight, so a target chosen in the meantime belongs to the *next*
     * message and must survive this send's cleanup. It is bumped by
     * [beginReply]/[cancelReply] and on every rebind.
     */
    private var replySelectionRevision: Long = 0L

    /**
     * Whether the current binding ever got a first history page.
     *
     * It is what lets [refresh] tell "poll for new messages" apart from "retry the
     * failed first load": polling without a history window cannot make progress.
     */
    private var initialLoadSucceeded: Boolean = false

    /**
     * The repository conversation instance this binding was opened against.
     *
     * The screen renders the repository snapshot, so an error dismissed here has to be
     * dismissed there as well — otherwise the next publish (a poll flag, a members
     * refresh) copies the old text straight back. The version is what keeps that
     * dismissal from touching a conversation opened in the meantime.
     */
    private var repositoryChatVersion: Long = -1L

    /**
     * The account this screen's state belongs to.
     *
     * A chat is only meaningful for the credentials that opened it: when the token
     * disappears (sign-out, revocation) or a different account signs in, everything
     * held here — transcript, coordinator binding, poll loop — is stale and must be
     * dropped. Otherwise a later open with the same id would reuse the previous
     * account's transcript through the "same conversation" shortcut in [open].
     */
    private var boundUserId: Long? = repository.currentUserId()

    init {
        viewModelScope.launch {
            repository.credentials.collect { credentials ->
                val userId = credentials?.userId
                when {
                    userId == null -> {
                        if (boundUserId != null) resetForSignedOut()
                        boundUserId = null
                    }

                    boundUserId != null && userId != boundUserId -> {
                        // A different account without an intermediate sign-out.
                        resetForSignedOut()
                        boundUserId = userId
                    }

                    else -> boundUserId = userId
                }
            }
        }
    }

    /**
     * Drops all state tied to the account that just lost its credentials.
     *
     * The screen is about to leave for auth, so nothing may keep polling or keep a
     * transcript that a later, different account could see. Clearing the session id is
     * what makes reopening the same conversation a real load instead of a reuse.
     */
    fun resetForSignedOut() {
        opening?.cancel()
        opening = null
        bindingGeneration++
        initialLoadSucceeded = false
        repositoryChatVersion = -1L
        repository.closeChat()
        _state.value = ChatUiState()
    }

    /**
     * Binds the screen to a conversation.
     *
     * Re-binding the same id is what a configuration change does: it must keep the
     * transcript and the poll loop, not reload history. Switching to a different id
     * starts from a clean state.
     *
     * The load itself waits for the stored login to be resolved: a screen restored
     * straight into the chat after process death composes before
     * [OxypeRepository.restoreSession] finishes, and opening then would show a bogus
     * "请先登录" instead of waiting a moment for the token check.
     */
    fun open(sessionId: Long, title: String, subtitle: String) {
        if (sessionId == _state.value.sessionId) {
            // Same conversation: refresh the header and make sure the poller is alive,
            // without discarding what is already on screen.
            //
            // The shortcut is only valid when the first load actually succeeded. After a
            // failed open there is no history window, so polling could only come back
            // empty and the retryable error would be hidden behind a "loaded" screen. A
            // rotation or a re-composition must keep the error and wait for the user's
            // refresh instead of silently switching into a state that never loaded.
            _state.value = _state.value.copy(title = title, subtitle = subtitle)
            watchRepository()
            if (isForeground && initialLoadSucceeded && !_state.value.isLoadingInitial) {
                repository.startPolling()
            }
            return
        }

        // A switch must not show the previous transcript, its spinner, its error or its
        // unsent text under the new conversation's header: a draft typed for A would
        // otherwise be sent to B by a single tap.
        val generation = ++bindingGeneration
        initialLoadSucceeded = false
        repositoryChatVersion = -1L
        _state.value = _state.value.copy(
            sessionId = sessionId,
            title = title,
            subtitle = subtitle,
            messages = emptyList(),
            participants = emptyMap(),
            isLoadingInitial = true,
            isLoadingOlder = false,
            isSending = false,
            reachedTop = false,
            draft = "",
            error = null,
            currentUserId = repository.currentUserId() ?: 0L,
            replyTarget = null
        )
        watchRepository()
        opening?.cancel()
        opening = viewModelScope.launch {
            // Readiness gate: never request with a token that is still being verified,
            // and never request at all once the check says there is no login. A token
            // published before the state flips is also enough, which keeps this from
            // hanging if the check and the publish ever interleave.
            val ready = repository.credentials.value != null ||
                repository.authState.first { it != AuthRestoreState.CHECKING } ==
                AuthRestoreState.READY
            if (!ready) {
                // No usable login: navigation is already moving to auth. Drop the binding
                // entirely so that a later sign-in and a re-open of the *same* id is a
                // real load, not the "already open" shortcut, and stop the spinner so a
                // restored chat screen is not left waiting forever.
                if (isCurrentBinding(generation, sessionId)) {
                    _state.value = ChatUiState()
                }
                return@launch
            }
            try {
                repository.openChat(sessionId)
                // Polling only after a successful load, and only while the screen is
                // actually in front of the user.
                if (isCurrentBinding(generation, sessionId)) {
                    initialLoadSucceeded = true
                    if (isForeground) repository.startPolling()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // The repository has already published the failure into the snapshot;
                // keep it as a recoverable error instead of a spinner. A slow failure
                // from the previous binding must not touch the new screen.
                if (isCurrentBinding(generation, sessionId)) {
                    _state.value = _state.value.copy(
                        isLoadingInitial = false,
                        error = _state.value.error ?: e.message ?: "无法加载消息"
                    )
                }
            }
        }
    }

    /** True when [generation]/[sessionId] is still the binding the user is looking at. */
    private fun isCurrentBinding(generation: Long, sessionId: Long): Boolean =
        generation == bindingGeneration && _state.value.sessionId == sessionId

    private fun watchRepository() {
        if (watching?.isActive == true) return
        watching = viewModelScope.launch {
            repository.chat.collect { snapshot ->
                if (snapshot != null) applySnapshot(snapshot)
            }
        }
    }

    private fun applySnapshot(snapshot: ChatSnapshot) {
        // A snapshot published for another conversation (a late emission around a
        // switch) must not overwrite the one now on screen.
        if (snapshot.sessionId != _state.value.sessionId) return
        repositoryChatVersion = repository.chatVersion
        _state.value = _state.value.copy(
            sessionId = snapshot.sessionId,
            messages = snapshot.messages,
            participants = snapshot.participants,
            isLoadingInitial = snapshot.isLoadingInitial,
            isLoadingOlder = snapshot.isLoadingOlder,
            isSending = snapshot.isSending,
            reachedTop = snapshot.reachedTop,
            error = snapshot.error
        )
    }

    fun loadOlder() {
        if (_state.value.isLoadingOlder || _state.value.reachedTop) return
        val generation = bindingGeneration
        val sessionId = _state.value.sessionId
        viewModelScope.launch {
            try {
                repository.loadOlder()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // A paging failure belongs to the conversation it was asked for; it must
                // not be painted onto a screen the user switched to meanwhile.
                if (isCurrentBinding(generation, sessionId)) {
                    _state.value = _state.value.copy(error = e.message ?: "加载更早消息失败")
                }
            }
        }
    }

    fun setDraft(value: String) {
        _state.value = _state.value.copy(draft = value)
    }

    /**
     * Starts a reply to [message].
     *
     * Three entries on the screen (the long-press menu, a left swipe, a double tap on the
     * right side) all funnel here, so the target is built one way. Calling it again
     * simply replaces the target: there is no stack of quotes.
     */
    fun beginReply(message: ChatMessage) {
        if (message.id <= 0L) return
        replySelectionRevision++
        _state.value = _state.value.copy(
            replyTarget = ReplyTarget(
                messageId = message.id,
                authorName = senderDisplayName(message.senderId, _state.value.participants),
                body = message.plainText.trim().take(REPLY_PREVIEW_CHARS)
            )
        )
    }

    /** Drops the quote without touching the draft or the scroll position. */
    fun cancelReply() {
        if (_state.value.replyTarget == null) return
        replySelectionRevision++
        _state.value = _state.value.copy(replyTarget = null)
    }

    fun send() {
        val text = _state.value.draft
        if (text.isBlank() || _state.value.isSending) return
        val generation = bindingGeneration
        val sessionId = _state.value.sessionId
        val replyTo = _state.value.replyTarget?.messageId
        // Which quote this send consumes. The composer stays usable while the request is
        // in flight, so the user may have pointed it at another message by the time the
        // answer arrives; only *this* selection may be cleared on success.
        val replyRevision = replySelectionRevision
        _state.value = _state.value.copy(draft = "", isSending = true)
        viewModelScope.launch {
            val sent = try {
                repository.sendText(text, replyTo)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            // The tail of a send for A must not restore its draft, clear B's composer
            // busy flag or overwrite B's state after a switch.
            if (!isCurrentBinding(generation, sessionId)) return@launch
            if (!sent) {
                // Every early exit — blank text, signed out, no open chat, a refusal or a
                // transport failure — must restore the draft and drop the busy flag, or
                // the composer stays disabled forever. The quote stays too: the user can
                // retry the very same reply or cancel it.
                _state.value = _state.value.copy(
                    isSending = false,
                    draft = _state.value.draft.ifEmpty { text }
                )
            } else {
                // Only a confirmed send consumes the quote, and only the one it was sent
                // with. Clearing it in the failure branch would silently turn a retry into
                // an unquoted message; clearing a newer selection would drop the quote the
                // user picked for the next message.
                _state.value = _state.value.copy(
                    isSending = false,
                    replyTarget = if (replySelectionRevision == replyRevision) {
                        null
                    } else {
                        _state.value.replyTarget
                    }
                )
            }
        }
    }

    /** Manual refresh for the pull-to-refresh gesture and the toolbar action. */
    fun refresh() {
        val sessionId = _state.value.sessionId
        if (sessionId == 0L) return
        val generation = bindingGeneration
        // A first load that never succeeded means there is no history window to poll
        // from: a refresh must re-run the open sequence instead of a poll that can only
        // come back empty. Otherwise a failed open leaves the user stuck on an empty
        // screen with no way to retry from the UI.
        if (!initialLoadSucceeded) {
            viewModelScope.launch {
                try {
                    repository.openChat(sessionId)
                    if (isCurrentBinding(generation, sessionId)) {
                        initialLoadSucceeded = true
                        if (isForeground) repository.startPolling()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The repository published the failure into the snapshot already.
                    if (isCurrentBinding(generation, sessionId)) {
                        _state.value = _state.value.copy(
                            isLoadingInitial = false,
                            error = _state.value.error ?: e.message ?: "刷新失败"
                        )
                    }
                }
            }
            return
        }
        viewModelScope.launch {
            // The login this refresh belongs to. A 401 answered for the previous account
            // must not sign out the one that replaced it.
            val owner = repository.authOwner()
            try {
                repository.pollOnce()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // The poll loop signs out on a rejected token; a manual refresh must use
                // the same rule instead of only showing the error.
                if (isRejectedToken(e)) repository.invalidateCredentials(owner)
                if (isCurrentBinding(generation, sessionId)) {
                    _state.value = _state.value.copy(error = e.message ?: "刷新失败")
                }
            }
        }
    }

    fun deleteMessage(messageId: Long) {
        viewModelScope.launch {
            try {
                repository.deleteMessage(messageId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // The repository already published a readable error for this chat.
            }
        }
    }

    /**
     * A 401 means the token itself is gone, not that this action failed.
     *
     * A 403 is a permission answer about one conversation, so it stays a visible chat
     * error instead of clearing a login that is still valid.
     */
    private fun isRejectedToken(error: Throwable): Boolean {
        val kind = (error as? dev.oxype.client.data.oxype.OxypeException)?.kind ?: return false
        return kind == dev.oxype.client.data.oxype.OxypeErrorKind.UNAUTHORIZED
    }

    /**
     * Dismisses the error shown for the open conversation.
     *
     * The repository snapshot is the source the screen renders, so clearing only this
     * ViewModel's copy would be undone by the very next publish — a poll flag or a member
     * refresh would copy the closed message back. The dismissal therefore goes through
     * the repository, pinned to the conversation instance it was made for: a new
     * conversation (or a re-open of this one after a switch) keeps its own error.
     */
    fun consumeError() {
        val sessionId = _state.value.sessionId
        if (sessionId != 0L && repositoryChatVersion >= 0L) {
            repository.dismissChatError(sessionId, repositoryChatVersion)
        }
        _state.value = _state.value.copy(error = null)
    }

    /** The screen (or activity) became visible: resume the foreground poll loop. */
    fun onForeground() {
        isForeground = true
        // A failed first load has no history window to poll from: resuming must not
        // start a loop that can only return nothing. `refresh()` re-runs the open.
        if (_state.value.sessionId != 0L && initialLoadSucceeded &&
            !_state.value.isLoadingInitial
        ) {
            repository.startPolling()
        }
    }

    /** The screen (or activity) went to the background: stop network polling. */
    fun onBackground() {
        isForeground = false
        repository.stopPolling()
    }

    fun close() {
        isForeground = false
        opening?.cancel()
        opening = null
        bindingGeneration++
        initialLoadSucceeded = false
        repositoryChatVersion = -1L
        repository.closeChat()
        // Clearing the id makes a later open of the same conversation a real load
        // instead of the "already open" shortcut used for configuration changes. The
        // draft goes with it: text typed for a conversation must not come back when a
        // different one is opened.
        _state.value = _state.value.copy(
            sessionId = 0L,
            messages = emptyList(),
            participants = emptyMap(),
            isLoadingInitial = false,
            isLoadingOlder = false,
            isSending = false,
            reachedTop = true,
            draft = "",
            error = null,
            replyTarget = null
        )
    }

    override fun onCleared() {
        super.onCleared()
        repository.closeChat()
    }

    private companion object {
        /**
         * Characters of the quoted text shown in the composer strip.
         *
         * The strip is one line tall, so this is a preview, not the quote: the bubble
         * carries the whole thing. Clamping here keeps a long quote from doing work in
         * composition on every recomposition of the input row.
         */
        const val REPLY_PREVIEW_CHARS = 200
    }
}
