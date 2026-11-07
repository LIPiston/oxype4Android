package dev.oxype.client.data.oxype

/** Credentials accepted by Oxype: every authenticated call carries this pair. */
data class AuthCredentials(
    val userId: Long,
    val token: String
)

enum class MessagePieceKind {
    TEXT,
    MARKDOWN,

    /**
     * A quote snapshot. It is recognized so the parser can pull it out of `pieces`, but
     * it never becomes a [MessagePiece]: the quote belongs to the message, not to its
     * body, and joining it into the body would duplicate the quoted text.
     */
    REPLY
}

/** One piece of a message body. Oxype defines `text`, `markdown` and `reply`. */
data class MessagePiece(
    val kind: MessagePieceKind,
    val text: String
)

/**
 * The quoted message frozen at send time.
 *
 * Oxype stores a *snapshot*: the sender, name and text are copied by the server when the
 * reply is sent, so they survive an edit or deletion of the original. Only [messageId] is
 * guaranteed — the client has no way to verify the spelling of the rest — so the others
 * are nullable and the UI falls back to whatever it already has loaded.
 */
data class ReplyQuote(
    val messageId: Long,
    val senderId: Long? = null,
    val senderName: String? = null,
    val text: String? = null
)

/** A message as stored by the server; `id` is the in-session sequence number. */
data class ChatMessage(
    val id: Long,
    val senderId: Long,
    val timestampMillis: Long,
    val editedAtMillis: Long,
    val pieces: List<MessagePiece>,
    /** Non-null when this message replies to another one; see [ReplyQuote]. */
    val reply: ReplyQuote? = null
) {
    val plainText: String
        get() = pieces.joinToString(separator = "") { it.text }

    val isEdited: Boolean
        get() = editedAtMillis > 0L

    /**
     * A record this client can render. The server never sends an id below 1 and an
     * empty body has nothing to show, so both are dropped instead of being painted
     * as a blank bubble.
     */
    val isUsable: Boolean
        get() = id > 0L && pieces.isNotEmpty()
}

data class LastMessagePreview(
    val text: String,
    val senderName: String?,
    val timestampMillis: Long
)

data class SessionSummary(
    val sessionId: Long,
    val name: String,
    val description: String,
    val ownerId: Long,
    val memberIds: List<Long>,
    val adminIds: List<Long>,
    val lastMessage: LastMessagePreview? = null
) {
    val memberCount: Int
        get() = (memberIds + ownerId).filter { it > 0L }.distinct().size

    /**
     * True when the row said something about membership.
     *
     * Some batch answers abbreviate a session to just `{ sessionId, name }`. Rendering
     * that as "0 位成员" misrepresents a real conversation, so such a row is completed
     * from the detail route instead of being trusted as-is.
     */
    val isComplete: Boolean
        get() = ownerId > 0L || memberIds.isNotEmpty()
}

data class SessionMember(
    val userId: Long,
    val username: String,
    /**
     * Optional avatar source.
     *
     * Oxype does not guarantee a profile image: the field may be absent, blank, or
     * spelled several ways. `null` means "nothing to load" and the UI draws a coloured
     * initial instead of an empty frame.
     */
    val avatarUrl: String? = null
)

data class SessionDetail(
    val sessionId: Long,
    val name: String,
    val description: String,
    val ownerId: Long,
    val isOwner: Boolean,
    val isAdmin: Boolean,
    val members: List<SessionMember>,
    /**
     * User ids the server listed as admins.
     *
     * Historically only the batch route reported this; the detail routes were not read
     * for it, so it defaults to empty rather than pretending the list is complete.
     */
    val adminIds: List<Long> = emptyList(),
    /**
     * A session-level avatar, distinct from the members' own.
     *
     * Optional like every other avatar on Oxype; `null` means the page draws the
     * session name's initial instead.
     */
    val avatarUrl: String? = null
) {
    val memberCount: Int
        get() = members.size

    /**
     * True when the payload said something about membership.
     *
     * A server that abbreviates the session view answers name-only (owner 0, no
     * members); rendering that would show a populated conversation as "0 位成员", so
     * such an answer is treated as unusable and another route is tried.
     */
    val isComplete: Boolean
        get() = ownerId > 0L || members.isNotEmpty()
}

data class CreatedSession(
    val sessionId: Long,
    val name: String,
    val inviteUuid: String
)

/**
 * The caller's own account, as the server describes it.
 *
 * Oxype keeps three things about a person: the id, the name, and an avatar. There is no
 * phone number, birthday, bio or presence field, so nothing else is modelled here.
 *
 * [avatarUrl] is already resolved against the configured server (or `null` when the
 * account has no picture). The upload rules ride along because the server enforces them
 * and the client wants to reject a bad picture before sending it.
 */
data class UserProfile(
    val userId: Long,
    val username: String,
    val avatarUrl: String? = null,
    val hasAvatar: Boolean = false,
    val maxBytes: Long = DEFAULT_AVATAR_MAX_BYTES,
    val maxEdge: Int = DEFAULT_AVATAR_MAX_EDGE,
    val formats: List<String> = DEFAULT_AVATAR_FORMATS
) {
    companion object {
        /**
         * The server's own limits, mirrored as the fallback for an answer that omits them.
         *
         * They match `AvatarStorage` upstream: 3 MB after decoding and 4096 px per edge.
         * A client-side default that was stricter would refuse pictures the server
         * accepts, and a looser one would only move the rejection to the network.
         */
        const val DEFAULT_AVATAR_MAX_BYTES: Long = 3L * 1024 * 1024
        const val DEFAULT_AVATAR_MAX_EDGE: Int = 4096
        val DEFAULT_AVATAR_FORMATS: List<String> =
            listOf("image/png", "image/jpeg", "image/webp", "image/gif")
    }
}

/**
 * A member's role in a group, decided once from ids.
 *
 * The header badge ("我是群主/我是管理员") and the per-member labels are two renderings
 * of the same answer, so they are computed by one function ([GroupInfo.roleOf]) instead
 * of each re-reading `ownerId`/`adminIds` with its own rules.
 */
enum class GroupRole { OWNER, ADMIN, MEMBER }

/**
 * Everything the group info page needs, already merged and already decided.
 *
 * The screen renders this without a second lookup and without judging who is owner or
 * admin: [ownerId] and [adminIds] are normalised once by the repository (a boolean-only
 * answer is folded into ids there), so the header badge and the member row labels can
 * never contradict each other.
 */
data class GroupInfo(
    val sessionId: Long,
    val name: String,
    val description: String,
    val ownerId: Long,
    val adminIds: List<Long>,
    val members: List<SessionMember>,
    val avatarUrl: String?,
    val isOwner: Boolean,
    val isAdmin: Boolean
) {
    val memberCount: Int
        get() = members.size

    /** [userId]'s label in the roster. Owner wins over admin, as the badge does. */
    fun roleOf(userId: Long): GroupRole = when {
        ownerId > 0L && userId == ownerId -> GroupRole.OWNER
        adminIds.contains(userId) -> GroupRole.ADMIN
        else -> GroupRole.MEMBER
    }
}

data class LastMessageInfo(
    val hasMessage: Boolean,
    val id: Long,
    val senderId: Long,
    val senderName: String?,
    val timestampMillis: Long,
    val content: String
)

/**
 * Outcome of the `/lastMessage` probe.
 *
 * The three cases must not be conflated: "the chat really has no message" lets polling
 * skip the request entirely, "there is a newest sequence" drives a gap-free catch-up,
 * and "the answer cannot be read" must fall back to a speculative window. Treating the
 * unreadable case as empty would make a server that changed its payload look like a
 * conversation that went silent forever.
 */
sealed interface LastMessageResult {
    /** The server explicitly answered `hasMessage = false`. */
    data object Empty : LastMessageResult

    /** The server answered with a usable newest message. */
    data class Present(val info: LastMessageInfo) : LastMessageResult

    /** The payload was missing, incomplete, or not an object. */
    data object Unknown : LastMessageResult
}

enum class OxypeErrorKind {
    /** The server could not be reached, or the socket timed out. */
    NETWORK,

    /** 401: the token is missing, expired, or revoked. */
    UNAUTHORIZED,

    /** 403: authenticated but not allowed to touch this resource. */
    FORBIDDEN,

    /** 400: the request itself was rejected (bad field, bad invite, weak password). */
    BAD_REQUEST,

    /** 404: unknown session, message, or user. */
    NOT_FOUND,

    /** 5xx: the server failed, the client did nothing wrong. */
    SERVER,

    /** The response could not be interpreted as the Oxype protocol. */
    PROTOCOL,

    /**
     * Oxype answers `{"seq": -1}` (or `{"success": false}`) when the caller is not a
     * member of the session. It is a soft refusal, not a transport failure.
     */
    NOT_JOINED
}

class OxypeException(
    val kind: OxypeErrorKind,
    message: String,
    cause: Throwable? = null,
    val httpStatus: Int? = null
) : Exception(message, cause)
