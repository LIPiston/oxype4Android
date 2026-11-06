package dev.oxype.client.ui.chat

import dev.oxype.client.data.oxype.ChatMessage
import dev.oxype.client.data.oxype.SessionMember

/**
 * Presentation rules for who wrote a message.
 *
 * These are plain functions so the fallbacks are unit-testable without Compose: the
 * server may omit the member directory, omit a name, or send no avatar at all.
 */

/** Placeholder shown when a sender has neither a name in the directory nor a usable id. */
const val UNKNOWN_SENDER_NAME: String = "用户"

/** Name to paint above an incoming bubble in a group conversation. */
internal fun senderDisplayName(
    senderId: Long,
    participants: Map<Long, SessionMember>
): String {
    val fromDirectory = participants[senderId]?.username?.trim()
    if (!fromDirectory.isNullOrEmpty()) return fromDirectory
    return if (senderId > 0L) "$UNKNOWN_SENDER_NAME $senderId" else UNKNOWN_SENDER_NAME
}

/**
 * First visible character of a name, used inside the avatar disc.
 *
 * The first *code point* is taken rather than the first `Char`, so a character outside
 * the BMP (an emoji, some CJK extensions) is not cut in half into an unprintable
 * surrogate. An empty or blank name falls back to [UNKNOWN_SENDER_NAME]'s first point.
 */
internal fun avatarInitial(name: String?): String {
    val trimmed = name?.trim()
    if (trimmed.isNullOrEmpty()) return UNKNOWN_SENDER_NAME.substring(0, 1)
    val firstCodePoint = trimmed.codePointAt(0)
    return String(Character.toChars(firstCodePoint))
}

/**
 * Stable palette slot for a name.
 *
 * The same sender always gets the same colour across recompositions and app launches,
 * without storing anything; [paletteSize] is clamped so a degenerate value cannot
 * produce an out-of-range index.
 */
internal fun avatarColorIndex(name: String?, paletteSize: Int): Int {
    if (paletteSize <= 1) return 0
    val key = name?.trim().orEmpty()
    // A tiny deterministic hash; `String.hashCode` is stable across JVMs but could be
    // negative, so the absolute value keeps the index non-negative.
    var hash = 0
    for (char in key) {
        hash = hash * 31 + char.code
    }
    return Math.floorMod(hash, paletteSize)
}

/**
 * Whether the conversation has more than one other account in it.
 *
 * A one-to-one chat keeps the compact layout (no avatar column, no name line); a group
 * shows the sender's avatar and name. A directory with a single other member is a
 * one-to-one chat, so "group" means at least two *other* accounts. The directory can
 * be missing, so the transcript is inspected as a second signal.
 */
internal fun isGroupConversation(
    participants: Map<Long, SessionMember>,
    messages: List<ChatMessage>,
    currentUserId: Long
): Boolean {
    val othersInDirectory = participants.keys.count { it > 0L && it != currentUserId }
    if (othersInDirectory >= 2) return true
    val otherSenders = messages.asSequence()
        .map { it.senderId }
        .filter { it > 0L && it != currentUserId }
        .distinct()
        .take(2)
        .count()
    return otherSenders >= 2
}
