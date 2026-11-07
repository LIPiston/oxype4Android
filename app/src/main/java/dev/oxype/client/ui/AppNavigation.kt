package dev.oxype.client.ui

/**
 * Which destination is showing.
 *
 * The three top-level tabs — [Chats], [Settings] and [Profile] — are what the bottom bar
 * switches between; [Chat] and [GroupInfo] are detail screens pushed above the chats
 * section, and [Auth] is the signed-out state. Chat carries its own session id.
 */
internal sealed interface Destination {
    data object Auth : Destination

    /** The conversation list, and the section a detail screen belongs to. */
    data object Chats : Destination
    data object Settings : Destination
    data object Profile : Destination
    data class Chat(val sessionId: Long, val title: String, val subtitle: String) : Destination

    /**
     * The full-page group info screen.
     *
     * [title] and [subtitle] are the header placeholder taken from the chat screen, not
     * the answer: the page draws them while the detail request is in flight (and if it
     * fails), so opening it never flashes an empty header. They are saved with the
     * destination for the same reason — a rebuilt process would otherwise start blank.
     */
    data class GroupInfo(
        val sessionId: Long,
        val title: String,
        val subtitle: String
    ) : Destination
}

/**
 * The tabs the bottom bar can show, in display order.
 *
 * Kept separate from [Destination] because the bar is about sections, not about every
 * screen the app can show: opening a conversation leaves the section on `CHATS` while
 * the bar itself is hidden.
 */
internal enum class BottomTab(val label: String) {
    CHATS("聊天"),
    SETTINGS("设置"),
    PROFILE("个人资料")
}

/** The tab index of a top-level destination, or `-1` for a detail/standalone screen. */
internal fun tabIndexOf(destination: Destination): Int = when (destination) {
    Destination.Chats -> 0
    Destination.Settings -> 1
    Destination.Profile -> 2
    else -> -1
}

/** True for the three destinations the bottom bar switches between. */
internal fun Destination.isTopLevel(): Boolean = tabIndexOf(this) >= 0

/**
 * The top-level section [destination] is drawn under.
 *
 * A conversation and its group info page belong to the chats section, so the bar keeps
 * "聊天" highlighted on the way in and out of a chat even though it is hidden there.
 * Everything else is its own section.
 */
internal fun Destination.sectionFor(): Destination = when (this) {
    is Destination.Chat, is Destination.GroupInfo -> Destination.Chats
    else -> this
}

/** The [BottomTab] for a top-level destination; `null` for detail/standalone screens. */
internal fun bottomTabFor(destination: Destination): BottomTab? = when (destination) {
    Destination.Chats -> BottomTab.CHATS
    Destination.Settings -> BottomTab.SETTINGS
    Destination.Profile -> BottomTab.PROFILE
    else -> null
}

/**
 * The navigation state machine driven by credential changes.
 *
 * Kept as a plain value so the login/logout/restore rules can be tested without
 * Compose, and so both a fresh sign-in and a revoked token use one signal instead of
 * two ad-hoc `destination` assignments.
 */
internal data class NavigationState(
    val restoring: Boolean = true,
    val destination: Destination = Destination.Auth
) {

    /**
     * Applies a credentials emission.
     *
     * A token that appears after the restore finished means the user just signed in:
     * the auth screen must give way to the list. A token that disappears sends any
     * signed-in screen (list, settings, profile, chat) back to auth, which keeps logout
     * and server-side revocation working through the same rule.
     */
    fun credentialsChanged(present: Boolean): NavigationState = when {
        restoring -> this
        present && destination is Destination.Auth -> copy(destination = Destination.Chats)
        !present && destination !is Destination.Auth -> copy(destination = Destination.Auth)
        else -> this
    }

    /** Records the outcome of [dev.oxype.client.data.OxypeRepository.restoreSession]. */
    fun restoreFinished(signedIn: Boolean): NavigationState = copy(
        restoring = false,
        // A signed-in restore only picks the initial screen. If the app is already
        // showing somewhere (the startup effect re-runs after a rotation), it keeps it.
        destination = when {
            !signedIn -> Destination.Auth
            destination is Destination.Auth -> Destination.Chats
            else -> destination
        }
    )
}

/**
 * The conversation whose chat-screen state must outlive the current destination.
 *
 * Entering the group info page deliberately does not close the chat, so the transcript,
 * the draft and the list position have to survive the round trip. The scroll position is
 * Compose-owned (a `LazyListState`), and switching the destination would drop it unless
 * the state is keyed by something that stays stable across that switch. This id is that
 * key: it is equal for `Chat(42)` and `GroupInfo(42)`, and it falls to `0` as soon as the
 * user leaves the conversation — list, settings, profile, another chat — so a later open
 * starts at the newest message instead of an old offset.
 */
internal fun retainedChatSessionId(destination: Destination): Long = when (destination) {
    is Destination.Chat -> destination.sessionId
    is Destination.GroupInfo -> destination.sessionId
    Destination.Auth,
    Destination.Chats,
    Destination.Settings,
    Destination.Profile -> 0L
}

/**
 * The key a chat branch remembers its screen state under.
 *
 * The key belongs to the destination that branch was composed for. It must never be
 * derived from where navigation has moved to, because `AnimatedContent` keeps composing
 * the outgoing chat for the length of the exit animation while the app has already
 * switched to the list: a key taken from the live destination would flip to `0`
 * mid-animation, recreate the provider and restart the chat's `LaunchedEffect` under the
 * screen that is on its way out.
 *
 * [live] is accepted only so the rule is testable: it is deliberately ignored.
 */
internal fun chatStateKeyFor(content: Destination, live: Destination): Long {
    // The parameter exists to pin the contract; the key is the composed destination's.
    live.hashCode()
    return retainedChatSessionId(content)
}

/**
 * Persists [NavigationState] across configuration changes and process death.
 *
 * Without this a rotation re-executes the startup restore and would drop an open chat.
 * Only the destination is durable: whether a token check still has to run is a property
 * of the current process, so a stored `restoring = false` from the previous process must
 * never become the next process's startup gate. The caller passes the live gate value to
 * [restore] instead. Stored as plain strings so it needs no custom Parcelable.
 */
internal object navigationStateSaver {
    fun save(state: NavigationState): List<String> = when (val current = state.destination) {
        Destination.Auth -> listOf("auth")
        Destination.Chats -> listOf("chats")
        Destination.Settings -> listOf("settings")
        Destination.Profile -> listOf("profile")
        is Destination.Chat -> listOf(
            "chat",
            current.sessionId.toString(),
            current.title,
            current.subtitle
        )

        is Destination.GroupInfo -> listOf(
            "groupinfo",
            current.sessionId.toString(),
            current.title,
            current.subtitle
        )
    }

    /**
     * Rebuilds the saved destination.
     *
     * [restoring] comes from the live process (a fresh repository has not verified its
     * token yet), not from the saved state. That is what makes a rebuilt process show
     * the restore placeholder over the saved chat instead of flashing auth, while a
     * rotation — whose process already resolved the token — reuses its destination
     * without any gate.
     */
    fun restore(saved: List<String>, restoring: Boolean): NavigationState {
        val destination = when (saved.getOrNull(0)) {
            // The list key was renamed from "home" when the tab bar arrived. A process
            // rebuilt from the previous build still has the old key on disk.
            "home", "chats" -> Destination.Chats
            "settings" -> Destination.Settings
            "profile" -> Destination.Profile
            "chat" -> Destination.Chat(
                sessionId = saved.getOrNull(1)?.toLongOrNull() ?: 0L,
                title = saved.getOrNull(2).orEmpty(),
                subtitle = saved.getOrNull(3).orEmpty()
            )

            "groupinfo" -> Destination.GroupInfo(
                sessionId = saved.getOrNull(1)?.toLongOrNull() ?: 0L,
                title = saved.getOrNull(2).orEmpty(),
                subtitle = saved.getOrNull(3).orEmpty()
            )

            else -> Destination.Auth
        }
        return NavigationState(restoring = restoring, destination = destination)
    }
}
