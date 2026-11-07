package dev.oxype.client.ui.chat

import dev.oxype.client.data.oxype.ChatMessage
import dev.oxype.client.data.oxype.ReplyQuote
import dev.oxype.client.data.oxype.SessionMember

/**
 * What a quote block shows.
 *
 * Plain values so the fallback chain is testable without Compose. The quoted snapshot
 * is written by the server and its field spelling cannot be verified from here, so each
 * piece may be missing; whatever is missing is filled from data this client already has.
 */
data class QuoteView(
    /** Name shown on the quote's first line. Never blank. */
    val author: String,
    /** Quoted body. Empty means "draw no body line", not "draw an empty line". */
    val body: String,
    val targetMessageId: Long,
    /** True when the original is in the loaded window and can be scrolled to. */
    val isJumpable: Boolean
)

/**
 * Name on the quote's first line.
 *
 * Quoting my own message says `我` even when the server wrote my display name into the
 * snapshot: the block answers "who am I replying to", and the bubble's own side already
 * says the message is mine. Every other sender keeps the snapshot name, because Oxype
 * freezes it at send time and a later rename must not rewrite history.
 */
internal fun quoteAuthorName(
    quote: ReplyQuote,
    participants: Map<Long, SessionMember>,
    currentUserId: Long
): String {
    val senderId = quote.senderId
    if (senderId != null && senderId > 0L && senderId == currentUserId) return SELF_SENDER_NAME
    val fromSnapshot = quote.senderName?.trim()
    if (!fromSnapshot.isNullOrEmpty()) return fromSnapshot
    if (senderId != null && senderId > 0L) {
        val fromDirectory = participants[senderId]?.username?.trim()
        if (!fromDirectory.isNullOrEmpty()) return fromDirectory
        return "$UNKNOWN_SENDER_NAME $senderId"
    }
    return UNKNOWN_QUOTE_AUTHOR
}

/**
 * Quoted body.
 *
 * The snapshot is preferred: Oxype freezes the wording at send time, so an edit to the
 * original must not change what the reply claims to quote. The local copy only fills a
 * missing snapshot.
 */
internal fun quoteBodyText(quote: ReplyQuote, local: ChatMessage?): String {
    val fromSnapshot = quote.text?.trim()
    if (!fromSnapshot.isNullOrEmpty()) return fromSnapshot
    return local?.plainText?.trim().orEmpty()
}

/** Resolves everything a quote block needs, with no Compose and no network. */
internal fun resolveQuote(
    quote: ReplyQuote,
    participants: Map<Long, SessionMember>,
    loaded: Map<Long, ChatMessage>,
    currentUserId: Long
): QuoteView = QuoteView(
    author = quoteAuthorName(quote, participants, currentUserId),
    body = quoteBodyText(quote, loaded[quote.messageId]),
    targetMessageId = quote.messageId,
    isJumpable = loaded.containsKey(quote.messageId)
)

/**
 * How the quote block labels its own author.
 *
 * Unlike the sender label above a bubble — which is hidden for my own messages because
 * the bubble's side already says who wrote it — the quote always needs a name: it is
 * answering "who am I replying to", and a nameless first line reads as a missing value.
 */
private const val SELF_SENDER_NAME: String = "我"

/** Used when the snapshot has neither a name nor a sender id to look up. */
internal const val UNKNOWN_QUOTE_AUTHOR: String = "未知用户"
