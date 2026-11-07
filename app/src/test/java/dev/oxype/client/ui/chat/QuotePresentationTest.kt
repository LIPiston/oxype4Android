package dev.oxype.client.ui.chat

import dev.oxype.client.data.oxype.ChatMessage
import dev.oxype.client.data.oxype.ReplyQuote
import dev.oxype.client.data.oxype.SessionMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a quote block says.
 *
 * The quoted snapshot is written by the server and this client cannot verify its
 * spelling, so every field but the id may be missing. Each fallback step is tested on
 * its own because a wrong one draws either a nameless block or an empty body.
 */
class QuotePresentationTest {

    private val participants = mapOf(
        2L to SessionMember(userId = 2L, username = "林间小道"),
        3L to SessionMember(userId = 3L, username = "青柠")
    )

    @Test
    fun `the snapshot name wins over the directory`() {
        val quote = ReplyQuote(messageId = 7L, senderId = 2L, senderName = "旧名字", text = "原话")

        assertEquals("旧名字", quoteAuthorName(quote, participants, currentUserId = 1L))
    }

    @Test
    fun `a blank snapshot name falls back to the directory`() {
        val quote = ReplyQuote(messageId = 7L, senderId = 2L, senderName = "   ", text = "原话")

        assertEquals("林间小道", quoteAuthorName(quote, participants, currentUserId = 1L))
    }

    @Test
    fun `a quote of my own message says so even when the snapshot carries a name`() {
        // The block answers "who am I replying to"; my own name adds nothing the bubble
        // side does not already imply, and §7 requires `我` for this case.
        val quote = ReplyQuote(messageId = 7L, senderId = 1L, senderName = "我的昵称", text = "我说的话")

        assertEquals("我", quoteAuthorName(quote, participants, currentUserId = 1L))
    }

    @Test
    fun `a quote of my own message without a snapshot name still says so`() {
        val quote = ReplyQuote(messageId = 7L, senderId = 1L, text = "我说的话")

        assertEquals("我", quoteAuthorName(quote, participants, currentUserId = 1L))
    }

    @Test
    fun `an unknown sender with an id gets the numeric label`() {
        val quote = ReplyQuote(messageId = 7L, senderId = 9L, text = "原话")

        assertEquals("用户 9", quoteAuthorName(quote, participants, currentUserId = 1L))
    }

    @Test
    fun `a quote with no sender at all is labelled generically`() {
        val quote = ReplyQuote(messageId = 7L, text = "原话")

        assertEquals("未知用户", quoteAuthorName(quote, participants, currentUserId = 1L))
    }

    @Test
    fun `the body falls back to the locally loaded message`() {
        val quote = ReplyQuote(messageId = 7L, senderId = 2L)
        val local = chatMessage(id = 7L, text = "本地已加载的原话")

        assertEquals("本地已加载的原话", quoteBodyText(quote, local))
    }

    @Test
    fun `the snapshot body wins over the local copy`() {
        val quote = ReplyQuote(messageId = 7L, senderId = 2L, text = "发送时的原话")
        val local = chatMessage(id = 7L, text = "后来编辑过的原话")

        assertEquals("发送时的原话", quoteBodyText(quote, local))
    }

    @Test
    fun `without any body the block stays empty instead of inventing one`() {
        val quote = ReplyQuote(messageId = 7L, senderId = 2L)

        assertEquals("", quoteBodyText(quote, null))
    }

    @Test
    fun `a quote is jumpable only when the target is loaded`() {
        val quote = ReplyQuote(messageId = 7L, senderId = 2L, text = "原话")
        val loaded = mapOf(7L to chatMessage(id = 7L, text = "原话"))

        assertTrue(resolveQuote(quote, participants, loaded, currentUserId = 1L).isJumpable)
        assertFalse(resolveQuote(quote, participants, emptyMap(), currentUserId = 1L).isJumpable)
    }

    @Test
    fun `resolved view carries the target id and both texts`() {
        val quote = ReplyQuote(messageId = 5L, senderId = 3L, text = "原话")

        val view = resolveQuote(quote, participants, emptyMap(), currentUserId = 1L)

        assertEquals(5L, view.targetMessageId)
        assertEquals("青柠", view.author)
        assertEquals("原话", view.body)
    }

    private fun chatMessage(id: Long, text: String): ChatMessage = ChatMessage(
        id = id,
        senderId = 2L,
        timestampMillis = 1767225600000,
        editedAtMillis = 0L,
        pieces = listOf(dev.oxype.client.data.oxype.MessagePiece(
            dev.oxype.client.data.oxype.MessagePieceKind.TEXT,
            text
        ))
    )
}
