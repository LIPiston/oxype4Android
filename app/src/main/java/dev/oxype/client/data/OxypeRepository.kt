package dev.oxype.client.data

import dev.oxype.client.data.oxype.AuthCredentials
import dev.oxype.client.data.oxype.ChatMessage
import dev.oxype.client.data.oxype.CreatedSession
import dev.oxype.client.data.oxype.GroupInfo
import dev.oxype.client.data.oxype.LastMessageResult
import dev.oxype.client.data.oxype.OxypeApi
import dev.oxype.client.data.oxype.OxypeErrorKind
import dev.oxype.client.data.oxype.OxypeException
import dev.oxype.client.data.oxype.OxypeHttpClient
import dev.oxype.client.data.oxype.SessionDetail
import dev.oxype.client.data.oxype.SessionMember
import dev.oxype.client.data.oxype.SessionSummary
import dev.oxype.client.data.session.SessionStorage
import dev.oxype.client.data.sync.MessageSyncCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Chat state owned by the repository; the ViewModel only renders it. */
data class ChatSnapshot(
    val sessionId: Long,
    val messages: List<ChatMessage> = emptyList(),
    /**
     * Member directory used to label senders in a group conversation.
     *
     * Empty when the lookup has not run, failed, or the server answered nothing. The
     * transcript remains usable in that case: names fall back to `用户 N`.
     */
    val participants: Map<Long, SessionMember> = emptyMap(),
    val isLoadingInitial: Boolean = false,
    val isLoadingOlder: Boolean = false,
    val isSending: Boolean = false,
    val reachedTop: Boolean = true,
    val error: String? = null,
    val isPolling: Boolean = false
)

/**
 * Whether the stored login has been checked yet.
 *
 * It exists because `credentials == null` cannot tell "the check has not run" apart
 * from "the check ran and the user is signed out". A screen restored straight into the
 * chat after process death must wait for [OxypeRepository.restoreSession] instead of
 * reporting "请先登录" while the token is still being verified.
 */
enum class AuthRestoreState {
    /** No restore attempt has finished yet. */
    CHECKING,

    /** The stored token was accepted (or a fresh sign-in completed). */
    READY,

    /** There is no usable token; the UI belongs on the auth screen. */
    SIGNED_OUT
}

/**
 * The login that owned a piece of asynchronous work when it started.
 *
 * Every authenticated call captures one of these before its first suspension and hands
 * it back before invalidating credentials or writing state. The `generation` matters as
 * much as the credential pair: signing the same account in again issues a different
 * token, and a request started under the previous token must not act on the new login
 * either.
 */
class AuthOwner internal constructor(
    internal val generation: Long,
    val credentials: AuthCredentials
)


/**
 * The application's single source of truth for Oxype state.
 *
 * It is the only class that composes authentication, session loading and message
 * synchronisation, so screens never assemble requests themselves.
 */
class OxypeRepository(
    private val api: OxypeApi,
    private val http: OxypeHttpClient,
    private val sessionStore: SessionStorage,
    private val scope: CoroutineScope
) {
    private val _credentials = MutableStateFlow<AuthCredentials?>(null)
    val credentials: StateFlow<AuthCredentials?> = _credentials.asStateFlow()

    /**
     * Whether the stored login has been resolved yet.
     *
     * [credentials] alone cannot distinguish "not checked" from "signed out", which is
     * exactly what a screen restored into the chat after process death needs to know
     * before it calls [openChat].
     */
    private val _authState = MutableStateFlow(AuthRestoreState.CHECKING)
    val authState: StateFlow<AuthRestoreState> = _authState.asStateFlow()

    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    val sessions: StateFlow<List<SessionSummary>> = _sessions.asStateFlow()

    private val _chat = MutableStateFlow<ChatSnapshot?>(null)
    val chat: StateFlow<ChatSnapshot?> = _chat.asStateFlow()

    /** Set when the stored token turned out to be dead, so the UI can explain why. */
    private val _sessionExpired = MutableStateFlow(false)
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    private var coordinator: MessageSyncCoordinator? = null
    private var pollJob: Job? = null

    /**
     * True when the server answered 403 for the conversation on screen.
     *
     * On Oxype a 403 means the caller is authenticated but not a member of that session,
     * and the message routes may still soft-return an empty list for it. Continuing to
     * poll would therefore look like a silently empty conversation forever, so a denial
     * stops this conversation's polling until it is reopened (which may succeed after a
     * re-invite). It is per-conversation on purpose: the global login is untouched.
     */
    private var chatAccessDenied: Boolean = false

    /**
     * Bumped whenever a conversation is opened or dropped.
     *
     * Suspending work captures it before its first request and refuses to publish when
     * the value moved: a page, a send echo or a poll that was started for the previous
     * conversation must not overwrite the transcript of the one now on screen.
     */
    private var chatGeneration: Long = 0L

    /**
     * Bumped whenever the login changes: a sign-in, a sign-out, or a restore.
     *
     * Requests capture it (together with the credential pair) before their first
     * suspension and refuse to publish or invalidate once it moved. Comparing only the
     * user id would not be enough: signing the same account in again issues a different
     * token, and an old request belonging to the previous token must not act on the new
     * login either.
     */
    private var authGeneration: Long = 0L

    val isSignedIn: Boolean
        get() = _credentials.value != null

    /** True while a foreground poll loop is running; the UI and tests can observe it. */
    val isPolling: Boolean
        get() = pollJob?.isActive == true

    fun currentUserId(): Long? = _credentials.value?.userId

    fun storedUsername(): String? = sessionStore.username()

    fun baseUrl(): String = http.baseUrl

    /**
     * The login that is current right now, for work that is about to suspend.
     *
     * Callers hand it back to [invalidateCredentials] or to [isCurrentAuth] before
     * writing state, so a slow answer from a previous login cannot sign out (or paint
     * over) the account that replaced it.
     */
    fun authOwner(): AuthOwner? = _credentials.value?.let { AuthOwner(authGeneration, it) }

    /** True while [owner] still describes the login in effect. */
    fun isCurrentAuth(owner: AuthOwner?): Boolean =
        owner != null && owner.generation == authGeneration && _credentials.value == owner.credentials

    /**
     * Dismisses the error on the open conversation.
     *
     * The snapshot is the authority the screen renders, so clearing it only in the
     * ViewModel would let the next publish (a poll flag, a participant refresh) copy the
     * old message back and resurrect an error the user already closed. [version] pins
     * the call to the conversation instance it was made for, so a late dismissal can
     * never wipe a *newly opened* chat's error — including a re-open of the same id.
     */
    fun dismissChatError(sessionId: Long, version: Long = chatGeneration): Boolean {
        val current = _chat.value ?: return false
        if (current.sessionId != sessionId || version != chatGeneration) return false
        if (current.error == null) return false
        _chat.value = current.copy(error = null)
        return true
    }

    /**
     * The current conversation instance.
     *
     * Bumped on every open and close; a screen captures it so it can tell "my chat" from
     * "the same id, opened again after a switch".
     */
    val chatVersion: Long
        get() = chatGeneration

    /**
     * Restores a stored login.
     *
     * `isAlive` is asked explicitly instead of assuming the token still works. A clear
     * "no" signs the user out and reports it; a network or server failure leaves the
     * token in place, because an unreachable server says nothing about its validity.
     *
     * Credentials are published only once that check is over, so observers (the home
     * list in particular) never start requesting with a token that is still being
     * verified or has just been rejected.
     */
    suspend fun restoreSession(): Boolean {
        // A rotation re-runs the startup effect on the same repository. The answer is
        // already known, so the token must not be re-verified (that would flash the
        // restore placeholder and re-issue a request for no reason).
        if (_authState.value == AuthRestoreState.READY && _credentials.value != null) {
            return true
        }
        val stored = sessionStore.credentials() ?: run {
            _authState.value = AuthRestoreState.SIGNED_OUT
            return false
        }
        http.updateBaseUrl(sessionStore.baseUrl)
        // Snapshot the login version: a sign-in completed while the token is being
        // verified must win, so this restore may not overwrite it afterwards.
        val generation = authGeneration
        // `null` means the answer said nothing about the token (network, 5xx).
        val alive: Boolean? = try {
            api.isAlive(stored)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // A cancelled check says nothing about the token. Publishing it as dead
            // would sign the user out over a screen that was simply left.
            throw e
        } catch (e: OxypeException) {
            // Only 401 means the stored token is no longer usable. A 403 says the
            // caller is authenticated but not allowed to do this, which is a
            // permission answer about the request, not about the login.
            if (e.kind == OxypeErrorKind.UNAUTHORIZED) {
                false
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
        // A login that happened meanwhile owns the state now; the answer about the old
        // stored token says nothing about it.
        if (generation != authGeneration) return _credentials.value != null
        if (alive == false) {
            signOutLocally(expired = true)
            return false
        }
        // Alive, or unverifiable: the stored login stands. `sessionExpired` is cleared
        // first so an observer that synchronously discovers a rejection is not undone.
        _sessionExpired.value = false
        authGeneration++
        _credentials.value = stored
        // Publishing the credentials runs observers synchronously, and one of them may
        // discover that the token is rejected and sign out. In that case the state must
        // stay SIGNED_OUT rather than claiming a login that no longer exists.
        _authState.value = if (_credentials.value == stored) {
            AuthRestoreState.READY
        } else {
            AuthRestoreState.SIGNED_OUT
        }
        return true
    }

    suspend fun register(username: String, password: String): AuthCredentials {
        val credentials = api.register(username, password)
        finishSignIn(credentials, username)
        return credentials
    }

    suspend fun login(userId: Long, password: String): AuthCredentials {
        val credentials = api.login(userId, password)
        finishSignIn(credentials, sessionStore.username())
        return credentials
    }

    private fun finishSignIn(credentials: AuthCredentials, username: String?) {
        sessionStore.saveAuth(credentials, username)
        _sessionExpired.value = false
        // A fresh login invalidates everything a previous login started, even when it is
        // the same account: the token differs, so an old 401 is not about this session.
        authGeneration++
        _credentials.value = credentials
        // Same guard as [restoreSession]: an observer may sign out during the publish.
        _authState.value = if (_credentials.value == credentials) {
            AuthRestoreState.READY
        } else {
            AuthRestoreState.SIGNED_OUT
        }
    }

    /** Best-effort server logout: a dead connection must still clear the local token. */
    suspend fun logout() {
        stopPolling()
        val current = _credentials.value
        if (current != null) {
            try {
                api.logout(current)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best effort only: the local token is cleared below regardless.
            }
        }
        _sessions.value = emptyList()
        clearChat()
        signOutLocally(expired = false)
    }

    private fun signOutLocally(expired: Boolean) {
        sessionStore.clearAuth()
        // Everything in flight belonged to the login that is going away; bumping the
        // generation is what makes its late answers unable to talk to the next one.
        authGeneration++
        _credentials.value = null
        _sessions.value = emptyList()
        clearChat()
        _sessionExpired.value = expired
        _authState.value = AuthRestoreState.SIGNED_OUT
    }

    /**
     * Invalidates the stored login after the server rejected the token with a 401.
     *
     * Used by screens that receive a 401 from a route other than the poll loop:
     * routing every rejection through one method keeps logout, expiry and revocation on
     * the same credential signal, so navigation returns to auth and the chat state is
     * dropped in one place. A 403 must never come through here: on Oxype it means the
     * caller is authenticated but lacks permission, which is a per-action error.
     *
     * [owner] is the login the rejected request was made with. When it no longer matches
     * the current one the 401 belongs to an account that has already been replaced, so
     * it must not sign the account now on screen out. Passing `null` means "this is a
     * deliberate local sign-out" (a manual expiry acknowledgement) and always applies.
     */
    fun invalidateCredentials(owner: AuthOwner? = null) {
        if (owner != null && !isCurrentAuth(owner)) return
        stopPolling()
        signOutLocally(expired = true)
    }

    fun acknowledgeSessionExpiry() {
        _sessionExpired.value = false
    }

    fun updateBaseUrl(url: String) {
        http.updateBaseUrl(url)
        sessionStore.baseUrl = http.baseUrl
    }

    /**
     * Reloads the joined-session list and resolves each session's display metadata.
     *
     * The list is only published while the login that asked for it is still current.
     * A refresh started for account A can answer long after B signed in; writing it then
     * would replace B's list with A's conversations.
     */
    suspend fun refreshSessions() {
        val owner = authOwner() ?: throw unauthorized()
        val ids = api.joinedSessionIds(owner.credentials)
        // Resolving details is another suspension: re-check before publishing.
        if (!isCurrentAuth(owner)) return
        val resolved = api.sessionDetails(owner.credentials, ids)
        if (!isCurrentAuth(owner)) return
        _sessions.value = resolved
    }

    suspend fun createSession(name: String, description: String): CreatedSession {
        val owner = authOwner() ?: throw unauthorized()
        val created = api.createSession(owner.credentials, name, description)
        // A creation made as A must not trigger a list refresh under B.
        if (isCurrentAuth(owner)) refreshSessions()
        return created
    }

    suspend fun joinSession(uuid: String): SessionDetail {
        val owner = authOwner() ?: throw unauthorized()
        val detail = api.joinSessionByUuid(owner.credentials, uuid)
        if (isCurrentAuth(owner)) refreshSessions()
        return detail
    }

    /**
     * Loads the group info page for one conversation.
     *
     * Three failures are deliberately different:
     * - no login → [unauthorized], exactly like every other authenticated call;
     * - 401 → the token is gone, so the credentials are invalidated and the error is
     *   rethrown for the caller to surface;
     * - 403 → the account is authenticated but not allowed to read this session, so it is
     *   reported as-is and the global login stays. A page about one conversation must
     *   never sign the user out.
     *
     * The member directory is *supplementary*: a 403 or a network failure there leaves the
     * ids the detail route gave, labelled `用户 N`. The detail route is the one failure
     * that makes the page as a whole fail, because it is what supplies the name and the
     * roster in the first place.
     *
     * `isOwner`/`isAdmin` are decided here, once, from `ownerId`/`adminIds`; the server's
     * own booleans are only used when those ids are absent. That keeps the header badge
     * and the per-member labels consistent.
     */
    suspend fun loadGroupInfo(sessionId: Long): GroupInfo {
        val owner = authOwner() ?: throw unauthorized()
        val detail = try {
            api.sessionInfo(owner.credentials, sessionId)
        } catch (e: OxypeException) {
            if (e.kind == OxypeErrorKind.UNAUTHORIZED) {
                invalidateCredentials(owner)
            }
            throw e
        }
        if (!isCurrentAuth(owner)) throw unauthorized()

        // The directory may be denied or unreachable; the page still has ids to show.
        val directory = try {
            api.sessionMembers(owner.credentials, sessionId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: OxypeException) {
            if (e.kind == OxypeErrorKind.UNAUTHORIZED) {
                invalidateCredentials(owner)
                throw e
            }
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        if (!isCurrentAuth(owner)) throw unauthorized()

        val byId = directory.associateBy { it.userId }
        val members = detail.members.map { member ->
            val fromDirectory = byId[member.userId]
            val hasRealName = member.username != "用户 ${member.userId}"
            when {
                // The detail route usually has the name; the directory only fills a
                // numeric placeholder, never overwrites a real label.
                hasRealName -> member.copy(
                    avatarUrl = member.avatarUrl ?: fromDirectory?.avatarUrl
                )

                fromDirectory != null -> SessionMember(
                    userId = member.userId,
                    username = fromDirectory.username,
                    avatarUrl = member.avatarUrl ?: fromDirectory.avatarUrl
                )

                else -> member
            }
        }

        val currentUserId = owner.credentials.userId
        val isOwner = if (detail.ownerId > 0L) detail.ownerId == currentUserId else detail.isOwner
        val isAdmin = if (detail.ownerId > 0L || detail.adminIds.isNotEmpty()) {
            isOwner || detail.adminIds.contains(currentUserId)
        } else {
            detail.isAdmin
        }

        return GroupInfo(
            sessionId = detail.sessionId,
            name = detail.name,
            description = detail.description,
            ownerId = detail.ownerId,
            adminIds = detail.adminIds,
            members = members,
            avatarUrl = detail.avatarUrl,
            isOwner = isOwner,
            isAdmin = isAdmin
        )
    }

    /**
     * Opens a conversation: resets local state, anchors on the newest message when the
     * server can say where it is, and loads one page of history.
     *
     * The loading flag is cleared on every exit path — success, failure and
     * cancellation — so a failed or abandoned open can never leave the transcript
     * spinning. A failure also keeps a readable error in the snapshot, which the screen
     * renders as a recoverable strip rather than as a permanent spinner. A 403 leaves
     * the conversation marked as denied so [startPolling] will not revive a poller that
     * can only repeat the refusal.
     */
    suspend fun openChat(sessionId: Long) {
        stopPolling()
        val owner = authOwner() ?: run {
            _chat.value = ChatSnapshot(
                sessionId = sessionId,
                reachedTop = true,
                error = "请先登录"
            )
            throw unauthorized()
        }
        val generation = ++chatGeneration
        val sync = MessageSyncCoordinator()
        coordinator = sync
        chatAccessDenied = false
        // Replacing the snapshot drops the previous conversation's messages before any
        // request is made, so a switch never shows the old transcript under the new id.
        _chat.value = ChatSnapshot(
            sessionId = sessionId,
            isLoadingInitial = true,
            reachedTop = false
        )

        var failure: Exception? = null
        try {
            // The read marker is only a *read* position: it says how far this account has
            // read, never how far the conversation goes. A reader with unread messages
            // (or a member added to a long history) has a marker far behind the newest
            // sequence, so anchoring the first screen on it would hide everything above
            // and — worse — could be mistaken for "the conversation ends here".
            showInitialHistory(owner, sessionId, sync)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cancellation (leaving the screen) still has to drop the spinner before the
            // scope finishes; the error is intentionally not reported for it.
            failure = null
            throw e
        } catch (e: Exception) {
            failure = e
            // A token rejected while opening is a sign-out, not a recoverable chat error:
            // the credential signal returns the app to auth and drops this state. A 403
            // is a permission answer about this conversation, so it stays a chat error.
            // The invalidation is scoped to the login that made the request: an answer
            // for account A must not sign account B out.
            if ((e as? OxypeException)?.kind == OxypeErrorKind.UNAUTHORIZED) {
                invalidateCredentials(owner)
            }
            if ((e as? OxypeException)?.kind == OxypeErrorKind.FORBIDDEN) {
                chatAccessDenied = true
            }
        } finally {
            // Only the conversation that is still current may publish its result; a
            // slower earlier open must not replace the one the user switched to.
            if (isCurrentChat(generation, sessionId)) {
                _chat.value = ChatSnapshot(
                    sessionId = sessionId,
                    messages = sync.messages,
                    isLoadingInitial = false,
                    // `reachedTop` is only what the coordinator actually proved: a failed
                    // or empty-but-not-at-zero window must stay paging-retryable instead
                    // of telling the user this is the earliest message.
                    reachedTop = sync.reachedTop,
                    error = failure?.let(::readableMessage),
                    isPolling = false
                )
            }
        }
        // Names and avatars are supplementary: the transcript is already on screen, so a
        // slow member lookup must not delay the caller (nor the poller it starts). A
        // denied conversation is not asked at all — it can only answer 403 again.
        if (failure == null && !chatAccessDenied) {
            scope.launch { loadParticipants(owner, sessionId, generation) }
        }
        if (failure != null) throw failure
    }

    /**
     * Loads the member directory for the open conversation and republishes it.
     *
     * Failure is deliberately silent: a chat that lost its names is still readable, and
     * surfacing "无法读取成员" over a healthy transcript would be a misleading error. The
     * one exception is a 401: that is the credential signal, so the app signs out
     * through the same path every other rejected request uses.
     */
    private suspend fun loadParticipants(
        owner: AuthOwner,
        sessionId: Long,
        generation: Long
    ) {
        val members = try {
            api.sessionMembers(owner.credentials, sessionId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: OxypeException) {
            if (isRejectedToken(e)) invalidateCredentials(owner)
            return
        } catch (e: Exception) {
            return
        }
        if (members.isEmpty()) return
        if (!isCurrentAuth(owner)) return
        if (!isCurrentChat(generation, sessionId)) return
        publish(participants = members.associateBy { it.userId })
    }

    /**
     * Loads the first page of a conversation.
     *
     * `GET /lastMessage` is the only route that reports where the conversation *ends*, so
     * it is the only anchor the first window may use. When it is unavailable the client
     * cannot tell where the newest message is, and the read marker must not be used as a
     * substitute: it is a read position, not a sequence bound. Falling back to it would
     * request `marker - page .. marker`, show an outdated tail as "the conversation", and
     * leave everything above the marker unreachable. The fallback is therefore the wide
     * speculative window from the first sequence, which can only skip history in the
     * sense of *showing less*, never in the sense of refusing to ever request it: the
     * watermark stays at the fetched messages, so the next poll asks for the gap.
     *
     * A 403 from the probe is a session-level denial, not an unreadable payload, and is
     * rethrown so the chat reports it instead of opening a speculative empty transcript.
     */
    private suspend fun showInitialHistory(
        owner: AuthOwner,
        sessionId: Long,
        sync: MessageSyncCoordinator
    ) {
        val newest = try {
            MessageSyncCoordinator.newestFromLastMessage(
                api.lastMessage(owner.credentials, sessionId)
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: OxypeException) {
            // Forbidden means "not a member of this session"; propagating it is what
            // turns a silent, permanently empty transcript into a visible error.
            if (e.kind == OxypeErrorKind.FORBIDDEN || e.kind == OxypeErrorKind.UNAUTHORIZED) throw e
            null
        } catch (e: Exception) {
            null
        }

        // No probe reported where the conversation ends. Asking for `0..0` is not a
        // smaller version of the real request: no message can live there, so the answer
        // is always empty and would be misread as an empty conversation. A wide window
        // from the start of the sequence space is a range the server can actually
        // answer, and it is explicitly speculative — it may not hold the newest page.
        val window: ClosedRange<Long> = if (newest != null) {
            val start = (newest - MessageSyncCoordinator.DEFAULT_HISTORY_PAGE + 1L)
                .coerceAtLeast(0L)
            start..newest
        } else {
            sync.speculativeHistoryWindow()
        }

        val page = api.messages(owner.credentials, sessionId, window.start, window.endInclusive)
        sync.applyHistory(
            requestedStart = window.start,
            requestedEnd = window.endInclusive,
            page = page,
            speculative = newest == null
        )
    }

    /** Fetches the next older page. A no-op once the top of the history is reached. */
    suspend fun loadOlder() {
        val owner = authOwner() ?: throw unauthorized()
        val sync = coordinator ?: return
        val sessionId = _chat.value?.sessionId ?: return
        val generation = chatGeneration
        val window = sync.nextHistoryWindow()
        if (window == null) {
            // A missing window means either a real top or no history loaded yet (a
            // failed open). Only the former may claim "已到最早的消息", or the user is
            // told the history is finished when the first page never arrived.
            if (isCurrentChat(generation, sessionId, sync)) {
                publish(
                    isLoadingOlder = false,
                    reachedTop = sync.reachedTop,
                    // Nothing to page from and not at the top: that is a failed initial
                    // load the user can retry, not a quiet no-op.
                    error = if (!sync.reachedTop && sync.oldestRequestedSeq ==
                        MessageSyncCoordinator.NO_SEQUENCE
                    ) {
                        ErrorUpdate.Set(EMPTY_HISTORY_ERROR)
                    } else {
                        null
                    }
                )
            }
            return
        }
        publish(isLoadingOlder = true, error = ErrorUpdate.Clear)
        try {
            val page = api.messages(owner.credentials, sessionId, window.start, window.endInclusive)
            // The user may have switched conversations while this page was in flight.
            if (!isCurrentChat(generation, sessionId, sync)) return
            sync.applyHistory(window.start, window.endInclusive, page)
            publish(messages = sync.messages, isLoadingOlder = false, reachedTop = sync.reachedTop)
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (isCurrentChat(generation, sessionId, sync)) publish(isLoadingOlder = false)
            throw e
        } catch (e: Exception) {
            if (isRejectedToken(e)) invalidateCredentials(owner)
            if (isCurrentChat(generation, sessionId, sync)) {
                publish(isLoadingOlder = false, error = ErrorUpdate.Set(readableMessage(e)))
            }
        }
    }

    /**
     * Sends text and, on success, pulls the server's stored copy so the bubble shows the
     * authoritative id and timestamp. The optimistic echo exists only for the duration
     * of the request and is replaced, so it can never be duplicated.
     *
     * [replyTo] is the quoted message's sequence: the server resolves and freezes the
     * quoted snapshot itself, so nothing else about the target is sent. A target that no
     * longer exists answers 404, which is reported as this conversation's error and does
     * not touch the login or the watermark.
     */
    suspend fun sendText(text: String, replyTo: Long? = null): Boolean {
        val owner = authOwner() ?: throw unauthorized()
        val snapshot = _chat.value ?: return false
        val sync = coordinator ?: return false
        val sessionId = snapshot.sessionId
        val generation = chatGeneration
        if (text.isBlank()) return false

        publish(isSending = true, error = ErrorUpdate.Clear)
        try {
            val seq = api.sendText(owner.credentials, sessionId, text, replyTo)
            if (seq == null) {
                // `{ seq: -1 }`: the server declined, most often because this account is
                // no longer a member. Surface it instead of pretending it was sent.
                if (isCurrentChat(generation, sessionId, sync)) {
                    publish(isSending = false, error = ErrorUpdate.Set("消息未发送：你可能已不在该会话中"))
                }
                return false
            }
            val refreshed = refreshTail(owner, sessionId, sync, around = seq)
            // The conversation may have been switched (or closed) while the send was in
            // flight; merging and publishing then would replace the new transcript. The
            // same holds for a login change: the answer is no longer this account's.
            if (!isCurrentAuth(owner)) return true
            if (!isCurrentChat(generation, sessionId, sync)) return true
            if (refreshed) {
                // The re-read succeeded and the message is in the store (either merged
                // now or already known), so the watermark may move past it — but only
                // when it connects to what is already known. A busy conversation can
                // assign the sent message a sequence far above the local watermark; in
                // that case the middle is still unfetched and the next poll must ask for
                // it instead of starting above it.
                if (sync.isContiguousWithWatermark(seq)) sync.acknowledgeSend(seq)
            }
            // On a failed re-read the watermark is deliberately left untouched: the next
            // poll must still ask for a range containing `seq`, or the message would be
            // skipped permanently.
            publish(messages = sync.messages, isSending = false)
            return true
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (isCurrentChat(generation, sessionId, sync)) publish(isSending = false)
            throw e
        } catch (e: Exception) {
            if (isRejectedToken(e)) invalidateCredentials(owner)
            // A failure for conversation A must not be painted onto B's transcript.
            if (isCurrentChat(generation, sessionId, sync)) {
                publish(isSending = false, error = ErrorUpdate.Set(readableMessage(e)))
            }
            return false
        }
    }

    /** Deletes a message; the row is removed only after the server confirms. */
    suspend fun deleteMessage(messageId: Long): Boolean {
        val owner = authOwner() ?: throw unauthorized()
        val snapshot = _chat.value ?: return false
        val sessionId = snapshot.sessionId
        val generation = chatGeneration
        return try {
            val deleted = api.deleteMessage(owner.credentials, sessionId, messageId)
            if (!isCurrentChat(generation, sessionId)) return deleted
            if (deleted) {
                // The tombstone outlives the row: a history page, poll or tail re-read
                // that was already in flight can still carry this id, and it must not
                // bring the deleted bubble back.
                coordinator?.remove(messageId)
                publish(messages = coordinator?.messages.orEmpty())
            }
            deleted
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isRejectedToken(e)) invalidateCredentials(owner)
            if (isCurrentChat(generation, sessionId)) {
                publish(error = ErrorUpdate.Set(readableMessage(e)))
            }
            false
        }
    }

    /**
     * One poll cycle. Returns true when new messages were merged, so the caller can
     * decide whether a follow-up cycle is worthwhile.
     */
    suspend fun pollOnce(): Boolean {
        val owner = authOwner() ?: return false
        val snapshot = _chat.value ?: return false
        val sync = coordinator ?: return false
        val sessionId = snapshot.sessionId
        val generation = chatGeneration

        // Three outcomes, and they must not be conflated:
        //  - probe answered with a sequence  -> fetch exactly what is missing,
        //  - probe answered "no messages"    -> the chat is empty, nothing to fetch,
        //  - probe failed or was unreadable  -> fall back to a bounded speculative range.
        // The last case matters: an unknown payload must not be mistaken for an empty
        // chat, or a protocol change would silently stop polling forever.
        //
        // A 403 is none of the three: Oxype answers it when this account is not a member,
        // and the other message routes may still soft-return an empty list. Degrading it
        // into a speculative window would leave an empty transcript quietly polling a
        // conversation the account cannot read, so the denial is propagated instead and
        // the poller stops (see [startPolling] and [chatAccessDenied]).
        val probe: LastMessageResult = try {
            api.lastMessage(owner.credentials, sessionId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: OxypeException) {
            if (isRejectedToken(e) || isSessionAccessDenied(e)) {
                if (isSessionAccessDenied(e)) {
                    chatAccessDenied = true
                    if (isCurrentChat(generation, sessionId, sync)) {
                        publish(error = ErrorUpdate.Set(readableMessage(e)))
                    }
                }
                if (isRejectedToken(e)) invalidateCredentials(owner)
                throw e
            }
            LastMessageResult.Unknown
        }
        // The token may have been replaced (or signed out) while the probe was in flight;
        // the answer belongs to the previous login and must not be merged into a
        // transcript that now belongs to somebody else.
        if (!isCurrentAuth(owner)) return false
        if (!isCurrentChat(generation, sessionId, sync)) return false

        if (probe is LastMessageResult.Empty) {
            // A successful answer with no message is authoritative: skip the request.
            return false
        }

        val newest = MessageSyncCoordinator.newestFromLastMessage(probe)
        val window = if (newest != null) {
            sync.planCatchUp(newest)
        } else {
            sync.speculativePollWindow()
        } ?: return false

        // An unreadable page is not an empty range: the messages in the window are
        // unknown, so the failure must reach the caller and the watermark must stay put
        // (otherwise the next poll would start past the messages never returned).
        val page = try {
            api.messages(owner.credentials, sessionId, window.start, window.endInclusive)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if ((e as? OxypeException)?.let(::isSessionAccessDenied) == true) {
                chatAccessDenied = true
            }
            if (isCurrentChat(generation, sessionId, sync)) {
                publish(error = ErrorUpdate.Set(readableMessage(e)))
            }
            throw e
        }
        if (!isCurrentAuth(owner)) return false
        // A poll for a conversation the user has left must not merge into the new one.
        if (!isCurrentChat(generation, sessionId, sync)) return false
        if (newest != null) {
            // The probe bounded the window at the server's newest sequence, so the whole
            // request was a real gap-free range and its end may move the watermark. This
            // must be the *fetched* end, not `newest`: a backlog larger than one poll
            // window would otherwise leave the middle unfetched and skipped forever.
            sync.acknowledgeFetchedWindow(window.start, window.endInclusive)
        }
        // A speculative window has no server bound and may extend past the newest
        // existing sequence; there the watermark may only follow the messages actually
        // returned, or the next poll would start past messages not yet created.
        val added = sync.mergeIncoming(page)
        if (added > 0) {
            publish(messages = sync.messages, reachedTop = sync.reachedTop, error = ErrorUpdate.Clear)
        }
        return added > 0
    }

    /**
     * Starts foreground polling for the open chat. Only one loop may exist at a time;
     * leaving the screen cancels it, so nothing keeps talking to the server in the
     * background.
     */
    fun startPolling(intervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS) {
        if (pollJob?.isActive == true) return
        if (_chat.value == null || _credentials.value == null) return
        // A conversation the server already refused cannot make progress by polling; it
        // would only repeat the 403 behind an unchanged, empty screen. Reopening the chat
        // is what clears the flag and gives a re-invited account another chance.
        if (chatAccessDenied) return
        publish(isPolling = true)
        pollJob = scope.launch {
            try {
                while (isActive) {
                    delay(intervalMillis)
                    // The login this cycle belongs to. A rejection is only a sign-out for
                    // the account that made the request, never for one that replaced it.
                    val owner = authOwner() ?: break
                    try {
                        pollOnce()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // stopPolling cancels the loop when the screen goes away. That is
                        // a normal lifecycle event, not a failed request, so it must be
                        // rethrown instead of being reported as an unknown error.
                        throw e
                    } catch (e: OxypeException) {
                        if (e.kind == OxypeErrorKind.UNAUTHORIZED) {
                            invalidateCredentials(owner)
                            break
                        }
                        if (isSessionAccessDenied(e)) {
                            // Not a member: the poller has nothing to poll. The error is
                            // already on screen, so the loop ends instead of retrying.
                            chatAccessDenied = true
                            publish(error = ErrorUpdate.Set(readableMessage(e)))
                            break
                        }
                        publish(error = ErrorUpdate.Set(readableMessage(e)))
                    } catch (e: Exception) {
                        publish(error = ErrorUpdate.Set(readableMessage(e)))
                    }
                }
            } finally {
                // Whatever ended the loop — cancellation, sign-out, or a crash — the
                // snapshot must stop advertising a poller that no longer exists.
                publish(isPolling = false)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        _chat.value?.let { if (it.isPolling) publish(isPolling = false) }
    }

    fun closeChat() {
        stopPolling()
        clearChat()
    }

    private fun clearChat() {
        chatGeneration++
        coordinator = null
        chatAccessDenied = false
        _chat.value = null
    }

    /**
     * True when the work that started for [generation]/[sessionId] is still relevant.
     *
     * A request can finish long after the user switched conversations, signed out, or
     * opened the same id again; publishing its result then would overwrite a snapshot
     * (or a merged message) that no longer belongs to it.
     */
    private fun isCurrentChat(
        generation: Long,
        sessionId: Long,
        sync: MessageSyncCoordinator? = null
    ): Boolean {
        if (generation != chatGeneration) return false
        if (_chat.value?.sessionId != sessionId) return false
        return sync == null || coordinator === sync
    }

    /**
     * A 401 means the token itself is no longer usable.
     *
     * 403 is deliberately excluded: Oxype answers it when the caller is authenticated
     * but not allowed to touch the resource, so it is a session-level error and must
     * not clear the global login.
     */
    private fun isRejectedToken(error: Throwable): Boolean {
        val kind = (error as? OxypeException)?.kind ?: return false
        return kind == OxypeErrorKind.UNAUTHORIZED
    }

    /**
     * A 403 on a message route means this account is not a member of the conversation.
     *
     * On Oxype that is a per-session denial, not a token problem: `lastMessage` refuses
     * with 403 while `getMessages`/`receivedMessageSeq` may soft-return an empty answer.
     * It is therefore kept separate from [isRejectedToken] and it stops the conversation's
     * polling instead of clearing the login.
     */
    private fun isSessionAccessDenied(error: Throwable): Boolean {
        val kind = (error as? OxypeException)?.kind ?: return false
        return kind == OxypeErrorKind.FORBIDDEN
    }

    /**
     * Re-reads a small window around a freshly sent sequence and merges it. Asking for
     * the neighbourhood rather than only `seq` also picks up anything that arrived in
     * between; the coordinator dedups whatever was already known.
     *
     * The watermark may only move when [around] itself came back: a successful request
     * whose page omits the sent sequence (the server has not exposed it yet, or it was
     * deleted again) proves nothing about that sequence, and acknowledging the whole
     * window would move the watermark above it so the next poll skips it forever.
     *
     * Returns true when the sent message is now in the store (either just merged or
     * already known). The caller must not advance its newest watermark on a false
     * answer: the message is then still missing locally, and the next poll has to
     * request a range that includes [around].
     */
    private suspend fun refreshTail(
        owner: AuthOwner,
        sessionId: Long,
        sync: MessageSyncCoordinator,
        around: Long
    ): Boolean {
        val start = (around - TAIL_LOOKBACK + 1L).coerceAtLeast(1L)
        val page = try {
            api.messages(owner.credentials, sessionId, start, around)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // The send itself succeeded; failing to re-read the tail only costs the
            // authoritative timestamp until the next poll.
            return false
        }
        sync.mergeWindow(start, around, page, requirePresent = around)
        return sync.contains(around)
    }

    /**
     * Copies the current chat snapshot with the named changes.
     *
     * `error` is tri-state on purpose: [SET_ERROR] replaces the message, [CLEAR_ERROR]
     * removes it, and `null` leaves whatever is on screen untouched so the other
     * arguments can be changed without disturbing an error the user has not seen.
     */
    private fun publish(
        messages: List<ChatMessage>? = null,
        participants: Map<Long, SessionMember>? = null,
        isLoadingInitial: Boolean? = null,
        isLoadingOlder: Boolean? = null,
        isSending: Boolean? = null,
        reachedTop: Boolean? = null,
        error: ErrorUpdate? = null,
        isPolling: Boolean? = null
    ) {
        val current = _chat.value ?: return
        _chat.value = current.copy(
            messages = messages ?: current.messages,
            participants = participants ?: current.participants,
            isLoadingInitial = isLoadingInitial ?: current.isLoadingInitial,
            isLoadingOlder = isLoadingOlder ?: current.isLoadingOlder,
            isSending = isSending ?: current.isSending,
            reachedTop = reachedTop ?: current.reachedTop,
            error = when (error) {
                is ErrorUpdate.Set -> error.message
                ErrorUpdate.Clear -> null
                null -> current.error
            },
            isPolling = isPolling ?: current.isPolling
        )
    }

    private sealed interface ErrorUpdate {
        data class Set(val message: String) : ErrorUpdate
        data object Clear : ErrorUpdate
    }

    private fun readableMessage(error: Throwable): String =
        (error as? OxypeException)?.message
            ?: error.message
            ?: "发生了未知错误"

    private fun unauthorized() = OxypeException(OxypeErrorKind.UNAUTHORIZED, "请先登录")

    companion object {
        const val DEFAULT_POLL_INTERVAL_MILLIS: Long = 3_000L
        private const val TAIL_LOOKBACK: Long = 5L

        /** Shown when paging is attempted before any page loaded (the first load failed). */
        const val EMPTY_HISTORY_ERROR: String = "消息尚未加载，请重新打开或稍后重试"
    }
}
