package dev.oxype.client.data.oxype

import org.json.JSONArray
import org.json.JSONObject

/**
 * Typed route access over [OxypeHttpClient].
 *
 * Every Oxype route string and every field spelling lives here. When the upstream
 * service renames something, this class is the only thing that has to change.
 */
class OxypeApi(private val http: OxypeHttpClient) {

    /** `POST /register` returns `{ userid, token }` and logs the new account in. */
    suspend fun register(username: String, password: String): AuthCredentials {
        val payload = http.postJson(
            path = "register",
            body = JSONObject().apply {
                put("username", username)
                put("password", password)
            }
        )
        val json = payload as? JSONObject
            ?: throw OxypeException(OxypeErrorKind.PROTOCOL, "注册响应缺少账户信息")
        val userId = OxypeJson.long(json, "userid", "userId", "id")
            ?: throw OxypeException(OxypeErrorKind.PROTOCOL, "注册响应缺少用户编号")
        val token = OxypeJson.string(json, "token")
            ?.takeIf { it.isNotBlank() }
            ?: throw OxypeException(OxypeErrorKind.PROTOCOL, "注册响应缺少令牌")
        return AuthCredentials(userId, token)
    }

    /** `POST /login/{userid}` accepts only the password and answers `{ token }`. */
    suspend fun login(userId: Long, password: String): AuthCredentials {
        val payload = http.postJson(
            path = "login/$userId",
            body = JSONObject().apply { put("password", password) }
        )
        val json = payload as? JSONObject
            ?: throw OxypeException(OxypeErrorKind.PROTOCOL, "登录响应缺少令牌")
        val token = OxypeJson.string(json, "token")
            ?.takeIf { it.isNotBlank() }
            ?: throw OxypeException(OxypeErrorKind.PROTOCOL, "登录响应缺少令牌")
        return AuthCredentials(userId, token)
    }

    /**
     * `POST /isAlive/{userid}`.
     *
     * A transport or server-side failure must not log the user out, so only the
     * explicit boolean answer is reported; everything else is thrown and the caller
     * decides. A 401 is thrown as [OxypeErrorKind.UNAUTHORIZED]; a 403 means the caller
     * is authenticated but not allowed, so it says nothing about token validity.
     */
    suspend fun isAlive(credentials: AuthCredentials): Boolean {
        val payload = http.postJson(
            path = "isAlive/${credentials.userId}",
            body = JSONObject().apply { put("token", credentials.token) }
        )
        if (payload is Boolean) return payload
        val json = payload as? JSONObject ?: return true
        return OxypeJson.boolean(json, "is_alive", "isAlive", "alive", "valid") ?: true
    }

    /** `POST /logout/{userid}`; the server answers `{ userid, loggedOut }`. */
    suspend fun logout(credentials: AuthCredentials) {
        http.postJson(
            path = "logout/${credentials.userId}",
            body = JSONObject().apply { put("token", credentials.token) }
        )
    }

    /** `GET /getJoinedSessions/{userid}` answers `{ sessions: [...] }` under `data`. */
    suspend fun joinedSessionIds(credentials: AuthCredentials): List<Long> {
        val payload = http.get(
            path = "getJoinedSessions/${credentials.userId}",
            credentials = credentials
        )
        return OxypeJson.longList(OxypeJson.arrayOf(payload, "sessions", "list"))
    }

    /**
     * `POST /getSessions` with `{ sessions: [id, ...] }`, answering an array of
     * session records. Callers pass the ids from [joinedSessionIds].
     */
    suspend fun sessionsByIds(sessionIds: List<Long>): List<SessionSummary> {
        if (sessionIds.isEmpty()) return emptyList()
        val payload = http.postJson(
            path = "getSessions",
            body = JSONObject().apply {
                put("sessions", JSONArray().apply { sessionIds.forEach { put(it) } })
            }
        )
        return parseSessions(payload)
    }

    /**
     * Resolves session details for a list of ids.
     *
     * The batch route is tried first, but its rows are not always complete: some
     * deployments omit `members`/`admins`, and others spell them `memberIds`/`adminIds`
     * (both are read by [parseSessions]). A row that carries no owner and no membership
     * at all is completed from the per-session detail route, and if that lookup fails the
     * row is still kept — a conversation whose name is known must not disappear from the
     * list because a supplementary call failed.
     *
     * Failures are per-session — one stale id must not wipe the whole list — but an error
     * that affects *every* id is a network or protocol problem and is thrown so the UI can
     * show it instead of a silently empty list.
     */
    suspend fun sessionDetails(
        credentials: AuthCredentials,
        sessionIds: List<Long>
    ): List<SessionSummary> {
        if (sessionIds.isEmpty()) return emptyList()
        val batched = try {
            sessionsByIds(sessionIds)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val byId = batched?.associateBy { it.sessionId }.orEmpty()

        val collected = ArrayList<SessionSummary>(sessionIds.size)
        var firstFailure: OxypeException? = null
        for (sessionId in sessionIds) {
            val row = byId[sessionId]
            if (row != null && row.isComplete) {
                collected.add(row)
                continue
            }
            val detail = try {
                sessionInfo(credentials, sessionId)
            } catch (e: OxypeException) {
                // A rejected token is not a per-session problem: it must reach the caller
                // so the app can sign out instead of showing a half-loaded list. A 403 on
                // one session can legitimately be a per-session permission answer, so it
                // stays a per-session skip.
                if (e.kind == OxypeErrorKind.UNAUTHORIZED) throw e
                if (firstFailure == null) firstFailure = e
                null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (firstFailure == null) {
                    firstFailure = OxypeException(OxypeErrorKind.NETWORK, "无法读取会话信息", e)
                }
                null
            }
            when {
                detail != null -> collected.add(
                    SessionSummary(
                        sessionId = detail.sessionId,
                        name = detail.name,
                        description = detail.description,
                        ownerId = detail.ownerId,
                        memberIds = detail.members.map { it.userId },
                        // The detail route now reports admins too; prefer its answer and
                        // keep the batch row's list only when the detail omitted it.
                        adminIds = detail.adminIds.ifEmpty { row?.adminIds.orEmpty() }
                    )
                )

                // Keep the abbreviated row: its name is still worth showing.
                row != null -> collected.add(row)
            }
        }
        if (collected.isEmpty() && firstFailure != null) throw firstFailure
        return collected
    }

    /**
     * Session details for one conversation.
     *
     * `POST /getSessionInfo/{sessionId}` is the route that answers the full session
     * view (name, owner and member list), so it is tried first. `GET /getSession/{id}`
     * stays as a compatibility fallback for servers that only expose it, but its payload
     * must be complete: a short list answer that reports owner 0 and no members would
     * otherwise be rendered as "0 位成员" for a session that does have members.
     */
    suspend fun sessionInfo(credentials: AuthCredentials, sessionId: Long): SessionDetail {
        var routeFailure: OxypeException? = null
        val viaPost = try {
            http.postJson(
                path = "getSessionInfo/$sessionId",
                body = JSONObject(),
                credentials = credentials
            )
        } catch (e: OxypeException) {
            if (e.kind == OxypeErrorKind.UNAUTHORIZED) throw e
            routeFailure = e
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            routeFailure = OxypeException(OxypeErrorKind.NETWORK, "无法读取会话信息", e)
            null
        }

        val postDetail = viaPost?.let { payload ->
            try {
                parseSessionDetail(payload, sessionId)
            } catch (e: OxypeException) {
                null
            }
        }
        if (postDetail != null && postDetail.isComplete) return postDetail

        val viaGet = try {
            http.get(path = "getSession/$sessionId", credentials = credentials)
        } catch (e: OxypeException) {
            if (e.kind == OxypeErrorKind.UNAUTHORIZED) throw e
            if (routeFailure == null) routeFailure = e
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (routeFailure == null) {
                routeFailure = OxypeException(OxypeErrorKind.NETWORK, "无法读取会话信息", e)
            }
            null
        }
        if (viaGet != null) {
            val getDetail = try {
                parseSessionDetail(viaGet, sessionId)
            } catch (e: OxypeException) {
                null
            }
            if (getDetail != null && getDetail.isComplete) return getDetail
        }

        // Both routes were tried. A transport/server error is more useful to the UI than
        // "incomplete", so it is preserved; only a genuine short-but-valid answer from
        // both routes becomes the explicit incompleteness message.
        throw routeFailure ?: OxypeException(
            OxypeErrorKind.PROTOCOL,
            "会话信息不完整：服务器没有返回成员或所有者"
        )
    }

    /** `POST /createSession` with `{ name, description }`. */
    suspend fun createSession(
        credentials: AuthCredentials,
        name: String,
        description: String
    ): CreatedSession {
        val payload = http.postJson(
            path = "createSession",
            body = JSONObject().apply {
                put("name", name)
                put("description", description)
            },
            credentials = credentials
        )
        val json = payload as? JSONObject
            ?: throw OxypeException(OxypeErrorKind.PROTOCOL, "创建会话的响应格式不正确")
        val sessionId = OxypeJson.long(json, "session_id", "sessionId", "id")
            ?: throw OxypeException(OxypeErrorKind.PROTOCOL, "创建会话的响应缺少会话编号")
        return CreatedSession(
            sessionId = sessionId,
            name = OxypeJson.stringOr(json, name, "sessionName", "name"),
            inviteUuid = OxypeJson.stringOr(json, "", "inviteUuid", "invite_uuid", "uuid")
        )
    }

    /** `POST /joinSessionByUuid` with `{ uuid }`; answers the joined session view. */
    suspend fun joinSessionByUuid(credentials: AuthCredentials, uuid: String): SessionDetail {
        val payload = http.postJson(
            path = "joinSessionByUuid",
            body = JSONObject().apply { put("uuid", uuid.trim()) },
            credentials = credentials
        )
        val json = payload as? JSONObject
            ?: throw OxypeException(OxypeErrorKind.PROTOCOL, "加入会话的响应格式不正确")
        val sessionId = OxypeJson.long(json, "sessionId", "session_id", "id")
            ?: throw OxypeException(OxypeErrorKind.PROTOCOL, "加入会话的响应缺少会话编号")
        return parseSessionDetail(json, sessionId)
    }

    /** `POST /receivedMessageSeq/{userid}/{sessionId}` answers `{ seq }`. */
    suspend fun receivedMessageSeq(credentials: AuthCredentials, sessionId: Long): Long {
        val payload = http.postJson(
            path = "receivedMessageSeq/${credentials.userId}/$sessionId",
            body = JSONObject(),
            credentials = credentials
        )
        return when (payload) {
            is Number -> payload.toLong()
            is JSONObject -> OxypeJson.longOr(payload, 0L, "seq", "value")
            else -> 0L
        }
    }

    /**
     * `GET /lastMessage/{sessionId}`, the newest surviving message and a preview.
     *
     * The answer is tri-state rather than a nullable message: an explicit
     * `hasMessage = false` is a real "this chat is empty", while a missing flag, a
     * missing sequence or a non-object body is a payload this client cannot read.
     * Collapsing the two would make a protocol change look like a permanently silent
     * conversation, so the caller must be able to tell them apart.
     */
    suspend fun lastMessage(credentials: AuthCredentials, sessionId: Long): LastMessageResult {
        val payload = http.get(
            path = "lastMessage/$sessionId",
            credentials = credentials
        )
        val json = payload as? JSONObject ?: return LastMessageResult.Unknown
        val hasMessage = OxypeJson.boolean(json, "hasMessage", "has_message")
            ?: return LastMessageResult.Unknown
        if (!hasMessage) return LastMessageResult.Empty
        val id = OxypeJson.long(json, "id", "seq", "messageId")
            ?.takeIf { it >= 1L }
            ?: return LastMessageResult.Unknown
        return LastMessageResult.Present(
            LastMessageInfo(
                hasMessage = true,
                id = id,
                senderId = OxypeJson.longOr(json, 0L, "sender", "senderId"),
                senderName = OxypeJson.string(json, "senderName", "sender_name"),
                timestampMillis = OxypeJson.longOr(json, 0L, "timestamp"),
                content = OxypeJson.stringOr(json, "", "content", "text")
            )
        )
    }

    /**
     * `POST /getMessages/{sessionId}` with an inclusive `start`..`end` range.
     *
     * Deleted slots are simply absent from the answer, so the list may be shorter
     * than the requested window; that is a normal result, not an error. An *empty*
     * array is likewise a legitimate "nothing in this range" answer.
     *
     * A payload that is not a list, or a list holding an entry this client cannot read,
     * is a different thing: it says nothing about the window. Returning only the readable
     * rows would be indistinguishable from a fully deleted range, and the caller would
     * advance its watermark past messages it never saw, so it is raised as a protocol
     * failure instead of being filtered.
     */
    suspend fun messages(
        credentials: AuthCredentials,
        sessionId: Long,
        start: Long,
        end: Long
    ): List<ChatMessage> {
        val payload = http.postJson(
            path = "getMessages/$sessionId",
            body = JSONObject().apply {
                put("start", start)
                put("end", end)
            },
            credentials = credentials
        )
        if (payload == null) return emptyList()
        val array = OxypeJson.arrayOf(payload, "messages", "list")
            ?: throw OxypeException(OxypeErrorKind.PROTOCOL, "消息列表的响应格式不正确")
        val result = ArrayList<ChatMessage>(array.length())
        for (index in 0 until array.length()) {
            val entry = array.opt(index) as? JSONObject
                ?: throw malformedMessage("第 ${index + 1} 项不是消息对象")
            result.add(parseMessage(entry))
        }
        return result
    }

    /**
     * `POST /sendMessage/{sessionId}` with a text piece and, optionally, a reply piece.
     *
     * A reply must lead with `{"type":"reply","messageId":N}`: the server rejects a reply
     * piece anywhere else with 400, and it fills in the quoted sender/name/text itself —
     * a client-supplied snapshot is ignored. Only [messageId] is sent.
     *
     * Returns the assigned sequence, or `null` for the server's soft refusal
     * (`{ seq: -1 }` when the caller is not a member). A refusal is not an exception:
     * it is a legitimate answer that the caller must not render as a new message.
     */
    suspend fun sendText(
        credentials: AuthCredentials,
        sessionId: Long,
        text: String,
        replyTo: Long? = null
    ): Long? {
        val payload = http.postJson(
            path = "sendMessage/$sessionId",
            body = JSONObject().apply {
                put(
                    "pieces",
                    JSONArray().apply {
                        if (replyTo != null) {
                            put(
                                JSONObject().apply {
                                    put("type", "reply")
                                    put("messageId", replyTo)
                                }
                            )
                        }
                        put(
                            JSONObject().apply {
                                put("type", "text")
                                put("text", text)
                            }
                        )
                    }
                )
            },
            credentials = credentials
        )
        val seq = when (payload) {
            is Number -> payload.toLong()
            is JSONObject -> OxypeJson.long(payload, "seq", "id", "messageId")
            else -> null
        }
        return seq?.takeIf { it > 0L }
    }

    /**
     * `POST /deleteMessage/{sessionId}` with `{ messageId }`.
     *
     * `{ success: false }` is the soft refusal for a non-member; it returns false
     * rather than throwing so the caller can just resync. Some deployments answer with
     * a bare JSON boolean instead of an object, which is accepted as well.
     */
    suspend fun deleteMessage(
        credentials: AuthCredentials,
        sessionId: Long,
        messageId: Long
    ): Boolean {
        val payload = http.postJson(
            path = "deleteMessage/$sessionId",
            body = JSONObject().apply { put("messageId", messageId) },
            credentials = credentials
        )
        if (payload is Boolean) return payload
        val json = payload as? JSONObject ?: return false
        return OxypeJson.boolean(json, "success", "deleted") == true
    }

    /** `GET /getSessionUsers/{sessionId}` answers `{ users: [{ userid, username }] }`. */
    suspend fun sessionMembers(
        credentials: AuthCredentials,
        sessionId: Long
    ): List<SessionMember> {
        val payload = http.get(
            path = "getSessionUsers/$sessionId",
            credentials = credentials
        )
        val json = payload as? JSONObject ?: return emptyList()
        val array = OxypeJson.arrayOf(json, "users", "members", "list") ?: return emptyList()
        return OxypeJson.objects(array).mapNotNull { entry ->
            val userId = OxypeJson.long(entry, "userid", "userId", "id") ?: return@mapNotNull null
            SessionMember(
                userId = userId,
                username = memberName(entry, userId),
                avatarUrl = avatarUrlOf(entry)
            )
        }
    }

    /** `POST /createSessionInvite/{sessionId}` answers an invite object. */
    suspend fun createInvite(credentials: AuthCredentials, sessionId: Long): String? {
        val payload = http.postJson(
            path = "createSessionInvite/$sessionId",
            body = JSONObject(),
            credentials = credentials
        )
        return when (payload) {
            is String -> payload
            is JSONObject -> OxypeJson.string(payload, "uuid", "inviteUuid", "invite_uuid")
            else -> null
        }
    }

    /**
     * Reads the signed-in account's own profile.
     *
     * `GET /getUser/{uid}` supplies the name and the avatar URL, while
     * `GET /getAvatar/{uid}` supplies the upload rules and the authoritative `hasAvatar`.
     * They are merged rather than chained because the second route has its own permission
     * rule (it is visible to accounts that share a session, not to every authenticated
     * caller): a denial or a failure there must leave a usable name on screen instead of
     * failing the whole page. The avatar URL itself comes from `getUser`, so the page can
     * still draw the picture when `getAvatar` refuses.
     */
    suspend fun profile(credentials: AuthCredentials): UserProfile {
        val user = http.get(path = "getUser/${credentials.userId}", credentials = credentials)
        val json = user as? JSONObject
            ?: throw OxypeException(OxypeErrorKind.PROTOCOL, "账号资料的响应格式不正确")
        val userId = OxypeJson.long(json, "id", "userid", "userId")
            ?: credentials.userId
        val name = OxypeJson.string(json, "username", "name")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "用户 $userId"
        val avatarUrl = avatarUrlOf(json)

        val avatar = try {
            http.get(path = "getAvatar/${credentials.userId}", credentials = credentials)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: OxypeException) {
            if (e.kind == OxypeErrorKind.UNAUTHORIZED) throw e
            null
        } catch (e: Exception) {
            null
        }
        return mergeProfile(userId, name, avatarUrl, avatar)
    }

    /**
     * Uploads an avatar as base64 in a JSON body.
     *
     * The server accepts a `data:` URL prefix as well as a bare payload; the prefix is
     * stripped here so both sides agree on one form and the request stays a few bytes
     * smaller. Validation of the format and size happens before this call, in
     * [dev.oxype.client.ui.profile.AvatarUploadRules].
     */
    suspend fun uploadAvatar(credentials: AuthCredentials, imageBase64: String): UserProfile {
        val payload = http.postJson(
            path = "uploadAvatar/${credentials.userId}",
            body = JSONObject().apply {
                put("image", stripDataUrlPrefix(imageBase64))
            },
            credentials = credentials
        )
        return parseAvatarAnswer(payload, credentials.userId)
    }

    /** `POST /removeAvatar/{uid}`: the account draws as an initial again. Idempotent. */
    suspend fun removeAvatar(credentials: AuthCredentials): UserProfile {
        val payload = http.postJson(
            path = "removeAvatar/${credentials.userId}",
            body = JSONObject(),
            credentials = credentials
        )
        return parseAvatarAnswer(payload, credentials.userId)
    }

    /**
     * Merges `getUser` with an optional `getAvatar` answer.
     *
     * `hasAvatar` is taken from `getAvatar` when it says something, and otherwise inferred
     * from the URL, so a server that only reports the URL still gets a correct state.
     */
    private fun mergeProfile(
        userId: Long,
        username: String,
        avatarUrl: String?,
        avatarPayload: Any?
    ): UserProfile {
        val json = avatarPayload as? JSONObject
        val mergedUrl = avatarUrl ?: json?.let { avatarUrlOf(it) }
        return UserProfile(
            userId = userId,
            username = username,
            avatarUrl = mergedUrl,
            hasAvatar = OxypeJson.boolean(json ?: JSONObject(), "hasAvatar", "has_avatar")
                ?: !mergedUrl.isNullOrBlank(),
            maxBytes = OxypeJson.long(json ?: JSONObject(), "maxBytes", "max_bytes")
                ?: UserProfile.DEFAULT_AVATAR_MAX_BYTES,
            maxEdge = OxypeJson.long(json ?: JSONObject(), "maxEdge", "max_edge")
                ?.toInt()
                ?: UserProfile.DEFAULT_AVATAR_MAX_EDGE,
            formats = formatsOf(json)
        )
    }

    /** Reads an avatar-route answer (upload/remove), which is a full avatar view. */
    private fun parseAvatarAnswer(payload: Any?, userId: Long): UserProfile {
        val json = payload as? JSONObject
            ?: throw OxypeException(OxypeErrorKind.PROTOCOL, "头像操作的响应格式不正确")
        val url = avatarUrlOf(json)
        return UserProfile(
            userId = OxypeJson.long(json, "id", "userid", "userId") ?: userId,
            username = OxypeJson.string(json, "name", "username")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "用户 $userId",
            avatarUrl = url,
            hasAvatar = OxypeJson.boolean(json, "hasAvatar", "has_avatar")
                ?: !url.isNullOrBlank(),
            maxBytes = OxypeJson.long(json, "maxBytes", "max_bytes")
                ?: UserProfile.DEFAULT_AVATAR_MAX_BYTES,
            maxEdge = OxypeJson.long(json, "maxEdge", "max_edge")
                ?.toInt()
                ?: UserProfile.DEFAULT_AVATAR_MAX_EDGE,
            formats = formatsOf(json)
        )
    }

    private fun formatsOf(json: JSONObject?): List<String> {
        if (json == null) return UserProfile.DEFAULT_AVATAR_FORMATS
        val array = OxypeJson.arrayOf(json, "formats") ?: return UserProfile.DEFAULT_AVATAR_FORMATS
        val formats = (0 until array.length())
            .mapNotNull { array.opt(it) as? String }
            .filter { it.isNotBlank() }
        return formats.ifEmpty { UserProfile.DEFAULT_AVATAR_FORMATS }
    }

    /** Removes an optional `data:<mime>;base64,` prefix from an encoded image. */
    private fun stripDataUrlPrefix(value: String): String {
        val trimmed = value.trim()
        if (!trimmed.startsWith("data:")) return trimmed
        val comma = trimmed.indexOf(',')
        return if (comma == -1) trimmed else trimmed.substring(comma + 1)
    }

    private fun parseSessions(payload: Any?): List<SessionSummary> {
        val array = OxypeJson.arrayOf(payload, "sessions", "list") ?: return emptyList()
        return OxypeJson.objects(array).mapNotNull { entry ->
            val sessionId = OxypeJson.long(entry, "sessionId", "session_id", "id")
                ?: return@mapNotNull null
            SessionSummary(
                sessionId = sessionId,
                name = OxypeJson.stringOr(entry, "会话 $sessionId", "sessionName", "name", "title"),
                description = OxypeJson.stringOr(entry, "", "description"),
                ownerId = OxypeJson.longOr(entry, 0L, "owner", "ownerId", "owner_id"),
                // Batch answers have been seen with both spellings; `memberIds` in
                // particular is the one a compact server implementation tends to use.
                memberIds = OxypeJson.longList(
                    OxypeJson.arrayOf(entry, "members", "memberIds", "member_ids")
                ),
                adminIds = OxypeJson.longList(
                    OxypeJson.arrayOf(entry, "admins", "adminIds", "admin_ids")
                )
            )
        }
    }

    private fun parseSessionDetail(payload: Any?, fallbackSessionId: Long): SessionDetail {
        val json = payload as? JSONObject
            ?: throw OxypeException(OxypeErrorKind.PROTOCOL, "会话信息格式不正确")
        val sessionId = OxypeJson.longOr(json, fallbackSessionId, "sessionId", "session_id", "id")
        val members = OxypeJson.objects(
            OxypeJson.arrayOf(json, "users", "members", "list") ?: JSONArray()
        ).mapNotNull { entry ->
            val userId = OxypeJson.long(entry, "userid", "userId", "id") ?: return@mapNotNull null
            SessionMember(
                userId = userId,
                username = memberName(entry, userId),
                avatarUrl = avatarUrlOf(entry)
            )
        }
        val memberIds = if (members.isNotEmpty()) {
            members.map { it.userId }
        } else {
            OxypeJson.longList(OxypeJson.arrayOf(json, "members"))
        }
        return SessionDetail(
            sessionId = sessionId,
            name = OxypeJson.stringOr(json, "会话 $sessionId", "sessionName", "name", "title"),
            description = OxypeJson.stringOr(json, "", "description"),
            ownerId = OxypeJson.longOr(json, 0L, "owner", "ownerId"),
            isOwner = OxypeJson.boolean(json, "isOwner", "is_owner") ?: false,
            isAdmin = OxypeJson.boolean(json, "isAdmin", "is_admin") ?: false,
            members = if (members.isNotEmpty()) {
                members
            } else {
                memberIds.map { SessionMember(it, "用户 $it") }
            },
            adminIds = OxypeJson.longList(
                OxypeJson.arrayOf(json, "admins", "adminIds", "admin_ids")
            ),
            avatarUrl = avatarUrlOf(json)
        )
    }

    /**
     * Reads a member name, ignoring a present-but-blank value.
     *
     * `{"username": ""}` is not a name: falling back to the numeric label keeps the
     * transcript readable instead of painting a nameless sender.
     */
    private fun memberName(entry: JSONObject, userId: Long): String =
        OxypeJson.string(entry, "username", "name")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "用户 $userId"

    /**
     * Reads an optional avatar source out of a member record.
     *
     * Oxype does not specify an avatar field, and deployments have been seen with
     * several spellings. Every alias is optional and a blank string is treated as
     * "none", so an absent avatar never becomes an unloadable empty URL. A relative
     * answer (`/static/avatars/7.png`, `avatars/7.png`) is resolved against the server
     * currently configured; an absolute HTTP(S) URL is kept, because it may point at a
     * separate image host. Anything else — `file:`, `data:`, a broken value — becomes
     * `null` so the UI keeps its initial instead of trying to fetch it.
     */
    private fun avatarUrlOf(entry: JSONObject): String? {
        val raw = OxypeJson.string(
            entry,
            "avatarUrl",
            "avatar_url",
            "avatar",
            "photoUrl",
            "photo_url",
            "photo"
        )
        return AvatarUrls.resolve(raw, http.baseUrl)
    }

    /**
     * Decodes one message record.
     *
     * `pieces` has appeared under both spellings and both piece kinds carry the same
     * `text` field. The spelling aliases are accepted, but the record itself is not
     * optional: a message without a usable id, a sender, or readable content cannot be
     * rendered, and silently dropping it would make the caller believe the sequence was
     * fetched while it never was. Such an entry is therefore a protocol failure.
     */
    private fun parseMessage(json: JSONObject): ChatMessage {
        val id = OxypeJson.long(json, "id", "seq", "messageId")
            ?.takeIf { it >= 1L }
            ?: throw malformedMessage("消息缺少有效的 id")
        val senderId = OxypeJson.long(json, "sender", "senderId", "sender_id")
            ?: throw malformedMessage("消息 $id 缺少发送者")
        if (senderId < 0L) throw malformedMessage("消息 $id 的发送者无效")
        val piecesJson = OxypeJson.arrayOf(json, "pieces", "content")
            ?: throw malformedMessage("消息 $id 缺少内容片段")
        val pieces = ArrayList<MessagePiece>(piecesJson.length())
        var reply: ReplyQuote? = null
        for (index in 0 until piecesJson.length()) {
            val piece = piecesJson.opt(index) as? JSONObject
                ?: throw malformedMessage("消息 $id 第 ${index + 1} 个片段不是对象")
            val type = OxypeJson.string(piece, "type")?.lowercase()
            if (type == "reply") {
                // A quote is message metadata, not body content: it must not be joined
                // into `plainText`. Only the first one counts; the server allows a single
                // reply piece and the rest is tolerant reading.
                if (reply == null) reply = parseReplyPiece(piece)
                continue
            }
            val kind = when (type) {
                "markdown", "md" -> MessagePieceKind.MARKDOWN
                "text", null -> MessagePieceKind.TEXT
                // A piece kind this client does not know is skipped, which keeps a
                // forward-compatible server readable. If that leaves nothing to show the
                // whole record is a protocol failure below.
                else -> continue
            }
            val text = OxypeJson.string(piece, "text", "content", "value")
                ?: throw malformedMessage("消息 $id 的片段缺少文本")
            pieces.add(MessagePiece(kind, text))
        }
        if (pieces.isEmpty()) throw malformedMessage("消息 $id 没有可显示的内容")
        return ChatMessage(
            id = id,
            senderId = senderId,
            timestampMillis = OxypeJson.longOr(json, 0L, "timestamp", "time"),
            editedAtMillis = OxypeJson.longOr(json, 0L, "editedAt", "edited_at"),
            pieces = pieces,
            reply = reply
        )
    }

    /**
     * Reads a stored `reply` piece.
     *
     * `messageId` is the only hard requirement: without it the quote cannot be jumped to
     * and is dropped entirely, while the reply's own text stays visible. The snapshot
     * fields are optional because the server does not document their spelling; a blank
     * value is treated as absent so the UI can fall back to a locally loaded message
     * instead of drawing an empty quote block.
     */
    private fun parseReplyPiece(piece: JSONObject): ReplyQuote? {
        val messageId = OxypeJson.long(piece, "messageId", "message_id", "id", "seq")
            ?.takeIf { it >= 1L }
            ?: return null
        val senderId = OxypeJson.long(piece, "sender", "senderId", "sender_id")
            ?.takeIf { it >= 0L }
        return ReplyQuote(
            messageId = messageId,
            senderId = senderId,
            senderName = OxypeJson.string(piece, "senderName", "sender_name", "username", "name")
                ?.trim()
                ?.takeIf { it.isNotEmpty() },
            text = OxypeJson.string(piece, "text", "content", "value")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        )
    }

    private fun malformedMessage(reason: String) =
        OxypeException(OxypeErrorKind.PROTOCOL, "消息数据格式不正确：$reason")
}
