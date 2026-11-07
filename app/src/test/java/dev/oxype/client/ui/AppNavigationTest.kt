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
    fun `successful sign in moves from auth to home`() {
        val state = NavigationState(restoring = false, destination = Destination.Auth)

        val next = state.credentialsChanged(present = true)

        assertEquals(Destination.Home, next.destination)
    }

    @Test
    fun `credentials arriving during restore do not navigate`() {
        val state = NavigationState(restoring = true, destination = Destination.Auth)

        assertSame(state, state.credentialsChanged(present = true))
    }

    @Test
    fun `losing credentials returns any signed in destination to auth`() {
        val home = NavigationState(restoring = false, destination = Destination.Home)
        val settings = NavigationState(restoring = false, destination = Destination.Settings)
        val chat = NavigationState(
            restoring = false,
            destination = Destination.Chat(9L, "标题", "两位成员")
        )

        assertEquals(Destination.Auth, home.credentialsChanged(present = false).destination)
        assertEquals(Destination.Auth, settings.credentialsChanged(present = false).destination)
        assertEquals(Destination.Auth, chat.credentialsChanged(present = false).destination)
    }

    @Test
    fun `credential changes keep a destination they do not affect`() {
        val home = NavigationState(restoring = false, destination = Destination.Home)
        assertEquals(
            Destination.Home,
            home.credentialsChanged(present = true).destination
        )

        val auth = NavigationState(restoring = false, destination = Destination.Auth)
        assertEquals(Destination.Auth, auth.credentialsChanged(present = false).destination)
    }

    @Test
    fun `finished restore selects the startup destination once`() {
        val restoring = NavigationState()

        val signedIn = restoring.restoreFinished(signedIn = true)
        assertFalse(signedIn.restoring)
        assertEquals(Destination.Home, signedIn.destination)

        val signedOut = restoring.restoreFinished(signedIn = false)
        assertFalse(signedOut.restoring)
        assertEquals(Destination.Auth, signedOut.destination)
    }

    @Test
    fun `a later credential emission after restore still navigates`() {
        val restored = NavigationState().restoreFinished(signedIn = false)
        assertTrue(restored.credentialsChanged(present = true).destination is Destination.Home)
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
        val saving = NavigationState(restoring = false, destination = Destination.Home)

        // Only the destination is durable: whether a restore still has to run is a
        // property of the current process, not of the saved state.
        assertEquals(listOf("home"), navigationStateSaver.save(saving))
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
        assertEquals(0L, retainedChatSessionId(Destination.Home))
        assertEquals(0L, retainedChatSessionId(Destination.Settings))
        assertEquals(0L, retainedChatSessionId(Destination.Auth))

        val otherChat = retainedChatSessionId(Destination.Chat(7L, "另一个会话", ""))
        assertEquals("a different conversation is a different key", 7L, otherChat)
    }
}
