package dev.oxype.client.data.sync

import dev.oxype.client.data.oxype.ChatMessage
import dev.oxype.client.data.oxype.LastMessageResult

/**
 * The message store and paging rules for one conversation.
 *
 * Kept as a plain, non-Android class so the ordering, inclusive-range and dedup
 * behaviour can be unit-tested directly:
 *
 * - ranges are inclusive on both ends (Oxype walks `start..end`),
 * - a sequence number may be absent because it was deleted, so a short page is normal,
 * - the same message can arrive twice (history fetch, send echo, later poll) and must
 *   be stored once.
 */
class MessageSyncCoordinator {

    private val messagesById = LinkedHashMap<Long, ChatMessage>()

    /**
     * Ids the server confirmed as deleted for this conversation.
     *
     * A deletion is authoritative, but a history page, poll or send tail that was
     * already in flight was composed before it and still carries the record. Remembering
     * the id forever (for this coordinator's lifetime, which is one open conversation)
     * is what stops a deleted bubble from reappearing behind the user's back.
     */
    private val tombstones = HashSet<Long>()

    /** Lowest sequence this coordinator has ever requested, or [NO_SEQUENCE]. */
    var oldestRequestedSeq: Long = NO_SEQUENCE
        private set

    /** Highest sequence known to exist (a hint; it may point at a deleted slot). */
    var newestKnownSeq: Long = NO_SEQUENCE
        private set

    var reachedTop: Boolean = false
        private set

    /** Messages in ascending sequence order; the UI reverses for display. */
    val messages: List<ChatMessage>
        get() = messagesById.values.sortedBy { it.id }

    val size: Int
        get() = messagesById.size

    val isEmpty: Boolean
        get() = messagesById.isEmpty()

    fun newestMessage(): ChatMessage? = messagesById.values.maxByOrNull { it.id }

    /** True when [messageId] is currently rendered. */
    fun contains(messageId: Long): Boolean = messagesById.containsKey(messageId)

    fun oldestMessage(): ChatMessage? = messagesById.values.minByOrNull { it.id }

    /**
     * Merges a fetched history page.
     *
     * [requestedStart] is remembered even when the page is empty: paging counts back
     * from the boundary that was asked for, so a hole at the top of the requested
     * window cannot make the client loop over the same range forever.
     *
     * [speculative] marks a window the client picked without asking the server where the
     * newest message is. Its start is a guess, so an answer — including an empty one —
     * proves nothing about older history; the top stays unproven until a real window
     * (or [nextHistoryWindow]) reaches sequence 1.
     *
     * Returns the number of genuinely new messages (duplicates are silently dropped).
     */
    fun applyHistory(
        requestedStart: Long,
        requestedEnd: Long,
        page: List<ChatMessage>,
        speculative: Boolean = false
    ): Int {
        val added = mergeRecords(page)
        if (requestedStart >= 0L) {
            oldestRequestedSeq = if (oldestRequestedSeq == NO_SEQUENCE) {
                requestedStart
            } else {
                minOf(oldestRequestedSeq, requestedStart)
            }
        }
        if (requestedEnd >= 1L && !speculative) {
            // Only a server-bounded window may move the watermark to its end. The end of
            // a speculative window is a guess; it can sit above every existing message,
            // and treating it as "everything up to here is known" would make the next
            // catch-up start past messages that do not exist yet.
            newestKnownSeq = maxOf(newestKnownSeq, requestedEnd)
        }
        if (speculative) {
            // The watermark may follow only what the server actually returned.
            for (message in page) {
                if (message.isUsable) newestKnownSeq = maxOf(newestKnownSeq, message.id)
            }
        }
        // A window that began at 0 or 1 has no older sequence to ask for. Treating it as
        // the top immediately avoids a pointless extra request that would return nothing.
        // A speculative window's start was not the server's answer, so the same rule does
        // not apply to it.
        if (!speculative && oldestRequestedSeq != NO_SEQUENCE && oldestRequestedSeq <= 1L) {
            reachedTop = true
        }
        return added
    }

    /**
     * Merges a page that answered the inclusive window `start..endInclusive`, moving the
     * watermark only when the window actually connects to what is already known.
     *
     * Used for reads that come from *below* — a send echo, a catch-up — where the window
     * start was chosen by the client rather than by the server's newest sequence. A send
     * can be assigned a sequence far above the local watermark (messages were posted by
     * others meanwhile); claiming the unfetched middle as processed would make every
     * later catch-up start past it, silently skipping that range forever.
     *
     * [requirePresent] names a sequence that must actually be in the answer before the
     * window may count as processed. A tail re-read around a freshly sent message is the
     * case: the server accepted the send but has not necessarily made that record
     * visible yet. Acknowledging the window then would move the watermark above the sent
     * sequence and the next poll would never ask for it again.
     *
     * The records themselves are always stored: the message really exists, it is only
     * the "everything below is known" claim that is withheld.
     */
    fun mergeWindow(
        start: Long,
        endInclusive: Long,
        page: List<ChatMessage>,
        requirePresent: Long? = null
    ): Int {
        val added = mergeRecords(page)
        if (requirePresent != null && !messagesById.containsKey(requirePresent)) return added
        if (start < 1L || endInclusive < start) return added
        if (isContiguousWithWatermark(start)) {
            newestKnownSeq = maxOf(newestKnownSeq, endInclusive)
        }
        return added
    }

    /**
     * True when `start..` sits directly on top of the highest sequence already processed.
     *
     * A request that starts above `newestKnownSeq + 1` leaves an unfetched middle, so it
     * may not be treated as a gap-free prefix.
     */
    fun isContiguousWithWatermark(start: Long): Boolean =
        (newestKnownSeq == NO_SEQUENCE && start <= 1L) ||
            (newestKnownSeq != NO_SEQUENCE && start <= newestKnownSeq + 1L)

    private fun mergeRecords(page: List<ChatMessage>): Int {
        var added = 0
        for (message in page) {
            if (!message.isUsable) continue
            // A confirmed deletion wins over any stale copy still travelling.
            if (tombstones.contains(message.id)) continue
            val known = messagesById[message.id]
            if (known != null) {
                // A duplicate normally adds nothing, but an edited message (or a record
                // that gained an authoritative timestamp) carries newer content and has
                // to replace the stored one instead of being dropped.
                if (isNewerRevision(message, known)) messagesById[message.id] = message
                continue
            }
            messagesById[message.id] = message
            added++
        }
        return added
    }

    /**
     * True when [incoming] supersedes [stored] rather than repeating it.
     *
     * The only revision signal Oxype sends is `editedAt`: a later edit must be visible,
     * while a plain duplicate (same or older `editedAt`) must not count as new.
     */
    private fun isNewerRevision(incoming: ChatMessage, stored: ChatMessage): Boolean =
        incoming.editedAtMillis > stored.editedAtMillis

    /**
     * Merges messages discovered by polling, where no explicit window was requested.
     * Returns the number of new messages.
     */
    fun mergeIncoming(incoming: List<ChatMessage>): Int {
        var added = 0
        for (message in incoming) {
            if (!message.isUsable) continue
            // A deleted id must not come back through a poll, and it must not move the
            // watermark either: the sequence exists but has nothing to show.
            if (tombstones.contains(message.id)) continue
            val known = messagesById[message.id]
            if (known != null) {
                if (isNewerRevision(message, known)) messagesById[message.id] = message
                continue
            }
            messagesById[message.id] = message
            newestKnownSeq = maxOf(newestKnownSeq, message.id)
            added++
        }
        return added
    }

    /**
     * Records that a send was accepted at [seq], after the message has been fetched or
     * echoed locally. Advancing the watermark here prevents the next poll from asking
     * for a message this client already has.
     *
     * Callers must only use this when the sent sequence connects to what is already
     * known — see [isContiguousWithWatermark]. A send proves its own sequence exists,
     * not that the ones below it do.
     */
    fun acknowledgeSend(seq: Long) {
        if (seq >= 1L) {
            newestKnownSeq = maxOf(newestKnownSeq, seq)
        }
    }

    /**
     * Records that the inclusive window `start..endInclusive` has been fetched.
     *
     * Only the window's own end may move the watermark. `/lastMessage` reports the
     * newest sequence on the server, which can be far above what one bounded request
     * covered; using that value directly would make the next poll start past the
     * unfetched middle of a backlog and skip it forever. Deleted slots make a short
     * page normal, so a successful answer for the whole window counts as processed and
     * the watermark still advances to its end (otherwise an empty window would be
     * re-requested until the newest message arrived).
     */
    fun acknowledgeFetchedWindow(start: Long, endInclusive: Long) {
        if (start >= 1L && endInclusive >= start) {
            newestKnownSeq = maxOf(newestKnownSeq, endInclusive)
        }
    }

    /**
     * The catch-up window for one poll, or `null` when nothing new exists.
     *
     * [serverNewestSeq] comes from `/lastMessage`, which reports the newest *surviving*
     * message. Comparing it with the local watermark is what makes polling quiet when
     * the conversation is idle: only a genuinely newer sequence causes a fetch, and
     * only the missing range is requested (inclusive on both ends, capped at
     * [maxBatch]). A `null` or non-positive [serverNewestSeq] is a soft answer and
     * yields no work.
     */
    fun planCatchUp(
        serverNewestSeq: Long?,
        maxBatch: Int = DEFAULT_POLL_WINDOW
    ): ClosedRange<Long>? {
        val newest = serverNewestSeq ?: return null
        if (newest < 1L) return null
        val start = if (newestKnownSeq == NO_SEQUENCE) 1L else newestKnownSeq + 1L
        if (start > newest) return null
        val end = minOf(newest, start + maxBatch.coerceAtLeast(1) - 1L)
        return start..end
    }

    /**
     * Fallback window for a server that cannot answer `/lastMessage`: asks for a fixed
     * block above the watermark. Used only when the probe route is unavailable.
     */
    fun speculativePollWindow(maxWindow: Int = DEFAULT_POLL_WINDOW): ClosedRange<Long> {
        val from = if (newestKnownSeq == NO_SEQUENCE) 0L else newestKnownSeq
        val start = from + 1L
        return start..(start + maxWindow.coerceAtLeast(1) - 1L)
    }

    /**
     * The first history window when neither probe could say where the newest message is.
     *
     * Sequence numbers start at 1, so that is the only bound the client actually knows;
     * asking for `0..0` would be a request the server can only answer with nothing and
     * would then be mistaken for an empty conversation. The window is deliberately
     * larger than one page and stays speculative: an empty or short answer proves
     * nothing about older or newer history, so [reachedTop] must stay unproven until a
     * normal window — or [nextHistoryWindow] — reaches the start of the sequence space.
     */
    fun speculativeHistoryWindow(
        windowSize: Int = SPECULATIVE_HISTORY_WINDOW
    ): ClosedRange<Long> = 1L..windowSize.coerceAtLeast(DEFAULT_HISTORY_PAGE).toLong()

    /** The next older history window ending just below the oldest requested seq. */
    fun nextHistoryWindow(pageSize: Int = DEFAULT_HISTORY_PAGE): ClosedRange<Long>? {
        if (reachedTop) return null
        val boundary = oldestRequestedSeq
        if (boundary == NO_SEQUENCE) return null
        if (boundary <= 1L) {
            reachedTop = true
            return null
        }
        val end = boundary - 1L
        val start = (end - pageSize.coerceAtLeast(1) + 1L).coerceAtLeast(0L)
        return start..end
    }

    /**
     * Drops a message after the server confirmed its deletion, and remembers the id.
     *
     * The tombstone is what makes the deletion stick: pages and polls composed before the
     * confirmation can still arrive afterwards and would otherwise recreate the row.
     * It lives as long as this coordinator, which is exactly one open conversation, so
     * reopening the chat starts from the server's answer again.
     */
    fun remove(messageId: Long): Boolean {
        tombstones.add(messageId)
        return messagesById.remove(messageId) != null
    }

    fun clear() {
        messagesById.clear()
        // Tombstones are deliberately kept: a confirmed deletion is still true for this
        // conversation, and forgetting it would let the next merge recreate the row. Only
        // a new coordinator (a reopened chat) starts without them.
        oldestRequestedSeq = NO_SEQUENCE
        newestKnownSeq = NO_SEQUENCE
        reachedTop = false
    }

    companion object {
        const val NO_SEQUENCE: Long = -1L
        const val DEFAULT_HISTORY_PAGE: Int = 30
        const val DEFAULT_POLL_WINDOW: Int = 30

        /**
         * Size of the first window when the probes cannot anchor the transcript.
         *
         * Wide enough to cover a conversation with recent history in one request, which
         * is what a normal chat looks like; a longer one pages down from this window's
         * start as usual.
         */
        const val SPECULATIVE_HISTORY_WINDOW: Int = 100

        /**
         * Converts a `/lastMessage` answer into the newest surviving sequence.
         *
         * An empty or unreadable answer carries no sequence and must not move the
         * watermark backwards. The empty/unreadable distinction itself belongs to
         * [dev.oxype.client.data.oxype.LastMessageResult], which callers use to decide
         * between "nothing to fetch" and a speculative window.
         */
        fun newestFromLastMessage(result: LastMessageResult?): Long? =
            (result as? LastMessageResult.Present)?.info?.id?.takeIf { it >= 1L }
    }
}
