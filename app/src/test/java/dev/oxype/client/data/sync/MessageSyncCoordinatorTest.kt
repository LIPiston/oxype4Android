package dev.oxype.client.data.sync

import dev.oxype.client.data.oxype.ChatMessage
import dev.oxype.client.data.oxype.LastMessageInfo
import dev.oxype.client.data.oxype.LastMessageResult
import dev.oxype.client.data.oxype.MessagePiece
import dev.oxype.client.data.oxype.MessagePieceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Paging and dedup rules.
 *
 * These cover the three ways an Oxype transcript can surprise a client: a range is
 * inclusive at both ends, deleted sequence numbers leave holes, and the same message
 * can be delivered more than once.
 */
class MessageSyncCoordinatorTest {

    private fun message(
        id: Long,
        text: String = "m$id",
        sender: Long = 1L,
        timestamp: Long = 1_700_000_000_000L + id
    ) = ChatMessage(
        id = id,
        senderId = sender,
        timestampMillis = timestamp,
        editedAtMillis = 0L,
        pieces = listOf(MessagePiece(MessagePieceKind.TEXT, text))
    )

    @Test
    fun `history pages merge in ascending order`() {
        val sync = MessageSyncCoordinator()
        sync.applyHistory(
            requestedStart = 1,
            requestedEnd = 3,
            page = listOf(message(3), message(1), message(2))
        )

        assertEquals(listOf(1L, 2L, 3L), sync.messages.map { it.id })
    }

    @Test
    fun `inclusive window is remembered and marks top when it reaches one`() {
        val sync = MessageSyncCoordinator()
        sync.applyHistory(1, 3, listOf(message(1)))

        assertEquals(1L, sync.oldestRequestedSeq)
        assertTrue(sync.reachedTop)
        assertNull(sync.nextHistoryWindow())
    }

    @Test
    fun `holes in a range do not advance the local view incorrectly`() {
        val sync = MessageSyncCoordinator()
        // Requested 10..12 but sequence 11 was deleted: only two records come back.
        val added = sync.applyHistory(10, 12, listOf(message(10), message(12)))

        assertEquals(2, added)
        assertEquals(listOf(10L, 12L), sync.messages.map { it.id })
        assertEquals(10L, sync.oldestRequestedSeq)
        assertEquals(12L, sync.newestKnownSeq)
        assertFalse(sync.reachedTop)
    }

    @Test
    fun `empty page still records the requested boundary`() {
        val sync = MessageSyncCoordinator()
        val added = sync.applyHistory(7, 9, emptyList())

        assertEquals(0, added)
        assertEquals(7L, sync.oldestRequestedSeq)
        assertEquals(9L, sync.newestKnownSeq)
    }

    @Test
    fun `duplicate messages are stored once`() {
        val sync = MessageSyncCoordinator()
        sync.applyHistory(1, 2, listOf(message(1, "first"), message(2, "second")))
        val addedAgain = sync.applyHistory(1, 2, listOf(message(1, "first"), message(2, "second")))

        assertEquals(0, addedAgain)
        assertEquals(2, sync.size)
    }

    @Test
    fun `polling merge ignores messages already delivered by a send echo`() {
        val sync = MessageSyncCoordinator()
        sync.mergeIncoming(listOf(message(5, "sent")))
        val added = sync.mergeIncoming(listOf(message(5, "sent"), message(6, "reply")))

        assertEquals(1, added)
        assertEquals(2, sync.size)
        assertEquals(6L, sync.newestKnownSeq)
    }

    @Test
    fun `next history window walks down from the requested boundary`() {
        val sync = MessageSyncCoordinator()
        sync.applyHistory(31, 60, listOf(message(31)))

        val window = sync.nextHistoryWindow(pageSize = 30)
        assertEquals(1L, window?.start)
        assertEquals(30L, window?.endInclusive)
    }

    @Test
    fun `history window starting at zero counts as the top`() {
        val sync = MessageSyncCoordinator()
        // A conversation younger than one page: the window begins at 0.
        sync.applyHistory(0, 4, listOf(message(1), message(2), message(3), message(4)))

        assertEquals(0L, sync.oldestRequestedSeq)
        assertTrue(sync.reachedTop)
        assertNull(sync.nextHistoryWindow())
    }

    @Test
    fun `history window stops at sequence zero instead of going negative`() {
        val sync = MessageSyncCoordinator()
        sync.applyHistory(5, 34, listOf(message(5)))

        val window = sync.nextHistoryWindow(pageSize = 30)
        assertEquals(0L, window?.start)
        assertEquals(4L, window?.endInclusive)
    }

    @Test
    fun `catch up asks only for messages above the watermark`() {
        val sync = MessageSyncCoordinator()
        sync.mergeIncoming(listOf(message(1), message(2)))

        val window = sync.planCatchUp(serverNewestSeq = 5)
        assertEquals(3L, window?.start)
        assertEquals(5L, window?.endInclusive)
    }

    @Test
    fun `catch up returns nothing when the server has no newer message`() {
        val sync = MessageSyncCoordinator()
        sync.mergeIncoming(listOf(message(4)))

        assertNull(sync.planCatchUp(serverNewestSeq = 4))
        assertNull(sync.planCatchUp(serverNewestSeq = 3))
    }

    @Test
    fun `catch up caps a large backlog into batches`() {
        val sync = MessageSyncCoordinator()
        sync.mergeIncoming(listOf(message(1)))

        val window = sync.planCatchUp(serverNewestSeq = 500, maxBatch = 20)
        assertEquals(2L, window?.start)
        assertEquals(21L, window?.endInclusive)

        sync.mergeIncoming((2L..21L).map { message(it) })
        val next = sync.planCatchUp(serverNewestSeq = 500, maxBatch = 20)
        assertEquals(22L, next?.start)
    }

    @Test
    fun `empty and unreadable last message answers carry no newest sequence`() {
        assertNull(MessageSyncCoordinator.newestFromLastMessage(null))
        assertNull(MessageSyncCoordinator.newestFromLastMessage(LastMessageResult.Empty))
        assertNull(MessageSyncCoordinator.newestFromLastMessage(LastMessageResult.Unknown))
        assertEquals(
            9L,
            MessageSyncCoordinator.newestFromLastMessage(
                LastMessageResult.Present(LastMessageInfo(true, 9, 1, "a", 5, "hi"))
            )
        )
        // A present record with a non-positive id is not a sequence either.
        assertNull(
            MessageSyncCoordinator.newestFromLastMessage(
                LastMessageResult.Present(LastMessageInfo(true, 0, 0, null, 0, ""))
            )
        )
    }

    @Test
    fun `unusable records are skipped`() {
        val sync = MessageSyncCoordinator()
        val broken = ChatMessage(0, 1, 0, 0, listOf(MessagePiece(MessagePieceKind.TEXT, "x")))
        val empty = ChatMessage(3, 1, 0, 0, emptyList())

        assertEquals(0, sync.mergeIncoming(listOf(broken, empty)))
        assertEquals(0, sync.size)
    }

    @Test
    fun `removing a deleted message keeps the rest`() {
        val sync = MessageSyncCoordinator()
        sync.applyHistory(1, 3, listOf(message(1), message(2), message(3)))

        assertTrue(sync.remove(2))
        assertFalse(sync.remove(99))
        assertEquals(listOf(1L, 3L), sync.messages.map { it.id })
        // The watermark must not move back: sequence 2 is simply gone.
        assertEquals(3L, sync.newestKnownSeq)
    }

    @Test
    fun `a deleted message does not come back from a late history page`() {
        // The delete is confirmed while an older page is still in flight; that page was
        // composed before the deletion, so it still carries the record.
        val sync = MessageSyncCoordinator()
        sync.applyHistory(1, 3, listOf(message(1), message(2), message(3)))
        assertTrue(sync.remove(2))

        val added = sync.applyHistory(1, 3, listOf(message(1), message(2), message(3)))

        assertEquals(0, added)
        assertEquals("the tombstone must outlive the fetch", listOf(1L, 3L), sync.messages.map { it.id })
    }

    @Test
    fun `a deleted message does not come back from a poll or a send tail`() {
        val sync = MessageSyncCoordinator()
        sync.applyHistory(1, 3, listOf(message(1), message(2), message(3)))
        sync.remove(3)

        sync.mergeIncoming(listOf(message(3), message(4)))
        assertEquals(listOf(1L, 2L, 4L), sync.messages.map { it.id })

        sync.mergeWindow(1, 3, listOf(message(3)))
        assertEquals(listOf(1L, 2L, 4L), sync.messages.map { it.id })
    }

    @Test
    fun `a tombstone survives a clear, and only a new coordinator trusts the server again`() {
        // `clear` empties the records but the deletion is still authoritative: a page
        // merged afterwards must not recreate the row.
        val sync = MessageSyncCoordinator()
        sync.applyHistory(1, 2, listOf(message(1), message(2)))
        sync.remove(1)
        sync.clear()
        sync.applyHistory(1, 2, listOf(message(1), message(2)))
        assertEquals("a cleared store still honours the deletion", listOf(2L), sync.messages.map { it.id })

        // Reopening the conversation builds a fresh coordinator, which starts from the
        // server's answer again.
        val reopened = MessageSyncCoordinator()
        reopened.mergeIncoming(listOf(message(1), message(2)))
        assertEquals(listOf(1L, 2L), reopened.messages.map { it.id })
    }

    @Test
    fun `a tombstone does not leak into another coordinator`() {
        val first = MessageSyncCoordinator()
        first.remove(5)

        val second = MessageSyncCoordinator()
        second.mergeIncoming(listOf(message(5)))

        assertEquals(1, second.size)
        assertTrue(first.isEmpty)
    }

    @Test
    fun `a newer edit replaces a stored message without counting as new`() {
        val sync = MessageSyncCoordinator()
        sync.applyHistory(1, 1, listOf(message(1, "before")))

        val edited = message(1, "after").copy(editedAtMillis = 5_000L)
        val added = sync.applyHistory(1, 1, listOf(edited))

        assertEquals("an edit is not a new message", 0, added)
        assertEquals("after", sync.messages.single().plainText)
        assertTrue(sync.messages.single().isEdited)
    }

    @Test
    fun `a plain duplicate does not replace the stored message`() {
        val sync = MessageSyncCoordinator()
        sync.applyHistory(1, 1, listOf(message(1, "original")))
        val older = message(1, "stale").copy(editedAtMillis = 0L)

        sync.applyHistory(1, 1, listOf(older))

        assertEquals("original", sync.messages.single().plainText)
    }

    @Test
    fun `a tail window that omits the sent sequence does not move the watermark`() {
        // The send was accepted at 3, but the re-read of 1..3 came back without it. The
        // window end must not be acknowledged: the next poll has to ask for 3 again.
        val sync = MessageSyncCoordinator()
        sync.applyHistory(1, 2, listOf(message(1), message(2)))

        sync.mergeWindow(1, 3, listOf(message(1), message(2)), requirePresent = 3)

        assertEquals(2L, sync.newestKnownSeq)
        val window = sync.planCatchUp(serverNewestSeq = 3)
        assertEquals("the sent sequence must stay requestable", 3L, window?.start)
        assertEquals(3L, window?.endInclusive)
    }

    @Test
    fun `a tail window that contains the sent sequence advances normally`() {
        val sync = MessageSyncCoordinator()
        sync.applyHistory(1, 2, listOf(message(1), message(2)))

        sync.mergeWindow(1, 3, listOf(message(1), message(2), message(3)), requirePresent = 3)

        assertEquals(3L, sync.newestKnownSeq)
        assertNull(sync.planCatchUp(serverNewestSeq = 3))
    }

    @Test
    fun `acknowledging a send advances the watermark`() {
        val sync = MessageSyncCoordinator()
        sync.acknowledgeSend(8)

        assertEquals(8L, sync.newestKnownSeq)
        assertNull(sync.planCatchUp(serverNewestSeq = 8))
    }

    @Test
    fun `an unmerged send watermark keeps the sent sequence requestable`() {
        // This mirrors a send whose tail re-read failed: the message was never merged,
        // so the watermark must not skip it and the next poll has to include it.
        val sync = MessageSyncCoordinator()
        sync.mergeIncoming(listOf(message(1), message(2)))
        assertFalse(sync.contains(3))

        val window = sync.planCatchUp(serverNewestSeq = 3)
        assertEquals(3L, window?.start)
        assertEquals(3L, window?.endInclusive)

        sync.mergeIncoming(listOf(message(3)))
        assertTrue(sync.contains(3))
        sync.acknowledgeSend(3)
        assertNull(sync.planCatchUp(serverNewestSeq = 3))
    }

    @Test
    fun `speculative window is available when the probe route fails`() {
        val sync = MessageSyncCoordinator()
        sync.mergeIncoming(listOf(message(2)))

        val window = sync.speculativePollWindow(maxWindow = 10)
        assertEquals(3L, window.start)
        assertEquals(12L, window.endInclusive)
    }

    @Test
    fun `a probe-less open asks from the first sequence over a wide window`() {
        val sync = MessageSyncCoordinator()

        val window = sync.speculativeHistoryWindow()

        assertEquals(1L, window.start)
        assertEquals(
            MessageSyncCoordinator.SPECULATIVE_HISTORY_WINDOW.toLong(),
            window.endInclusive
        )
        assertTrue("a probe-less window must not be a 0..0 request", window.endInclusive > 30L)
    }

    @Test
    fun `a speculative history window does not claim the top nor the watermark`() {
        val sync = MessageSyncCoordinator()
        val window = sync.speculativeHistoryWindow(windowSize = 40)

        // The server answered, but the range was picked by the client: a short or empty
        // page says nothing about older messages.
        sync.applyHistory(window.start, window.endInclusive, listOf(message(1)), speculative = true)

        assertFalse("a guessed window cannot prove the top", sync.reachedTop)
        assertEquals(
            "the watermark may only follow messages actually returned",
            1L,
            sync.newestKnownSeq
        )
        assertEquals(1L, sync.oldestRequestedSeq)
    }

    @Test
    fun `a bounded window still advances the watermark past an empty page`() {
        // Regression guard: only speculative windows are denied the watermark.
        val sync = MessageSyncCoordinator()
        sync.applyHistory(31, 60, emptyList())

        assertEquals(60L, sync.newestKnownSeq)
    }

    @Test
    fun `acknowledging a fetched window advances only to its end`() {
        val sync = MessageSyncCoordinator()
        sync.mergeIncoming(listOf(message(1)))

        // A backlog far larger than one window: the server newest is 200, but only
        // 2..31 was actually requested and merged.
        val window = sync.planCatchUp(serverNewestSeq = 200)!!
        assertEquals(2L, window.start)
        assertEquals(31L, window.endInclusive)

        sync.acknowledgeFetchedWindow(window.start, window.endInclusive)
        assertEquals(31L, sync.newestKnownSeq)

        // The next window must continue at 32, not jump to the server's newest.
        val next = sync.planCatchUp(serverNewestSeq = 200)!!
        assertEquals(32L, next.start)
        assertEquals(61L, next.endInclusive)
    }

    @Test
    fun `acknowledging a window never moves the watermark backwards`() {
        val sync = MessageSyncCoordinator()
        sync.acknowledgeSend(50)

        sync.acknowledgeFetchedWindow(1, 10)

        assertEquals(50L, sync.newestKnownSeq)
    }

    @Test
    fun `clear resets every watermark`() {
        val sync = MessageSyncCoordinator()
        sync.applyHistory(1, 2, listOf(message(1)))
        sync.clear()

        assertEquals(0, sync.size)
        assertEquals(MessageSyncCoordinator.NO_SEQUENCE, sync.newestKnownSeq)
        assertEquals(MessageSyncCoordinator.NO_SEQUENCE, sync.oldestRequestedSeq)
        assertFalse(sync.reachedTop)
    }
}
