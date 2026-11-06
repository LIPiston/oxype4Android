package dev.oxype.client.ui.chat

import dev.oxype.client.data.oxype.ChatMessage
import dev.oxype.client.data.oxype.MessagePiece
import dev.oxype.client.data.oxype.MessagePieceKind
import dev.oxype.client.data.oxype.SessionMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a sender is presented when the member directory is available, partial, or absent.
 *
 * The name must come from `getSessionUsers`, and the avatar fallback must work for
 * non-Latin names and names made of several code points.
 */
class SenderPresentationTest {

    private fun member(id: Long, name: String, avatar: String? = null) =
        SessionMember(userId = id, username = name, avatarUrl = avatar)

    private fun message(id: Long, sender: Long) = ChatMessage(
        id = id,
        senderId = sender,
        timestampMillis = 0L,
        editedAtMillis = 0L,
        pieces = listOf(MessagePiece(MessagePieceKind.TEXT, "m$id"))
    )

    @Test
    fun `a sender name comes from the member directory`() {
        val directory = mapOf(
            2L to member(2, "bob"),
            3L to member(3, "carol")
        )

        assertEquals("bob", senderDisplayName(2L, directory))
        assertEquals("carol", senderDisplayName(3L, directory))
    }

    @Test
    fun `a missing or blank member name falls back to the numeric placeholder`() {
        assertEquals("用户 7", senderDisplayName(7L, emptyMap()))
        assertEquals("用户 8", senderDisplayName(8L, mapOf(8L to member(8L, "   "))))
        assertEquals("用户", senderDisplayName(0L, emptyMap()))
    }

    @Test
    fun `the avatar initial is the first unicode code point`() {
        assertEquals("张", avatarInitial("张三"))
        assertEquals("a", avatarInitial("alice"))
        assertEquals("用", avatarInitial(""))
        assertEquals("用", avatarInitial("   "))
        assertEquals("用", avatarInitial(null))
        assertEquals("👨", avatarInitial("👨‍👩‍👧"))
        assertEquals("👨", avatarInitial("👨‍👩‍👧 family"))
    }

    @Test
    fun `avatar colours are stable and stay inside the palette`() {
        assertEquals(avatarColorIndex("bob", 6), avatarColorIndex("bob", 6))
        assertEquals("an empty name must still be stable", avatarColorIndex(null, 6), avatarColorIndex("", 6))
        assertEquals("a one colour palette always uses index 0", 0, avatarColorIndex("bob", 1))

        for (name in listOf("bob", "alice", "张三", "用户 7", "👨‍👩‍👧")) {
            val index = avatarColorIndex(name, 6)
            assertTrue("$name produced $index", index in 0..5)
        }
    }

    @Test
    fun `a conversation with two other members is a group`() {
        val participants = mapOf(
            1L to member(1, "alice"),
            2L to member(2, "bob"),
            3L to member(3, "carol")
        )

        assertTrue(isGroupConversation(participants, emptyList(), currentUserId = 1L))
    }

    @Test
    fun `a one to one conversation keeps the compact layout`() {
        val participants = mapOf(1L to member(1, "alice"), 2L to member(2, "bob"))

        assertFalse(isGroupConversation(participants, emptyList(), currentUserId = 1L))
    }

    @Test
    fun `the transcript proves a group when the directory is short`() {
        // A stale directory lists only one other member, but two different accounts
        // wrote in the transcript, so avatars and names are still needed.
        assertTrue(
            isGroupConversation(
                participants = mapOf(1L to member(1, "alice")),
                messages = listOf(message(1, 2L), message(2, 3L)),
                currentUserId = 1L
            )
        )
    }

    @Test
    fun `a conversation with only my own messages is not a group`() {
        assertFalse(
            isGroupConversation(
                participants = mapOf(1L to member(1, "alice")),
                messages = listOf(message(1, 1L), message(2, 1L)),
                currentUserId = 1L
            )
        )
        assertFalse(isGroupConversation(emptyMap(), emptyList(), currentUserId = 1L))
    }
}
