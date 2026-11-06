package dev.oxype.client.ui.chat

import dev.oxype.client.data.oxype.ChatMessage
import dev.oxype.client.data.oxype.MessagePiece
import dev.oxype.client.data.oxype.MessagePieceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Ordering of the reversed transcript.
 *
 * In a `reverseLayout` list index 0 is drawn at the bottom, so a day divider that must
 * appear *above* a message has to come *after* it in this list. Getting this backwards
 * paints the date label underneath the first message of each day.
 */
class ChatListLayoutTest {

    private fun message(id: Long, timestampMillis: Long) = ChatMessage(
        id = id,
        senderId = 1L,
        timestampMillis = timestampMillis,
        editedAtMillis = 0L,
        pieces = listOf(MessagePiece(MessagePieceKind.TEXT, "m$id"))
    )

    private fun day(year: Int, month: Int, dayOfMonth: Int, hour: Int = 12): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, dayOfMonth, hour, 0, 0)
        }.timeInMillis

    @Test
    fun `day divider follows the oldest message of that day`() {
        val newer = message(2, day(2024, Calendar.MARCH, 2, 9))
        val older = message(1, day(2024, Calendar.MARCH, 1, 9))

        val items = chatListItems(newestFirst = listOf(newer, older))

        // Bottom-to-top: newest bubble, its day label, older bubble, its day label.
        assertEquals(4, items.size)
        val rows = items.map { row -> row::class.simpleName }
        assertEquals(listOf("Message", "Day", "Message", "Day"), rows)
        val messages = items.filterIsInstance<ChatListItem.Message>().map { it.message.id }
        assertEquals(listOf(2L, 1L), messages)
    }

    @Test
    fun `same day group shares one divider at its oldest edge`() {
        val messages = listOf(
            message(3, day(2024, Calendar.MARCH, 2, 20)),
            message(2, day(2024, Calendar.MARCH, 2, 18)),
            message(1, day(2024, Calendar.MARCH, 1, 9))
        )

        val items = chatListItems(newestFirst = messages)

        // M3, M2, D(3-2), M1, D(3-1)
        assertEquals(5, items.size)
        assertEquals(listOf("m3", "m2"), items.filterIsInstance<ChatListItem.Message>()
            .take(2).map { it.message.plainText })
        val dayLabels = items.filterIsInstance<ChatListItem.Day>().map { it.label }
        assertEquals(listOf("2024年3月2日", "2024年3月1日"), dayLabels)
        assertEquals(2, items.indexOfFirst { it is ChatListItem.Day })
    }

    @Test
    fun `unknown timestamps are grouped together`() {
        val items = chatListItems(
            newestFirst = listOf(message(2, 0L), message(1, 0L))
        )

        assertEquals(3, items.size)
        assertEquals(1, items.count { it is ChatListItem.Day })
        assertEquals("未知日期", items.filterIsInstance<ChatListItem.Day>().single().label)
    }

    @Test
    fun `empty transcript yields no items`() {
        assertTrue(chatListItems(emptyList()).isEmpty())
    }
}
