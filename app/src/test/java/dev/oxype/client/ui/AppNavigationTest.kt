package dev.oxype.client.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Navigation rules for credential changes.
 *
 * These encode the two ways the app can get stuck: signing in never leaves the auth
 * screen, and signing out (or a revoked token) never comes back to it.
 */
class AppNavigationTest {

    @Test
    fun `successful sign in moves from auth to chats`() {
        val state = NavigationState(restoring = false, destination = Destination.Auth)

        val next = state.credentialsChanged(present = true)

        assertEquals(Destination.Chats, next.destination)
    }

    @Test
    fun `credentials arriving during restore do not navigate`() {
        val state = NavigationState(restoring = true, destination = Destination.Auth)

        assertSame(state, state.credentialsChanged(present = true))
    }

    @Test
    fun `losing credentials returns any signed in destination to auth`() {
        val chats = NavigationState(restoring = false, destination = Destination.Chats)
        val settings = NavigationState(restoring = false, destination = Destination.Settings)
        val profile = NavigationState(restoring = false, destination = Destination.Profile)
        val chat = NavigationState(
            restoring = false,
            destination = Destination.Chat(9L, "标题", "两位成员")
        )

        assertEquals(Destination.Auth, chats.credentialsChanged(present = false).destination)
        assertEquals(Destination.Auth, settings.credentialsChanged(present = false).destination)
        assertEquals(Destination.Auth, profile.credentialsChanged(present = false).destination)
        assertEquals(Destination.Auth, chat.credentialsChanged(present = false).destination)
    }

    @Test
    fun `credential changes keep a destination they do not affect`() {
        val chats = NavigationState(restoring = false, destination = Destination.Chats)
        assertEquals(
            Destination.Chats,
            chats.credentialsChanged(present = true).destination
        )

        val auth = NavigationState(restoring = false, destination = Destination.Auth)
        assertEquals(Destination.Auth, auth.credentialsChanged(present = false).destination)
    }

    @Test
    fun `finished restore selects the startup destination once`() {
        val restoring = NavigationState()

        val signedIn = restoring.restoreFinished(signedIn = true)
        assertFalse(signedIn.restoring)
        assertEquals(Destination.Chats, signedIn.destination)

        val signedOut = restoring.restoreFinished(signedIn = false)
        assertFalse(signedOut.restoring)
        assertEquals(Destination.Auth, signedOut.destination)
    }

    @Test
    fun `a later credential emission after restore still navigates`() {
        val restored = NavigationState().restoreFinished(signedIn = false)
        assertTrue(restored.credentialsChanged(present = true).destination is Destination.Chats)
    }

    @Test
    fun `a re-run restore keeps the screen the user is on`() {
        // A rotation re-executes the startup effect while the chat is open; finishing it
        // must not silently navigate back to the list.
        val chat = NavigationState(
            restoring = false,
            destination = Destination.Chat(9L, "标题", "两位成员")
        )

        assertEquals(
            Destination.Chat(9L, "标题", "两位成员"),
            chat.restoreFinished(signedIn = true).destination
        )
    }

    @Test
    fun `navigation state survives a save and restore round trip`() {
        val original = NavigationState(
            restoring = false,
            destination = Destination.Chat(9L, "标题", "两位成员")
        )

        assertEquals(
            original,
            navigationStateSaver.restore(navigationStateSaver.save(original), restoring = false)
        )
    }

    @Test
    fun `saved auth destination round trips`() {
        val original = NavigationState(restoring = false, destination = Destination.Auth)
        assertEquals(
            original,
            navigationStateSaver.restore(navigationStateSaver.save(original), restoring = false)
        )
    }

    @Test
    fun `a rebuilt process gates the restored chat until the login is resolved`() {
        // The Activity was killed with the chat open. Its saved state still says
        // `restoring = false`, but the new process has not checked the token yet.
        val saved = navigationStateSaver.save(
            NavigationState(
                restoring = false,
                destination = Destination.Chat(9L, "标题", "两位成员")
            )
        )

        val rebuilt = navigationStateSaver.restore(saved, restoring = true)

        assertTrue("a rebuilt process must wait for the restore", rebuilt.restoring)
        assertEquals(Destination.Chat(9L, "标题", "两位成员"), rebuilt.destination)
    }

    @Test
    fun `a rebuilt chat is not bounced to auth by the empty credential flow`() {
        val rebuilt = navigationStateSaver.restore(
            navigationStateSaver.save(
                NavigationState(
                    restoring = false,
                    destination = Destination.Chat(9L, "标题", "两位成员")
                )
            ),
            restoring = true
        )

        // A fresh repository publishes `null` before the token is verified; treating
        // that as a sign-out is exactly the login flash the gate prevents.
        assertSame(rebuilt, rebuilt.credentialsChanged(present = false))
        assertEquals(
            "a valid stored login returns to the chat, not the list",
            Destination.Chat(9L, "标题", "两位成员"),
            rebuilt.restoreFinished(signedIn = true).destination
        )
        assertEquals(
            "a dead token still lands on auth",
            Destination.Auth,
            rebuilt.restoreFinished(signedIn = false).destination
        )
    }

    @Test
    fun `a rotation keeps the destination without re-gating`() {
        val saved = navigationStateSaver.save(
            NavigationState(
                restoring = false,
                destination = Destination.Chat(9L, "标题", "两位成员")
            )
        )

        // The same process already resolved the login, so there is no splash to show.
        val rotated = navigationStateSaver.restore(saved, restoring = false)

        assertFalse(rotated.restoring)
        assertEquals(Destination.Chat(9L, "标题", "两位成员"), rotated.destination)
        // The credential flow re-emits the already known token; nothing moves.
        assertEquals(
            Destination.Chat(9L, "标题", "两位成员"),
            rotated.credentialsChanged(present = true).destination
        )
    }

    @Test
    fun `the group info page saves and restores with its placeholder header`() {
        val original = NavigationState(
            restoring = false,
            destination = Destination.GroupInfo(42L, "周末徒步小队", "5 位成员")
        )

        // The title and subtitle are only the loading placeholder, but they are the whole
        // header until the server answers — losing them after process death would paint an
        // empty page instead of the conversation the user was looking at.
        val restored = navigationStateSaver.restore(
            navigationStateSaver.save(original),
            restoring = false
        )

        assertEquals(original, restored)
        assertEquals("groupinfo", navigationStateSaver.save(original).first())
    }

    @Test
    fun `an unknown saved destination falls back to auth`() {
        val restored = navigationStateSaver.restore(
            listOf("something-new", "1", "x", "y"),
            restoring = false
        )

        assertEquals(Destination.Auth, restored.destination)
    }

    @Test
    fun `losing credentials on the group info page returns to auth`() {
        val groupInfo = NavigationState(
            restoring = false,
            destination = Destination.GroupInfo(42L, "周末徒步小队", "5 位成员")
        )

        assertEquals(Destination.Auth, groupInfo.credentialsChanged(present = false).destination)
    }

    @Test
    fun `the restore gate is not persisted with the destination`() {
        val saving = NavigationState(restoring = false, destination = Destination.Chats)

        // Only the destination is durable: whether a restore still has to run is a
        // property of the current process, not of the saved state.
        assertEquals(listOf("chats"), navigationStateSaver.save(saving))
    }

    @Test
    fun `the chat keeps its screen state across a group info round trip`() {
        // The list position is Compose-owned, so it only survives if the key under which
        // it is remembered stays the same while the destination changes.
        val chat = retainedChatSessionId(Destination.Chat(42L, "周末徒步小队", "5 位成员"))
        val groupInfo = retainedChatSessionId(Destination.GroupInfo(42L, "周末徒步小队", "5 位成员"))

        assertEquals(42L, chat)
        assertEquals(
            "opening the group info page must not look like leaving the conversation",
            chat,
            groupInfo
        )
    }

    @Test
    fun `leaving the conversation drops the retained chat state`() {
        // A later open of the same id has to start at the newest message, so the key must
        // fall back to "no conversation" as soon as the chat is really left.
        assertEquals(0L, retainedChatSessionId(Destination.Chats))
        assertEquals(0L, retainedChatSessionId(Destination.Settings))
        assertEquals(0L, retainedChatSessionId(Destination.Profile))
        assertEquals(0L, retainedChatSessionId(Destination.Auth))

        val otherChat = retainedChatSessionId(Destination.Chat(7L, "另一个会话", ""))
        assertEquals("a different conversation is a different key", 7L, otherChat)
    }

    @Test
    fun `an exiting chat keeps its own key while navigation has already moved`() {
        // `AnimatedContent` keeps composing the outgoing chat during the exit animation,
        // after `navigation` has already switched to the list. The key must not follow
        // that switch: doing so recreates the provider and restarts the chat's effect
        // under a screen that is disappearing.
        val chat = Destination.Chat(42L, "周末徒步小队", "5 位成员")

        assertEquals(
            "the key belongs to the branch being composed",
            42L,
            chatStateKeyFor(content = chat, live = Destination.Chats)
        )
        assertEquals(
            "and it stays put when the live destination is another section",
            42L,
            chatStateKeyFor(content = chat, live = Destination.Profile)
        )
        assertEquals(
            "the group info round trip keeps the same key on both sides",
            chatStateKeyFor(content = chat, live = Destination.GroupInfo(42L, "周末徒步小队", "5 位成员")),
            chatStateKeyFor(
                content = Destination.GroupInfo(42L, "周末徒步小队", "5 位成员"),
                live = chat
            )
        )
    }

    // ---- top-level tabs ---------------------------------------------------------

    @Test
    fun `the three top-level destinations have distinct tab indexes`() {
        assertEquals(0, tabIndexOf(Destination.Chats))
        assertEquals(1, tabIndexOf(Destination.Settings))
        assertEquals(2, tabIndexOf(Destination.Profile))
    }

    @Test
    fun `detail destinations have no tab index`() {
        assertEquals(-1, tabIndexOf(Destination.Chat(9L, "标题", "两位成员")))
        assertEquals(-1, tabIndexOf(Destination.GroupInfo(9L, "标题", "两位成员")))
        assertEquals(-1, tabIndexOf(Destination.Auth))
    }

    @Test
    fun `only the three tabs are top level`() {
        assertTrue(Destination.Chats.isTopLevel())
        assertTrue(Destination.Settings.isTopLevel())
        assertTrue(Destination.Profile.isTopLevel())
        assertFalse(Destination.Chat(9L, "标题", "两位成员").isTopLevel())
        assertFalse(Destination.GroupInfo(9L, "标题", "两位成员").isTopLevel())
        assertFalse(Destination.Auth.isTopLevel())
    }

    @Test
    fun `a detail destination belongs to the chats section`() {
        // The bottom bar highlights "聊天" while a conversation is open on the way in and
        // out of it; the section is what the bar follows.
        assertEquals(Destination.Chats, Destination.Chat(9L, "标题", "两位成员").sectionFor())
        assertEquals(Destination.Chats, Destination.GroupInfo(9L, "标题", "两位成员").sectionFor())
        assertEquals(Destination.Chats, Destination.Chats.sectionFor())
        assertEquals(Destination.Settings, Destination.Settings.sectionFor())
        assertEquals(Destination.Profile, Destination.Profile.sectionFor())
        assertEquals(Destination.Auth, Destination.Auth.sectionFor())
    }

    @Test
    fun `the top-level destinations round trip through saved state`() {
        for (destination in listOf(Destination.Chats, Destination.Settings, Destination.Profile)) {
            val original = NavigationState(restoring = false, destination = destination)
            assertEquals(
                destination,
                navigationStateSaver.restore(
                    navigationStateSaver.save(original),
                    restoring = false
                ).destination
            )
        }
    }

    @Test
    fun `a saved home destination from the old build reads as chats`() {
        // The key was renamed with the destination. A process restored from the previous
        // version must land on the list, not on auth.
        val restored = navigationStateSaver.restore(listOf("home"), restoring = false)

        assertEquals(Destination.Chats, restored.destination)
    }

    @Test
    fun `the profile tab saves as its own key`() {
        val saving = NavigationState(restoring = false, destination = Destination.Profile)

        assertEquals(listOf("profile"), navigationStateSaver.save(saving))
    }
}
