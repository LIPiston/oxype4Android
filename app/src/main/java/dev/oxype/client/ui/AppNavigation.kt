package dev.oxype.client.ui

/** Which top-level destination is showing. Chat carries its own session id. */
internal sealed interface Destination {
    data object Auth : Destination
    data object Home : Destination
    data object Settings : Destination
    data class Chat(val sessionId: Long, val title: String, val subtitle: String) : Destination
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
     * signed-in screen (list, settings, chat) back to auth, which keeps logout and
     * server-side revocation working through the same rule.
     */
    fun credentialsChanged(present: Boolean): NavigationState = when {
        restoring -> this
        present && destination is Destination.Auth -> copy(destination = Destination.Home)
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
            destination is Destination.Auth -> Destination.Home
            else -> destination
        }
    )
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
        Destination.Home -> listOf("home")
        Destination.Settings -> listOf("settings")
        is Destination.Chat -> listOf(
            "chat",
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
            "home" -> Destination.Home
            "settings" -> Destination.Settings
            "chat" -> Destination.Chat(
                sessionId = saved.getOrNull(1)?.toLongOrNull() ?: 0L,
                title = saved.getOrNull(2).orEmpty(),
                subtitle = saved.getOrNull(3).orEmpty()
            )

            else -> Destination.Auth
        }
        return NavigationState(restoring = restoring, destination = destination)
    }
}
