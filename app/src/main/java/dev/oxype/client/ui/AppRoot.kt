package dev.oxype.client.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.oxype.client.data.AuthRestoreState
import dev.oxype.client.data.OxypeRepository
import dev.oxype.client.data.session.ThemeMode
import dev.oxype.client.ui.auth.AuthScreen
import dev.oxype.client.ui.auth.AuthViewModel
import dev.oxype.client.ui.chat.ChatScreen
import dev.oxype.client.ui.chat.ChatViewModel
import dev.oxype.client.ui.groupinfo.GroupInfoScreen
import dev.oxype.client.ui.groupinfo.GroupInfoViewModel
import dev.oxype.client.ui.home.HomeScreen
import dev.oxype.client.ui.home.HomeViewModel
import dev.oxype.client.ui.settings.SettingsScreen
import dev.oxype.client.ui.settings.SettingsViewModel
import dev.oxype.client.ui.theme.OxypeTheme
import dev.oxype.client.ui.theme.oxypeColors

/**
 * The single navigation point for the app.
 *
 * A hand-written destination state replaces a navigation library: there are three
 * top-level screens and one detail screen, and the back behaviour is a short, explicit
 * rule (chat -> list, settings -> list, list -> system back). Which screen is showing
 * is derived from [NavigationState], so signing in, logging out and a revoked token all
 * move through the same credential signal.
 */
@Composable
fun AppRoot(container: AppContainer) {
    val repository: OxypeRepository = container.repository
    val factory = remember(container) { OxypeViewModelFactory(container) }

    var navigation by rememberSaveable(
        stateSaver = listSaver(
            save = { navigationStateSaver.save(it) },
            restore = {
                // The gate belongs to the live process: a rebuilt one has not verified
                // its stored token yet, while a rotation already has. Reading it here is
                // what stops a saved `restoring = false` from becoming a startup gate.
                navigationStateSaver.restore(
                    it,
                    restoring = repository.authState.value == AuthRestoreState.CHECKING
                )
            }
        )
    ) { mutableStateOf(NavigationState(restoring = repository.authState.value == AuthRestoreState.CHECKING)) }
    var themeMode by remember { mutableStateOf(container.sessionStore.themeMode) }

    val authViewModel: AuthViewModel = viewModel(factory = factory)
    val homeViewModel: HomeViewModel = viewModel(factory = factory)
    val chatViewModel: ChatViewModel = viewModel(factory = factory)
    val groupInfoViewModel: GroupInfoViewModel = viewModel(factory = factory)
    val settingsViewModel: SettingsViewModel = viewModel(factory = factory)

    // Startup: a stored token is verified before the list is shown. A dead token sends
    // the user back to sign-in with an explanation rather than a silent failure.
    LaunchedEffect(Unit) {
        val signedIn = repository.restoreSession()
        if (!signedIn && repository.sessionExpired.value) authViewModel.showExpiredNotice()
        navigation = navigation.restoreFinished(signedIn)
    }

    // The credential flow is the single navigation signal: a token that appears after
    // the restore moves auth -> home (fresh login/registration), and a token that
    // disappears moves any signed-in screen back to auth (logout or revocation).
    // ChatViewModel watches the same flow and drops its transcript/coordinator binding,
    // so a later open of the same conversation cannot reuse the previous account's state.
    LaunchedEffect(Unit) {
        repository.credentials.collect { credentials ->
            navigation = navigation.credentialsChanged(credentials != null)
        }
    }

    // Follow theme changes made in settings without recreating the activity.
    LaunchedEffect(Unit) {
        container.themeMode.collect { themeMode = it }
    }

    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    OxypeTheme(darkTheme = darkTheme) {
        // Chat-screen state that Compose owns — the LazyListState above all — is kept
        // here. The group info page is a detour, not a departure: `retainedChatSessionId`
        // stays the same across that switch, so the provider leaves composition (saving
        // the list position) and comes back under the same key.
        val chatScreenState = rememberSaveableStateHolder()
        val retainedChatId = retainedChatSessionId(navigation.destination)
        if (navigation.restoring) {
            // A fresh process must not paint auth (or an empty chat) while the stored
            // token is still being verified: the placeholder holds the frame until the
            // answer arrives, and the saved destination is shown straight afterwards.
            RestorePlaceholder()
            return@OxypeTheme
        }
        when (val current = navigation.destination) {
            Destination.Auth -> AuthScreen(viewModel = authViewModel)

            Destination.Home -> {
                HomeScreen(
                    viewModel = homeViewModel,
                    username = repository.storedUsername(),
                    onOpenChat = { sessionId ->
                        val summary = homeViewModel.state.value.sessions
                            .firstOrNull { it.sessionId == sessionId }
                        navigation = navigation.copy(
                            destination = Destination.Chat(
                                sessionId = sessionId,
                                title = summary?.name ?: "会话 $sessionId",
                                subtitle = summary?.let { "${it.memberCount} 位成员" } ?: ""
                            )
                        )
                    },
                    onOpenSettings = {
                        navigation = navigation.copy(destination = Destination.Settings)
                    }
                )
            }

            Destination.Settings -> {
                BackHandler { navigation = navigation.copy(destination = Destination.Home) }
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onBack = { navigation = navigation.copy(destination = Destination.Home) },
                    onSignedOut = {
                        // The credential flow already sends the app to auth; dropping the
                        // cached rows here only hides them for the frame in between.
                        homeViewModel.onSessionsChanged(emptyList())
                    }
                )
            }

            is Destination.Chat -> {
                val leaveChat = {
                    chatViewModel.close()
                    // Coming back from a conversation is the moment its preview and
                    // ordering can have changed, so the list is re-read then.
                    homeViewModel.refresh()
                    navigation = navigation.copy(destination = Destination.Home)
                }
                BackHandler { leaveChat() }
                chatScreenState.SaveableStateProvider(retainedChatId) {
                    ChatScreen(
                        viewModel = chatViewModel,
                        sessionId = current.sessionId,
                        title = current.title,
                        subtitle = current.subtitle,
                        onBack = leaveChat,
                        onOpenGroupInfo = { sessionId, title, subtitle ->
                            navigation = navigation.copy(
                                destination = Destination.GroupInfo(sessionId, title, subtitle)
                            )
                        }
                    )
                }
            }

            is Destination.GroupInfo -> {
                // Back goes to the chat, and *not* through `chatViewModel.close()`: the
                // transcript, the draft and the scroll position must survive the round
                // trip. Re-composing ChatScreen hits the "same conversation" shortcut in
                // `ChatViewModel.open`, and `repeatOnLifecycle` resumes the poll loop.
                // The chat's saved state stays in `chatScreenState` untouched while this
                // branch is on screen, so returning restores the same list position.
                val backToChat = {
                    navigation = navigation.copy(
                        destination = Destination.Chat(
                            sessionId = current.sessionId,
                            title = current.title,
                            subtitle = current.subtitle
                        )
                    )
                }
                BackHandler { backToChat() }
                GroupInfoScreen(
                    viewModel = groupInfoViewModel,
                    sessionId = current.sessionId,
                    title = current.title,
                    subtitle = current.subtitle,
                    onBack = backToChat
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        repository.sessionExpired.collect { expired ->
            if (expired) authViewModel.showExpiredNotice()
        }
    }
}

/**
 * The neutral frame shown while a rebuilt process verifies its stored login.
 *
 * It deliberately shows neither the auth form nor an empty transcript, so a valid
 * restored session never flashes the sign-in screen on the way to the chat.
 */
@Composable
private fun RestorePlaceholder() {
    val colors = oxypeColors
    Surface(modifier = Modifier.fillMaxSize(), color = colors.appBackground) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = colors.accent)
        }
    }
}
