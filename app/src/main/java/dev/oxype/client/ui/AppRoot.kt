package dev.oxype.client.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
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
import dev.oxype.client.ui.components.OxypeBottomBar
import dev.oxype.client.ui.groupinfo.GroupInfoScreen
import dev.oxype.client.ui.groupinfo.GroupInfoViewModel
import dev.oxype.client.ui.home.HomeScreen
import dev.oxype.client.ui.home.HomeViewModel
import dev.oxype.client.ui.profile.ProfileScreen
import dev.oxype.client.ui.profile.ProfileViewModel
import dev.oxype.client.ui.settings.SettingsScreen
import dev.oxype.client.ui.settings.SettingsViewModel
import dev.oxype.client.ui.theme.OxypeTheme
import dev.oxype.client.ui.theme.oxypeColors

/**
 * The single navigation point for the app.
 *
 * A hand-written destination state replaces a navigation library: three top-level
 * sections, two detail screens belonging to the chats section, and the signed-out state.
 * Which screen is showing is derived from [NavigationState], so signing in, logging out
 * and a revoked token all move through the same credential signal.
 *
 * The bottom bar is drawn on the three sections only. A conversation needs the whole
 * screen for its input, and the group info page is a detour off a conversation, so the
 * bar disappears there — the sections are reachable again by going back.
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
    val profileViewModel: ProfileViewModel = viewModel(factory = factory)

    // Startup: a stored token is verified before the list is shown. A dead token sends
    // the user back to sign-in with an explanation rather than a silent failure.
    LaunchedEffect(Unit) {
        val signedIn = repository.restoreSession()
        if (!signedIn && repository.sessionExpired.value) authViewModel.showExpiredNotice()
        navigation = navigation.restoreFinished(signedIn)
    }

    // The credential flow is the single navigation signal: a token that appears after
    // the restore moves auth -> chats (fresh login/registration), and a token that
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

    val navigate: (Destination) -> Unit = { destination ->
        navigation = navigation.copy(destination = destination)
    }

    OxypeTheme(darkTheme = darkTheme) {
        // Compose-owned screen state — the chat's LazyListState above all — is kept here.
        // The group info page is a detour, not a departure: `retainedChatSessionId` stays
        // the same across that switch, so the provider leaves composition (saving the list
        // position) and comes back under the same key.
        val chatScreenState = rememberSaveableStateHolder()
        // Section scroll positions are kept per tab, so switching to settings and back
        // does not jump the conversation list to the top.
        val sectionState = rememberSaveableStateHolder()
        val retainedChatId = retainedChatSessionId(navigation.destination)
        if (navigation.restoring) {
            // A fresh process must not paint auth (or an empty chat) while the stored
            // token is still being verified: the placeholder holds the frame until the
            // answer arrives, and the saved destination is shown straight afterwards.
            RestorePlaceholder()
            return@OxypeTheme
        }

        DestinationHost(
            current = navigation.destination,
            username = repository.storedUsername(),
            chatScreenState = chatScreenState,
            sectionState = sectionState,
            retainedChatId = retainedChatId,
            authViewModel = authViewModel,
            homeViewModel = homeViewModel,
            chatViewModel = chatViewModel,
            groupInfoViewModel = groupInfoViewModel,
            settingsViewModel = settingsViewModel,
            profileViewModel = profileViewModel,
            navigate = navigate
        )
    }

    LaunchedEffect(Unit) {
        repository.sessionExpired.collect { expired ->
            if (expired) authViewModel.showExpiredNotice()
        }
    }
}

/**
 * Animates between every destination and puts the bar on the sections.
 *
 * Two motions, chosen by what is moving:
 *
 * - a tab switch slides by a twelfth of the width with a short fade, so the sections feel
 *   adjacent rather than pushed;
 * - entering a conversation (or the group info page) slides the whole screen in from the
 *   right and back out, which is the usual "detail on top" cue;
 * - chat <-> group info fades, because it is a lateral move inside one conversation and a
 *   slide would look like leaving the conversation.
 *
 * Durations stay under 220ms: a transition that outlives a tap makes the app feel slower
 * than the network it is waiting on.
 */
@Composable
private fun DestinationHost(
    current: Destination,
    username: String?,
    chatScreenState: SaveableStateHolder,
    sectionState: SaveableStateHolder,
    retainedChatId: Long,
    authViewModel: AuthViewModel,
    homeViewModel: HomeViewModel,
    chatViewModel: ChatViewModel,
    groupInfoViewModel: GroupInfoViewModel,
    settingsViewModel: SettingsViewModel,
    profileViewModel: ProfileViewModel,
    navigate: (Destination) -> Unit
) {
    // The bar lives outside the animated content so it stays put while a section slides
    // beneath it — only its selection pill moves. `current` decides whether it is drawn at
    // all: a conversation and the group info page own the full screen.
    val selectedTab = bottomTabFor(current)
    // While the bar fades out into a conversation it keeps drawing the section the user
    // just left; without this the pill would jump back to 聊天 on the way out.
    var lastTab by remember { mutableStateOf(selectedTab ?: BottomTab.CHATS) }
    LaunchedEffect(selectedTab) {
        if (selectedTab != null) lastTab = selectedTab
    }
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = current,
            transitionSpec = {
                val from = initialState
                val to = targetState
                when {
                    from is Destination.Auth || to is Destination.Auth -> {
                        // Signing in or out replaces the whole frame; a slide would suggest
                        // the screens are neighbours in a stack, which they are not.
                        fadeIn(tween(180)) togetherWith fadeOut(tween(140))
                    }

                    from.isSection() && to.isSection() -> {
                        val forward = tabIndexOf(to) >= tabIndexOf(from)
                        val direction = if (forward) 1 else -1
                        (
                            slideInHorizontally(
                                animationSpec = tween(200, easing = FastOutSlowInEasing),
                                initialOffsetX = { width -> direction * width / 12 }
                            ) + fadeIn(tween(180))
                            ).togetherWith(
                            slideOutHorizontally(
                                animationSpec = tween(140, easing = FastOutSlowInEasing),
                                targetOffsetX = { width -> -direction * width / 12 }
                            ) + fadeOut(tween(120))
                        )
                    }

                    from.isChatSide() && to.isChatSide() -> {
                        fadeIn(tween(160)) togetherWith fadeOut(tween(120))
                    }

                    to.isChatSide() -> {
                        (
                            slideInHorizontally(
                                animationSpec = tween(220, easing = FastOutSlowInEasing),
                                initialOffsetX = { width -> width }
                            ) + fadeIn(tween(160))
                            ).togetherWith(
                            fadeOut(tween(120))
                        )
                    }

                    else -> {
                        fadeIn(tween(160)) togetherWith
                            (
                                slideOutHorizontally(
                                    animationSpec = tween(200, easing = FastOutSlowInEasing),
                                    targetOffsetX = { width -> width }
                                ) + fadeOut(tween(140))
                                )
                    }
                }
            },
            label = "destination"
        ) { destination ->
            when (destination) {
                Destination.Auth -> AuthScreen(viewModel = authViewModel)

                Destination.Chats -> sectionState.SaveableStateProvider("chats") {
                    HomeScreen(
                        viewModel = homeViewModel,
                        username = username,
                        onOpenChat = { sessionId ->
                            val summary = homeViewModel.state.value.sessions
                                .firstOrNull { it.sessionId == sessionId }
                            navigate(
                                Destination.Chat(
                                    sessionId = sessionId,
                                    title = summary?.name ?: "会话 $sessionId",
                                    subtitle = summary?.let { "${it.memberCount} 位成员" } ?: ""
                                )
                            )
                        }
                    )
                }

                Destination.Settings -> {
                    BackHandler { navigate(Destination.Chats) }
                    sectionState.SaveableStateProvider("settings") {
                        SettingsScreen(
                            viewModel = settingsViewModel,
                            onOpenProfile = { navigate(Destination.Profile) },
                            onSignedOut = {
                                // The credential flow already sends the app to auth;
                                // dropping the cached rows here only hides them for the
                                // frame in between.
                                homeViewModel.onSessionsChanged(emptyList())
                            }
                        )
                    }
                }

                Destination.Profile -> {
                    BackHandler { navigate(Destination.Chats) }
                    // Leaving the page is what closes it: an upload started here must not
                    // land on the next visit. `open()` is what starts a visit, so the two
                    // bound the same generation.
                    DisposableEffect(Unit) { onDispose { profileViewModel.close() } }
                    sectionState.SaveableStateProvider("profile") {
                        ProfileScreen(
                            viewModel = profileViewModel,
                            onOpenSettings = { navigate(Destination.Settings) }
                        )
                    }
                }

                is Destination.Chat -> {
                    val leaveChat = {
                        chatViewModel.close()
                        // Coming back from a conversation is the moment its preview and
                        // ordering can have changed, so the list is re-read then.
                        homeViewModel.refresh()
                        navigate(Destination.Chats)
                    }
                    BackHandler { leaveChat() }
                    chatScreenState.SaveableStateProvider(retainedChatId) {
                        ChatScreen(
                            viewModel = chatViewModel,
                            sessionId = destination.sessionId,
                            title = destination.title,
                            subtitle = destination.subtitle,
                            onBack = leaveChat,
                            onOpenGroupInfo = { sessionId, title, subtitle ->
                                navigate(Destination.GroupInfo(sessionId, title, subtitle))
                            }
                        )
                    }
                }

                is Destination.GroupInfo -> {
                    // Back goes to the chat, and *not* through `chatViewModel.close()`:
                    // the transcript, the draft and the scroll position must survive the
                    // round trip. Re-composing ChatScreen hits the "same conversation"
                    // shortcut in `ChatViewModel.open`, and `repeatOnLifecycle` resumes the
                    // poll loop. The chat's saved state stays in `chatScreenState`
                    // untouched while this branch is on screen, so returning restores the
                    // same list position.
                    val backToChat = {
                        navigate(
                            Destination.Chat(
                                sessionId = destination.sessionId,
                                title = destination.title,
                                subtitle = destination.subtitle
                            )
                        )
                    }
                    BackHandler { backToChat() }
                    GroupInfoScreen(
                        viewModel = groupInfoViewModel,
                        sessionId = destination.sessionId,
                        title = destination.title,
                        subtitle = destination.subtitle,
                        onBack = backToChat
                    )
                }
            }
        }

        // Drawn last so it floats over the section that is sliding underneath, and faded
        // rather than popped when a conversation takes over the screen.
        AnimatedVisibility(
            visible = selectedTab != null,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(120)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            OxypeBottomBar(
                selected = lastTab,
                onSelect = onSelectFrom(current, navigate)
            )
        }
    }
}

/**
 * True for the three destinations the bottom bar switches between.
 *
 * The bar itself is placed by the host so it can stay fixed while a section slides; only
 * the section decides whether the bar is drawn at all.
 */
private fun Destination.isSection(): Boolean = isTopLevel()

/**
 * Turns a bar tap into a destination for the section it was made from.
 *
 * `from` matters because a tap on the already-selected tab is a no-op: re-assigning the
 * same destination would restart its entry animation and, worse, drop a section's saved
 * scroll state for no reason.
 */
private fun onSelectFrom(
    from: Destination,
    navigate: (Destination) -> Unit
): (BottomTab) -> Unit = { tab ->
    val target = when (tab) {
        BottomTab.CHATS -> Destination.Chats
        BottomTab.SETTINGS -> Destination.Settings
        BottomTab.PROFILE -> Destination.Profile
    }
    if (target != from) navigate(target)
}

/** True for the two screens inside the chats section. */
private fun Destination.isChatSide(): Boolean =
    this is Destination.Chat || this is Destination.GroupInfo

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
