package dev.oxype.client.data

import dev.oxype.client.data.oxype.AuthCredentials
import dev.oxype.client.data.oxype.HttpRequest
import dev.oxype.client.data.oxype.HttpResponse
import dev.oxype.client.data.oxype.HttpTransport
import dev.oxype.client.data.oxype.OxypeApi
import dev.oxype.client.data.oxype.OxypeErrorKind
import dev.oxype.client.data.oxype.OxypeException
import dev.oxype.client.data.oxype.OxypeHttpClient
import dev.oxype.client.data.session.SessionStorage
import dev.oxype.client.data.session.ThemeMode
import dev.oxype.client.data.sync.MessageSyncCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repository behaviour against a scripted transport.
 *
 * The fake answers by route so the test covers the real request sequence the
 * repository produces, without a live Oxype instance.
 */
class RepositoryTest {

    /** In-memory stand-in for SharedPreferences-backed storage. */
    private class FakeSessionStorage : SessionStorage {
        override var baseUrl: String = "http://example.test:12345"
        override var themeMode: ThemeMode = ThemeMode.SYSTEM
        private var stored: AuthCredentials? = null
        private var storedName: String? = null

        override fun credentials(): AuthCredentials? = stored
        override fun username(): String? = storedName
        override fun saveAuth(credentials: AuthCredentials, username: String?) {
            stored = credentials
            storedName = username
        }

        override fun clearAuth() {
            stored = null
            storedName = null
        }

        override fun hasStoredAuth(): Boolean = stored != null
    }

    /**
     * Route-aware fake: `path` is everything after the base URL.
     *
     * A handler may return `null` to accept the default answer for a route, which keeps
     * every test focused on the requests its scenario actually cares about.
     */
    private class ScriptedTransport(
        private val handler: suspend (HttpRequest, String) -> HttpResponse?
    ) : HttpTransport {
        val requests = mutableListOf<HttpRequest>()

        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests.add(request)
            val path = request.url.substringAfter("://").substringAfter('/')
            val route = "/" + path.substringBefore('?')
            // The script wins; unscripted auth routes get their obvious answer so each
            // test only declares what its scenario is about.
            return handler(request, route) ?: when {
                route.startsWith("/login/") ->
                    HttpResponse(200, """{"data":{"token":"tok"}}""")

                route.startsWith("/isAlive/") ->
                    HttpResponse(200, """{"data":{"is_alive":true}}""")

                // Unscripted, non-auth routes answer 404 so a test that forgets a route
                // fails loudly instead of silently reading an empty payload.
                else -> HttpResponse(404, """{"error_message":"unscripted route"}""")
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun repository(
        storage: FakeSessionStorage,
        transport: ScriptedTransport
    ): Pair<OxypeRepository, OxypeHttpClient> {
        val http = OxypeHttpClient(transport = transport, baseUrl = "http://example.test:12345")
        return OxypeRepository(
            api = OxypeApi(http),
            http = http,
            sessionStore = storage,
            scope = scope
        ) to http
    }

    private fun body(request: HttpRequest): JSONObject = JSONObject(request.body!!)

    @Test
    fun `login stores credentials and sends only the password`() = runBlocking {
        val storage = FakeSessionStorage()
        val transport = ScriptedTransport { _, path ->
            when (path) {
                "/login/7" -> HttpResponse(200, """{"data":{"token":"tok"}}""")
                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        val credentials = repository.login(7L, "secret1")

        assertEquals(7L, credentials.userId)
        assertEquals("tok", credentials.token)
        assertEquals(credentials, storage.credentials())
        // `POST /login/{userid}` takes only the password; the id is already in the path.
        val loginBody = body(transport.requests.single { it.url.contains("/login/7") })
        assertFalse(loginBody.has("userid"))
        assertEquals("secret1", loginBody.getString("password"))
    }

    @Test
    fun `register persists the returned token`() = runBlocking {
        val storage = FakeSessionStorage()
        val transport = ScriptedTransport { _, path ->
            when (path) {
                "/register" -> HttpResponse(200, """{"data":{"userid":11,"token":"newtok"}}""")
                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.register("alice", "secret1")

        assertEquals(11L, storage.credentials()?.userId)
        assertEquals("newtok", storage.credentials()?.token)
        assertEquals("alice", storage.username())
        assertEquals(11L, repository.currentUserId())
    }

    @Test
    fun `restore session clears a token the server rejects`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(3L, "dead"), "alice")
        }
        val transport = ScriptedTransport { _, path ->
            when (path) {
                "/isAlive/3" -> HttpResponse(200, """{"data":{"is_alive":false}}""")
                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)

        assertFalse(repository.restoreSession())
        assertNull(storage.credentials())
        assertTrue(repository.sessionExpired.value)
    }

    @Test
    fun `restore session keeps a token when the server is unreachable`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(3L, "live"), "alice")
        }
        val transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse {
                throw dev.oxype.client.data.oxype.OxypeException(
                    dev.oxype.client.data.oxype.OxypeErrorKind.NETWORK,
                    "无法连接服务器"
                )
            }
        }
        val http = OxypeHttpClient(transport = transport)
        val repository = OxypeRepository(
            api = OxypeApi(http),
            http = http,
            sessionStore = storage,
            scope = scope
        )

        // An unreachable server says nothing about token validity, so the login stays.
        assertTrue(repository.restoreSession())
        assertNotNull(storage.credentials())
    }

    @Test
    fun `logout clears local state even if the server call fails`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(3L, "tok"), "alice")
        }
        val transport = ScriptedTransport { _, path ->
            when (path) {
                "/logout/3" -> HttpResponse(500, """{"error_message":"boom"}""")
                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.login(3L, "secret1")
        repository.logout()

        assertNull(storage.credentials())
        assertNull(repository.credentials.value)
    }

    @Test
    fun `refresh sessions resolves ids and keeps display names`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        val transport = ScriptedTransport { _, path ->
            when (path) {
                "/getJoinedSessions/1" ->
                    HttpResponse(200, """{"data":{"isEmpty":false,"sessions":[10,11]}}""")

                "/getSessions" -> HttpResponse(
                    200,
                    """{"data":{"sessions":[
                        {"sessionId":10,"sessionName":"甲"},
                        {"sessionId":11,"sessionName":"乙"}
                    ]}}"""
                )

                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.login(1L, "secret1")
        repository.refreshSessions()

        assertEquals(listOf(10L, 11L), repository.sessions.value.map { it.sessionId })
        assertEquals(listOf("甲", "乙"), repository.sessions.value.map { it.name })
    }

    @Test
    fun `sessions fall back to per session info when the batch route is unusable`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        val transport = ScriptedTransport { _, path ->
            when (path) {
                "/getJoinedSessions/1" -> HttpResponse(200, """{"data":{"sessions":[20]}}""")
                "/getSessions" -> HttpResponse(200, """{"data":{"unexpected":true}}""")
                "/getSessionInfo/20" -> HttpResponse(
                    200,
                    """{"data":{"sessionId":20,"sessionName":"丙","owner":1,"users":[{"userid":1,"username":"alice"}]}}"""
                )

                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.login(1L, "secret1")
        repository.refreshSessions()

        assertEquals(1, repository.sessions.value.size)
        assertEquals("丙", repository.sessions.value.single().name)
        assertEquals(1, repository.sessions.value.single().memberCount)
    }

    @Test
    fun `batch sessions accept memberIds and adminIds aliases`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        var detailRequested = false
        val transport = ScriptedTransport { _, path ->
            when (path) {
                "/getJoinedSessions/1" -> HttpResponse(200, """{"data":{"sessions":[30]}}""")
                "/getSessions" -> HttpResponse(
                    200,
                    """{"data":{"sessions":[{"sessionId":30,"sessionName":"戊",
                        "ownerId":1,"memberIds":[1,2],"adminIds":[2]}]}}"""
                )

                "/getSessionInfo/30" -> {
                    detailRequested = true
                    null
                }

                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.login(1L, "secret1")
        repository.refreshSessions()

        val summary = repository.sessions.value.single()
        assertEquals(listOf(1L, 2L), summary.memberIds)
        assertEquals(listOf(2L), summary.adminIds)
        assertEquals(2, summary.memberCount)
        assertFalse("a complete batch row needs no detail call", detailRequested)
    }

    @Test
    fun `a batch row without members is completed from the session detail`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        val transport = ScriptedTransport { _, path ->
            when (path) {
                "/getJoinedSessions/1" -> HttpResponse(200, """{"data":{"sessions":[31]}}""")
                // The batch route abbreviates the record: no owner, no members.
                "/getSessions" -> HttpResponse(
                    200,
                    """{"data":{"sessions":[{"sessionId":31,"sessionName":"己"}]}}"""
                )

                "/getSessionInfo/31" -> HttpResponse(
                    200,
                    """{"data":{"sessionId":31,"sessionName":"己","owner":1,
                        "users":[{"userid":1,"username":"alice"},{"userid":2,"username":"bob"}]}}"""
                )

                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.login(1L, "secret1")
        repository.refreshSessions()

        assertEquals(1, repository.sessions.value.size)
        assertEquals("己", repository.sessions.value.single().name)
        assertEquals(listOf(1L, 2L), repository.sessions.value.single().memberIds)
        assertEquals(2, repository.sessions.value.single().memberCount)
    }

    @Test
    fun `an incomplete batch row survives a failed detail lookup`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        val transport = ScriptedTransport { _, path ->
            when (path) {
                "/getJoinedSessions/1" -> HttpResponse(200, """{"data":{"sessions":[32]}}""")
                "/getSessions" -> HttpResponse(
                    200,
                    """{"data":{"sessions":[{"sessionId":32,"sessionName":"庚"}]}}"""
                )

                "/getSessionInfo/32", "/getSession/32" ->
                    throw dev.oxype.client.data.oxype.OxypeException(
                        dev.oxype.client.data.oxype.OxypeErrorKind.NETWORK,
                        "无法连接服务器"
                    )

                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.login(1L, "secret1")
        repository.refreshSessions()

        // The batch name is still known, so the conversation must not vanish from the
        // list because a supplementary detail call failed.
        assertEquals(1, repository.sessions.value.size)
        assertEquals("庚", repository.sessions.value.single().name)
        assertEquals(32L, repository.sessions.value.single().sessionId)
    }

    @Test
    fun `a 401 while opening a chat signs the user out`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "dead"), "alice")
        }
        val transport = ScriptedTransport { _, path ->
            when (path) {
                "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")
                "/lastMessage/9" -> HttpResponse(200, """{"data":{"hasMessage":false}}""")
                "/getMessages/9" ->
                    HttpResponse(401, """{"error_message":"Unauthorized"}""")

                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        // Publish a login first, as a successful restore would have done.
        repository.login(1L, "secret1")

        val error = runCatching { repository.openChat(9) }.exceptionOrNull()
        assertEquals(
            dev.oxype.client.data.oxype.OxypeErrorKind.UNAUTHORIZED,
            (error as dev.oxype.client.data.oxype.OxypeException).kind
        )

        assertNull("a rejected token must clear the login", repository.credentials.value)
        assertTrue(repository.sessionExpired.value)
        assertNull(repository.chat.value)
    }

    @Test
    fun `a rejected token during session resolution escapes the per session fallback`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        val transport = ScriptedTransport { _, path ->
            when (path) {
                "/getJoinedSessions/1" -> HttpResponse(200, """{"data":{"sessions":[34,35]}}""")
                "/getSessions" -> HttpResponse(500, """{"error_message":"boom"}""")
                "/getSessionInfo/34" -> HttpResponse(401, """{"error_message":"Unauthorized"}""")
                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.login(1L, "secret1")

        val error = runCatching { repository.refreshSessions() }.exceptionOrNull()
            as? dev.oxype.client.data.oxype.OxypeException

        assertEquals(
            "a rejected token must not be downgraded to a per-session skip",
            dev.oxype.client.data.oxype.OxypeErrorKind.UNAUTHORIZED,
            error?.kind
        )
    }

    @Test
    fun `a network failure across every route is reported instead of an empty list`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        val transport = ScriptedTransport { _, path ->
            when {
                path.startsWith("/login/") -> null
                path == "/getJoinedSessions/1" -> HttpResponse(200, """{"data":{"sessions":[33]}}""")
                else -> throw dev.oxype.client.data.oxype.OxypeException(
                    dev.oxype.client.data.oxype.OxypeErrorKind.NETWORK,
                    "无法连接服务器"
                )
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.login(1L, "secret1")

        val error = runCatching { repository.refreshSessions() }.exceptionOrNull()

        assertNotNull("a network failure must not look like an empty list", error)
        assertTrue(error is dev.oxype.client.data.oxype.OxypeException)
    }

    @Test
    fun `open chat anchors on the newest message not the read marker`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        val transport = ScriptedTransport { _, path ->
            when {
                path == "/receivedMessageSeq/1/9" ->
                    // The user has read only up to 2, but the transcript has 40.
                    HttpResponse(200, """{"data":{"seq":2}}""")

                path == "/lastMessage/9" ->
                    HttpResponse(200, """{"data":{"hasMessage":true,"id":40,"content":"newest"}}""")

                path == "/getMessages/9" -> HttpResponse(
                    200,
                    """{"data":[
                        {"id":40,"sender":1,"pieces":[{"type":"text","text":"newest"}]},
                        {"id":39,"sender":1,"pieces":[{"type":"text","text":"older"}]}
                    ]}"""
                )

                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.login(1L, "secret1")
        repository.openChat(9)

        val snapshot = repository.chat.value!!
        assertEquals(listOf(39L, 40L), snapshot.messages.map { it.id })
        assertFalse(snapshot.isLoadingInitial)

        // The window must end at the newest message, and be inclusive from `end - page + 1`.
        val messagesRequest = transport.requests.last { it.url.contains("/getMessages/9") }
        val payload = body(messagesRequest)
        assertEquals(40L, payload.getLong("end"))
        assertEquals(11L, payload.getLong("start"))
    }

    @Test
    fun `send text pulls the stored message back and does not duplicate it`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        var messageFetched = false
        val transport = ScriptedTransport { _, path ->
            when {
                path == "/lastMessage/9" ->
                    HttpResponse(200, """{"data":{"hasMessage":false}}""")

                path == "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")

                path == "/getMessages/9" -> if (!messageFetched) {
                    // Opening the conversation: nothing yet.
                    HttpResponse(200, """{"data":[]}""")
                } else {
                    HttpResponse(
                        200,
                        """{"data":[{"id":1,"sender":1,"pieces":[{"type":"text","text":"hello"}]}]}"""
                    )
                }

                path == "/sendMessage/9" -> HttpResponse(200, """{"data":{"seq":1}}""")
                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.login(1L, "secret1")
        repository.openChat(9)

        messageFetched = true
        assertTrue(repository.sendText("hello"))

        val messages = repository.chat.value!!.messages
        assertEquals(1, messages.size)
        assertEquals("hello", messages.single().plainText)
    }

    @Test
    fun `a send whose tail read fails is recovered by the next poll exactly once`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        // The send is accepted at seq 3, but the immediate tail re-read fails. Polling
        // must then ask for a range that still includes seq 3, and merge it once.
        var tailReadFails = true
        var serverNewest = 2L
        val transport = ScriptedTransport { _, path ->
            when {
                path == "/lastMessage/9" ->
                    HttpResponse(200, """{"data":{"hasMessage":true,"id":$serverNewest}}""")

                path == "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")

                path == "/getMessages/9" -> {
                    if (tailReadFails && serverNewest == 3L) {
                        HttpResponse(500, """{"error_message":"tail unavailable"}""")
                    } else {
                        val items = (1L..serverNewest).joinToString(",") {
                            """{"id":$it,"sender":1,"pieces":[{"type":"text","text":"m$it"}]}"""
                        }
                        HttpResponse(200, """{"data":[$items]}""")
                    }
                }

                path == "/sendMessage/9" -> {
                    serverNewest = 3L
                    HttpResponse(200, """{"data":{"seq":3}}""")
                }

                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.login(1L, "secret1")
        repository.openChat(9)
        assertEquals(listOf(1L, 2L), repository.chat.value!!.messages.map { it.id })

        assertTrue("the send itself succeeded", repository.sendText("hello"))
        // The optimistic tail read failed, so the message is not on screen yet.
        assertEquals(listOf(1L, 2L), repository.chat.value!!.messages.map { it.id })

        // The next poll must request the sent sequence rather than skip past it.
        tailReadFails = false
        assertTrue(repository.pollOnce())

        val pollRequest = transport.requests.last { it.url.contains("/getMessages/9") }
        val payload = JSONObject(pollRequest.body!!)
        assertTrue(
            "the poll must include the sent seq 3: ${payload.getLong("start")}..${payload.getLong("end")}",
            payload.getLong("start") <= 3L && payload.getLong("end") >= 3L
        )
        assertEquals(listOf(1L, 2L, 3L), repository.chat.value!!.messages.map { it.id })

        // A further poll must not show it twice.
        assertFalse(repository.pollOnce())
        assertEquals(listOf(1L, 2L, 3L), repository.chat.value!!.messages.map { it.id })
    }

    @Test
    fun `send text reports a soft refusal as a failure`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        val transport = ScriptedTransport { _, path ->
            when {
                path == "/lastMessage/9" ->
                    HttpResponse(200, """{"data":{"hasMessage":false}}""")

                path == "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")
                path == "/getMessages/9" -> HttpResponse(200, """{"data":[]}""")
                path == "/sendMessage/9" -> HttpResponse(200, """{"data":{"seq":-1}}""")
                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.login(1L, "secret1")
        repository.openChat(9)

        assertFalse(repository.sendText("hello"))
        assertTrue(repository.chat.value!!.error != null)
        assertTrue(repository.chat.value!!.messages.isEmpty())
    }

    @Test
    fun `polling merges only unseen messages`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        var serverNewest = 2L
        val transport = ScriptedTransport { _, path ->
            when {
                path == "/lastMessage/9" ->
                    HttpResponse(200, """{"data":{"hasMessage":true,"id":$serverNewest}}""")

                path == "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")

                path == "/getMessages/9" -> {
                    val payload = JSONObject("{}")
                    // Answer with everything up to the server's newest for simplicity.
                    val items = (1..serverNewest).joinToString(",") {
                        """{"id":$it,"sender":1,"pieces":[{"type":"text","text":"m$it"}]}"""
                    }
                    HttpResponse(200, """{"data":[$items]}""")
                }

                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.login(1L, "secret1")
        repository.openChat(9)
        assertEquals(2, repository.chat.value!!.messages.size)

        // Idle poll: no newer sequence, nothing fetched, nothing changes.
        assertFalse(repository.pollOnce())
        assertEquals(2, repository.chat.value!!.messages.size)

        serverNewest = 4L
        assertTrue(repository.pollOnce())
        assertEquals(listOf(1L, 2L, 3L, 4L), repository.chat.value!!.messages.map { it.id })
    }

    @Test
    fun `polling with an authoritative empty probe makes no message request`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        var messageRequests = 0
        val transport = ScriptedTransport { _, path ->
            when {
                path == "/lastMessage/9" ->
                    HttpResponse(200, """{"data":{"hasMessage":false}}""")

                path == "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")

                path == "/getMessages/9" -> {
                    messageRequests++
                    HttpResponse(200, """{"data":[]}""")
                }

                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.login(1L, "secret1")
        repository.openChat(9)
        val afterOpen = messageRequests

        assertFalse(repository.pollOnce())
        assertEquals("idle poll must not fetch a range", afterOpen, messageRequests)
    }

    @Test
    fun `polling falls back to a speculative range when the probe fails`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        var probes = 0
        val transport = ScriptedTransport { _, path ->
            when {
                path == "/lastMessage/9" -> {
                    probes++
                    // The probe route is unavailable on this server; opening the chat
                    // tolerates it and polling must still make progress.
                    if (probes <= 1) null else HttpResponse(500, """{"error_message":"boom"}""")
                }

                path == "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")

                path == "/getMessages/9" -> HttpResponse(
                    200,
                    """{"data":[{"id":1,"sender":1,"pieces":[{"type":"text","text":"a"}]}]}"""
                )

                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.login(1L, "secret1")
        repository.openChat(9)

        // The fallback window still returns the only message; it is already known, so
        // nothing is duplicated and the call simply reports "no additions".
        assertFalse(repository.pollOnce())
        assertEquals(1, repository.chat.value!!.messages.size)
    }

    @Test
    fun `deleting a message removes it only when the server confirms`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        var deleteConfirmed = false
        val transport = ScriptedTransport { _, path ->
            when {
                path == "/lastMessage/9" ->
                    HttpResponse(200, """{"data":{"hasMessage":true,"id":2}}""")

                path == "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")

                path == "/getMessages/9" -> HttpResponse(
                    200,
                    """{"data":[
                        {"id":1,"sender":1,"pieces":[{"type":"text","text":"a"}]},
                        {"id":2,"sender":2,"pieces":[{"type":"text","text":"b"}]}
                    ]}"""
                )

                path == "/deleteMessage/9" -> HttpResponse(
                    200,
                    if (deleteConfirmed) """{"data":{"success":true}}""" else """{"data":{"success":false}}"""
                )

                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.login(1L, "secret1")
        repository.openChat(9)

        assertFalse(repository.deleteMessage(1))
        assertEquals(2, repository.chat.value!!.messages.size)

        deleteConfirmed = true
        assertTrue(repository.deleteMessage(1))
        assertEquals(listOf(2L), repository.chat.value!!.messages.map { it.id })
    }

    @Test
    fun `a deleted message is not resurrected by the next poll`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        // The server confirms the deletion but keeps answering the old record for a
        // while — an answer that was snapshotted before the delete.
        var serverNewest = 2L
        var includeDeleted = true
        val transport = ScriptedTransport { _, path ->
            when {
                path == "/lastMessage/9" ->
                    HttpResponse(200, """{"data":{"hasMessage":true,"id":$serverNewest}}""")

                path == "/getMessages/9" -> {
                    val items = (1L..serverNewest).mapNotNull { id ->
                        if (id == 1L && !includeDeleted) null
                        else """{"id":$id,"sender":1,"pieces":[{"type":"text","text":"m$id"}]}"""
                    }.joinToString(",")
                    HttpResponse(200, """{"data":[$items]}""")
                }

                path == "/deleteMessage/9" -> HttpResponse(200, """{"data":{"success":true}}""")
                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.login(1L, "secret1")
        repository.openChat(9)
        assertEquals(listOf(1L, 2L), repository.chat.value!!.messages.map { it.id })

        assertTrue(repository.deleteMessage(1))
        assertEquals(listOf(2L), repository.chat.value!!.messages.map { it.id })

        // New traffic arrives; the range read still carries the deleted record.
        serverNewest = 3L
        assertTrue(repository.pollOnce())
        assertEquals(
            "a confirmed deletion must survive the poll that raced it",
            listOf(2L, 3L),
            repository.chat.value!!.messages.map { it.id }
        )

        // The same store serves older pages: a late page must not undo it either.
        includeDeleted = false
        repository.loadOlder()
        assertEquals(listOf(2L, 3L), repository.chat.value!!.messages.map { it.id })
    }

    @Test
    fun `reopening a chat after a deletion trusts the server again`() = runBlocking {
        // The tombstone belongs to one open conversation; a reopen fetches fresh state.
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        val transport = ScriptedTransport { _, path ->
            when {
                path == "/lastMessage/9" ->
                    HttpResponse(200, """{"data":{"hasMessage":true,"id":2}}""")

                path == "/getMessages/9" -> HttpResponse(
                    200,
                    """{"data":[
                        {"id":1,"sender":1,"pieces":[{"type":"text","text":"a"}]},
                        {"id":2,"sender":1,"pieces":[{"type":"text","text":"b"}]}
                    ]}"""
                )

                path == "/deleteMessage/9" -> HttpResponse(200, """{"data":{"success":true}}""")
                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.login(1L, "secret1")
        repository.openChat(9)
        assertTrue(repository.deleteMessage(1))
        assertEquals(listOf(2L), repository.chat.value!!.messages.map { it.id })

        repository.openChat(9)

        assertEquals(
            "a new coordinator starts from the server's answer",
            listOf(1L, 2L),
            repository.chat.value!!.messages.map { it.id }
        )
    }

    @Test
    fun `a tail re-read that omits the sent sequence leaves it for the next poll`() =
        runBlocking {
            val storage = FakeSessionStorage().apply {
                saveAuth(AuthCredentials(1L, "tok"), "alice")
            }
            // The send is accepted at 3, but the immediate re-read answers the older
            // messages only (the record is not visible yet).
            var exposeSent = false
            var serverNewest = 2L
            val transport = ScriptedTransport { request, path ->
                when {
                    path == "/lastMessage/9" ->
                        HttpResponse(200, """{"data":{"hasMessage":true,"id":$serverNewest}}""")

                    path == "/getMessages/9" -> {
                        val body = JSONObject(request.body!!)
                        val start = body.getLong("start").coerceAtLeast(1L)
                        val end = body.getLong("end").coerceAtMost(serverNewest)
                        val items = if (start <= end) {
                            (start..end).mapNotNull { id ->
                                if (id == 3L && !exposeSent) null
                                else """{"id":$id,"sender":1,"pieces":[{"type":"text","text":"m$id"}]}"""
                            }.joinToString(",")
                        } else {
                            ""
                        }
                        HttpResponse(200, """{"data":[$items]}""")
                    }

                    path == "/sendMessage/9" -> {
                        serverNewest = 3L
                        HttpResponse(200, """{"data":{"seq":3}}""")
                    }

                    else -> null
                }
            }
            val (repository, _) = repository(storage, transport)
            repository.login(1L, "secret1")
            repository.openChat(9)
            assertEquals(listOf(1L, 2L), repository.chat.value!!.messages.map { it.id })

            assertTrue("the send itself succeeds", repository.sendText("hello"))
            assertEquals(
                "the unexposed record is not on screen yet",
                listOf(1L, 2L),
                repository.chat.value!!.messages.map { it.id }
            )

            // The tail read acknowledged nothing, so the next poll must ask for 3.
            exposeSent = true
            assertTrue(repository.pollOnce())
            val poll = body(transport.requests.last { it.url.contains("/getMessages/9") })
            assertTrue(
                "the next poll must include the sent seq: ${poll.getLong("start")}..${poll.getLong("end")}",
                poll.getLong("start") <= 3L && poll.getLong("end") >= 3L
            )
            assertEquals(listOf(1L, 2L, 3L), repository.chat.value!!.messages.map { it.id })
        }

    @Test
    fun `auth state starts checking and resolves to ready or signed out`() = runBlocking {
        // Nothing restored yet: a screen must be able to tell "not checked" apart from
        // "checked and signed out" before deciding to open a conversation.
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(3L, "live"), "alice")
        }
        val transport = ScriptedTransport { _, path ->
            when (path) {
                "/isAlive/3" -> HttpResponse(200, """{"data":{"is_alive":true}}""")
                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        assertEquals(AuthRestoreState.CHECKING, repository.authState.value)

        assertTrue(repository.restoreSession())
        assertEquals(AuthRestoreState.READY, repository.authState.value)

        repository.logout()
        assertEquals(AuthRestoreState.SIGNED_OUT, repository.authState.value)
    }

    @Test
    fun `a rejected stored token resolves the auth state to signed out`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(3L, "dead"), "alice")
        }
        val transport = ScriptedTransport { _, path ->
            when (path) {
                "/isAlive/3" -> HttpResponse(200, """{"data":{"is_alive":false}}""")
                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)

        assertFalse(repository.restoreSession())
        assertEquals(AuthRestoreState.SIGNED_OUT, repository.authState.value)
    }

    @Test
    fun `an unverifiable token still resolves the auth state to ready`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(3L, "live"), "alice")
        }
        val transport = ScriptedTransport { _, path ->
            when (path) {
                "/isAlive/3" -> HttpResponse(500, """{"error_message":"boom"}""")
                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)

        assertTrue(repository.restoreSession())
        // A network/server failure says nothing about validity, so a restored chat is
        // allowed to proceed and report its own error rather than bouncing to auth.
        assertEquals(AuthRestoreState.READY, repository.authState.value)
        assertNotNull(repository.credentials.value)
    }

    @Test
    fun `restore session keeps a token that is authenticated but forbidden`() = runBlocking {
        // Oxype answers 403 when the caller is authenticated but not allowed to touch
        // the resource. That says nothing about the token, so signing out would throw
        // away a perfectly good login.
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(3L, "live"), "alice")
        }
        val transport = ScriptedTransport { _, path ->
            when (path) {
                "/isAlive/3" -> HttpResponse(403, """{"error_message":"forbidden"}""")
                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)

        assertTrue(repository.restoreSession())
        assertEquals("the login must survive a 403", AuthCredentials(3L, "live"), storage.credentials())
        assertNotNull(repository.credentials.value)
        assertFalse(repository.sessionExpired.value)
        assertEquals(AuthRestoreState.READY, repository.authState.value)
    }

    @Test
    fun `restore session keeps a token when isAlive reports a server error`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(3L, "live"), "alice")
        }
        val transport = ScriptedTransport { _, path ->
            when (path) {
                "/isAlive/3" -> HttpResponse(500, """{"error_message":"boom"}""")
                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)

        assertTrue(repository.restoreSession())
        assertNotNull(storage.credentials())
        assertFalse(repository.sessionExpired.value)
        // Unverifiable still means signed in: observers must see the token so the list
        // loads (and reports its own network error) instead of showing the auth screen.
        assertNotNull(repository.credentials.value)
    }

    @Test
    fun `opening a chat that fails clears the spinner and keeps a recoverable error`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        val transport = ScriptedTransport { _, path ->
            when {
                path == "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")
                path == "/lastMessage/9" -> HttpResponse(200, """{"data":{"hasMessage":false}}""")
                path == "/getMessages/9" -> HttpResponse(500, """{"error_message":"boom"}""")
                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.login(1L, "secret1")

        val error = runCatching { repository.openChat(9) }.exceptionOrNull()

        assertNotNull("the failure must reach the caller", error)
        val snapshot = repository.chat.value!!
        assertFalse("the spinner must stop even on failure", snapshot.isLoadingInitial)
        assertEquals("boom", snapshot.error)
        assertEquals(9L, snapshot.sessionId)
    }

    @Test
    fun `opening a second chat drops the first transcript`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        val transport = ScriptedTransport { _, path ->
            when {
                path == "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")
                path == "/lastMessage/9" -> HttpResponse(200, """{"data":{"hasMessage":false}}""")
                path == "/getMessages/9" -> HttpResponse(
                    200,
                    """{"data":[{"id":1,"sender":1,"pieces":[{"type":"text","text":"old"}]}]}"""
                )

                path == "/receivedMessageSeq/1/10" -> HttpResponse(200, """{"data":{"seq":0}}""")
                path == "/lastMessage/10" -> HttpResponse(200, """{"data":{"hasMessage":false}}""")
                path == "/getMessages/10" -> HttpResponse(200, """{"data":[]}""")
                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.login(1L, "secret1")

        repository.openChat(9)
        assertEquals(1, repository.chat.value!!.messages.size)

        repository.openChat(10)
        assertTrue(repository.chat.value!!.messages.isEmpty())
        assertEquals(10L, repository.chat.value!!.sessionId)
    }

    @Test
    fun `polling start and stop are observable and idempotent`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        val transport = ScriptedTransport { _, path ->
            when {
                path == "/lastMessage/9" ->
                    HttpResponse(200, """{"data":{"hasMessage":true,"id":1}}""")

                path == "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")
                path == "/getMessages/9" -> HttpResponse(
                    200,
                    """{"data":[{"id":1,"sender":1,"pieces":[{"type":"text","text":"a"}]}]}"""
                )

                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.login(1L, "secret1")
        repository.openChat(9)

        assertFalse(repository.isPolling)
        repository.startPolling(intervalMillis = 60_000L)
        assertTrue(repository.isPolling)
        assertTrue(repository.chat.value!!.isPolling)

        // A second start must not stack a second loop.
        repository.startPolling(intervalMillis = 60_000L)

        repository.stopPolling()
        assertFalse(repository.isPolling)
        assertFalse(repository.chat.value!!.isPolling)

        repository.stopPolling()
        assertFalse(repository.isPolling)
    }

    @Test
    fun `session details surface an error when every lookup fails`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        val transport = ScriptedTransport { _, path ->
            when (path) {
                "/getJoinedSessions/1" -> HttpResponse(200, """{"data":{"sessions":[20]}}""")
                "/getSessions" -> HttpResponse(500, """{"error_message":"boom"}""")
                "/getSessionInfo/20" -> HttpResponse(500, """{"error_message":"boom"}""")
                "/getSession/20" -> HttpResponse(500, """{"error_message":"boom"}""")
                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.login(1L, "secret1")

        val error = runCatching { repository.refreshSessions() }.exceptionOrNull()

        assertNotNull("a total resolution failure must not look like an empty list", error)
        assertTrue(error is dev.oxype.client.data.oxype.OxypeException)
    }

    @Test
    fun `session details keep the readable sessions when only one id is stale`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        val transport = ScriptedTransport { _, path ->
            when (path) {
                "/getJoinedSessions/1" -> HttpResponse(200, """{"data":{"sessions":[20,21]}}""")
                "/getSessions" -> HttpResponse(200, """{"data":{"unexpected":true}}""")
                "/getSessionInfo/20" -> HttpResponse(
                    200,
                    """{"data":{"sessionId":20,"sessionName":"丙","owner":1,
                        "users":[{"userid":1,"username":"alice"}]}}"""
                )

                "/getSessionInfo/21" -> HttpResponse(404, """{"error_message":"gone"}""")
                "/getSession/21" -> HttpResponse(404, """{"error_message":"gone"}""")
                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.login(1L, "secret1")
        repository.refreshSessions()

        assertEquals(listOf(20L), repository.sessions.value.map { it.sessionId })
        assertEquals("丙", repository.sessions.value.single().name)
    }

    @Test
    fun `creating a session refreshes the list and returns the invite`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        var created = false
        val transport = ScriptedTransport { _, path ->
            when {
                path == "/createSession" -> {
                    created = true
                    HttpResponse(
                        200,
                        """{"data":{"session_id":33,"sessionName":"新会话","inviteUuid":"uuid-1"}}"""
                    )
                }

                path == "/getJoinedSessions/1" -> HttpResponse(
                    200,
                    if (created) """{"data":{"sessions":[33]}}""" else """{"data":{"sessions":[]}}"""
                )

                path == "/getSessions" -> HttpResponse(
                    200,
                    """{"data":{"sessions":[{"sessionId":33,"sessionName":"新会话"}]}}"""
                )

                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.login(1L, "secret1")

        val result = repository.createSession("新会话", "")
        assertEquals(33L, result.sessionId)
        assertEquals("uuid-1", result.inviteUuid)
        assertEquals(listOf(33L), repository.sessions.value.map { it.sessionId })
    }

    @Test
    fun `join by uuid returns the joined session`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        val transport = ScriptedTransport { _, path ->
            when (path) {
                "/joinSessionByUuid" -> HttpResponse(
                    200,
                    """{"data":{"sessionId":44,"sessionName":"加入的会话"}}"""
                )

                "/getJoinedSessions/1" -> HttpResponse(200, """{"data":{"sessions":[44]}}""")
                "/getSessions" -> HttpResponse(
                    200,
                    """{"data":{"sessions":[{"sessionId":44,"sessionName":"加入的会话"}]}}"""
                )

                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.login(1L, "secret1")

        val detail = repository.joinSession("uuid-2")
        assertEquals(44L, detail.sessionId)
        assertEquals("加入的会话", detail.name)
    }

    @Test
    fun `polling a backlog larger than one window walks the middle range instead of skipping it`() =
        runBlocking {
            val storage = FakeSessionStorage().apply {
                saveAuth(AuthCredentials(1L, "tok"), "alice")
            }
            var serverNewest = 70L
            val transport = ScriptedTransport { request, path ->
                when {
                    path == "/lastMessage/9" ->
                        HttpResponse(200, """{"data":{"hasMessage":true,"id":$serverNewest}}""")

                    path == "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")

                    path == "/getMessages/9" -> {
                        val payload = JSONObject(request.body!!)
                        val start = payload.getLong("start").coerceAtLeast(1L)
                        val end = payload.getLong("end").coerceAtMost(serverNewest)
                        val items = (start..end).joinToString(",") {
                            """{"id":$it,"sender":1,"pieces":[{"type":"text","text":"m$it"}]}"""
                        }
                        HttpResponse(200, """{"data":[$items]}""")
                    }

                    else -> null
                }
            }
            val (repository, _) = repository(storage, transport)
            repository.login(1L, "secret1")
            repository.openChat(9)
            assertEquals(70L, repository.chat.value!!.messages.last().id)

            // A long absence: the backlog is far larger than one poll window.
            serverNewest = 200L
            repeat(8) { repository.pollOnce() }

            // Every sequence between the opening anchor and the newest must have been
            // requested; a watermark that jumps to 200 would silently skip 101..199.
            val covered = mutableSetOf<Long>()
            for (request in transport.requests.filter { it.url.contains("/getMessages/9") }) {
                val payload = JSONObject(request.body!!)
                val start = payload.getLong("start").coerceAtLeast(1L)
                val end = payload.getLong("end")
                if (start in 71L..200L) {
                    for (seq in start..minOf(end, 200L)) covered.add(seq)
                }
            }
            val missing = (71L..200L).filterNot { it in covered }
            assertTrue("unrequested backlog sequences: $missing", missing.isEmpty())
            assertEquals(
                (71L..200L).toList(),
                repository.chat.value!!.messages.map { it.id }.filter { it >= 71L }
            )
        }

    @Test
    fun `an older page that returns after a session switch does not touch the new chat`() =
        runBlocking {
            val storage = FakeSessionStorage().apply {
                saveAuth(AuthCredentials(1L, "tok"), "alice")
            }
            val olderRequestStarted = CompletableDeferred<Unit>()
            val olderGate = CompletableDeferred<Unit>()
            val transport = ScriptedTransport { request, path ->
                when {
                    path == "/lastMessage/9" ->
                        HttpResponse(200, """{"data":{"hasMessage":true,"id":130}}""")

                    path == "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")

                    path == "/getMessages/9" -> {
                        val payload = JSONObject(request.body!!)
                        val start = payload.getLong("start").coerceAtLeast(1L)
                        val end = payload.getLong("end")
                        if (start < 100L) {
                            // The older page is held until after the switch.
                            olderRequestStarted.complete(Unit)
                            olderGate.await()
                        }
                        val items = (start..end).joinToString(",") {
                            """{"id":$it,"sender":1,"pieces":[{"type":"text","text":"a$it"}]}"""
                        }
                        HttpResponse(200, """{"data":[$items]}""")
                    }

                    path == "/lastMessage/10" ->
                        HttpResponse(200, """{"data":{"hasMessage":true,"id":500}}""")

                    path == "/receivedMessageSeq/1/10" -> HttpResponse(200, """{"data":{"seq":0}}""")

                    path == "/getMessages/10" -> HttpResponse(
                        200,
                        """{"data":[{"id":500,"sender":2,"pieces":[{"type":"text","text":"b"}]}]}"""
                    )

                    else -> null
                }
            }
            val (repository, _) = repository(storage, transport)
            repository.login(1L, "secret1")
            repository.openChat(9)
            assertEquals(30, repository.chat.value!!.messages.size)

            val olderLoad = launch(Dispatchers.Default) { repository.loadOlder() }
            // Wait until A's history request is genuinely in flight, then switch.
            olderRequestStarted.await()
            repository.openChat(10)
            olderGate.complete(Unit)
            olderLoad.join()

            val snapshot = repository.chat.value!!
            assertEquals(10L, snapshot.sessionId)
            assertEquals("A's late history must not leak into B", listOf(500L), snapshot.messages.map { it.id })
        }

    @Test
    fun `a send that returns after a session switch does not overwrite the new transcript`() =
        runBlocking {
            val storage = FakeSessionStorage().apply {
                saveAuth(AuthCredentials(1L, "tok"), "alice")
            }
            val sendStarted = CompletableDeferred<Unit>()
            val sendGate = CompletableDeferred<Unit>()
            val transport = ScriptedTransport { _, path ->
                when {
                    path == "/lastMessage/9" ->
                        HttpResponse(200, """{"data":{"hasMessage":true,"id":1}}""")

                    path == "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")

                    path == "/getMessages/9" -> HttpResponse(
                        200,
                        """{"data":[{"id":1,"sender":1,"pieces":[{"type":"text","text":"a"}]},
                            {"id":2,"sender":1,"pieces":[{"type":"text","text":"hi"}]}]}"""
                    )

                    path == "/sendMessage/9" -> {
                        sendStarted.complete(Unit)
                        sendGate.await()
                        HttpResponse(200, """{"data":{"seq":2}}""")
                    }

                    path == "/lastMessage/10" ->
                        HttpResponse(200, """{"data":{"hasMessage":true,"id":500}}""")

                    path == "/receivedMessageSeq/1/10" -> HttpResponse(200, """{"data":{"seq":0}}""")

                    path == "/getMessages/10" -> HttpResponse(
                        200,
                        """{"data":[{"id":500,"sender":2,"pieces":[{"type":"text","text":"b"}]}]}"""
                    )

                    else -> null
                }
            }
            val (repository, _) = repository(storage, transport)
            repository.login(1L, "secret1")
            repository.openChat(9)

            val sending = launch(Dispatchers.Default) { repository.sendText("hi") }
            // Switch conversations while the send is still awaiting its answer.
            sendStarted.await()
            repository.openChat(10)
            sendGate.complete(Unit)
            sending.join()

            val snapshot = repository.chat.value!!
            assertEquals(10L, snapshot.sessionId)
            assertEquals(
                "A's send must not replace B's transcript",
                listOf(500L),
                snapshot.messages.map { it.id }
            )
            assertFalse(snapshot.isSending)
        }

    @Test
    fun `a rejected token while loading older pages signs the user out`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        var rejectOlder = false
        val transport = ScriptedTransport { request, path ->
            when {
                path == "/lastMessage/9" ->
                    HttpResponse(200, """{"data":{"hasMessage":true,"id":130}}""")

                path == "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")

                path == "/getMessages/9" -> {
                    val payload = JSONObject(request.body!!)
                    val start = payload.getLong("start").coerceAtLeast(1L)
                    val end = payload.getLong("end")
                    if (rejectOlder && start < 100L) {
                        // 401 is the global credential signal (unlike 403, which is a
                        // per-session permission answer).
                        HttpResponse(401, """{"error_message":"Unauthorized"}""")
                    } else {
                        val items = (start..end).joinToString(",") {
                            """{"id":$it,"sender":1,"pieces":[{"type":"text","text":"a$it"}]}"""
                        }
                        HttpResponse(200, """{"data":[$items]}""")
                    }
                }

                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.login(1L, "secret1")
        repository.openChat(9)

        rejectOlder = true
        repository.loadOlder()

        assertNull("a rejected token must clear the login", repository.credentials.value)
        assertTrue(repository.sessionExpired.value)
    }

    @Test
    fun `restore session rethrows cancellation instead of publishing a dead token`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(3L, "tok"), "alice")
        }
        val gate = CompletableDeferred<Unit>()
        val transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse {
                gate.await()
                return HttpResponse(200, """{"data":{"is_alive":true}}""")
            }
        }
        val http = OxypeHttpClient(transport = transport)
        val repository = OxypeRepository(
            api = OxypeApi(http),
            http = http,
            sessionStore = storage,
            scope = scope
        )

        val deferred = async { repository.restoreSession() }
        yield()
        deferred.cancel()

        val outcome = runCatching { deferred.await() }.exceptionOrNull()
        assertTrue(
            "cancellation must not be converted into a signed-out answer",
            outcome is kotlinx.coroutines.CancellationException
        )
        assertEquals(AuthRestoreState.CHECKING, repository.authState.value)
        assertNull(repository.credentials.value)
    }

    @Test
    fun `a resolved restore is not repeated for a rotation`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(3L, "live"), "alice")
        }
        var aliveCalls = 0
        val transport = ScriptedTransport { _, path ->
            when (path) {
                "/isAlive/3" -> {
                    aliveCalls++
                    HttpResponse(200, """{"data":{"is_alive":true}}""")
                }

                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)

        assertTrue(repository.restoreSession())
        // A configuration change re-runs the startup effect; the answer is already
        // known, so the token must not be re-verified (and no placeholder flashes).
        assertTrue(repository.restoreSession())
        assertEquals(1, aliveCalls)
    }

    @Test
    fun `opening a chat that is forbidden reports it without signing out`() = runBlocking {
        // 403 means "authenticated, not allowed" on Oxype. It is a per-session error,
        // so the login must survive and the chat must show a recoverable message.
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        val transport = ScriptedTransport { _, path ->
            when (path) {
                "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")
                "/lastMessage/9" -> HttpResponse(200, """{"data":{"hasMessage":false}}""")
                "/getMessages/9" -> HttpResponse(403, """{"error_message":"forbidden"}""")
                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.login(1L, "secret1")

        val error = runCatching { repository.openChat(9) }.exceptionOrNull()

        assertTrue(error is dev.oxype.client.data.oxype.OxypeException)
        assertEquals(
            dev.oxype.client.data.oxype.OxypeErrorKind.FORBIDDEN,
            (error as dev.oxype.client.data.oxype.OxypeException).kind
        )
        val snapshot = repository.chat.value!!
        assertEquals("forbidden", snapshot.error)
        assertFalse("a 403 must not be a sign-out", snapshot.isLoadingInitial)
        assertNotNull("a 403 must not clear the token", repository.credentials.value)
        assertEquals(AuthCredentials(1L, "tok"), storage.credentials())
        assertFalse(repository.sessionExpired.value)
    }

    @Test
    fun `a 403 while loading older pages is a session error not a sign-out`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        var rejectOlder = false
        val transport = ScriptedTransport { request, path ->
            when {
                path == "/lastMessage/9" ->
                    HttpResponse(200, """{"data":{"hasMessage":true,"id":130}}""")

                path == "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")

                path == "/getMessages/9" -> {
                    val payload = JSONObject(request.body!!)
                    val start = payload.getLong("start").coerceAtLeast(1L)
                    val end = payload.getLong("end")
                    if (rejectOlder && start < 100L) {
                        HttpResponse(403, """{"error_message":"forbidden"}""")
                    } else {
                        val items = (start..end).joinToString(",") {
                            """{"id":$it,"sender":1,"pieces":[{"type":"text","text":"a$it"}]}"""
                        }
                        HttpResponse(200, """{"data":[$items]}""")
                    }
                }

                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.login(1L, "secret1")
        repository.openChat(9)

        rejectOlder = true
        repository.loadOlder()

        val snapshot = repository.chat.value!!
        assertEquals("forbidden", snapshot.error)
        assertFalse("a 403 must not leave the page spinner running", snapshot.isLoadingOlder)
        assertNotNull("a 403 must not clear the token", repository.credentials.value)
        assertEquals(AuthCredentials(1L, "tok"), storage.credentials())
        assertFalse(repository.sessionExpired.value)
    }

    @Test
    fun `an unreadable last message probe makes polling use a speculative window`() =
        runBlocking {
            val storage = FakeSessionStorage().apply {
                saveAuth(AuthCredentials(1L, "tok"), "alice")
            }
            var probeReadable = true
            var messageRequests = 0
            val transport = ScriptedTransport { _, path ->
                when {
                    path == "/lastMessage/9" -> HttpResponse(
                        200,
                        if (probeReadable) {
                            """{"data":{"hasMessage":false}}"""
                        } else {
                            // A payload that says nothing about the transcript: not an
                            // empty chat, just an answer this client cannot read.
                            """{"data":{"unexpected":true}}"""
                        }
                    )

                    path == "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")

                    path == "/getMessages/9" -> {
                        messageRequests++
                        HttpResponse(
                            200,
                            if (probeReadable) {
                                """{"data":[]}"""
                            } else {
                                """{"data":[{"id":1,"sender":1,
                                    "pieces":[{"type":"text","text":"late"}]}]}"""
                            }
                        )
                    }

                    else -> null
                }
            }
            val (repository, _) = repository(storage, transport)
            repository.login(1L, "secret1")
            repository.openChat(9)
            assertEquals(0, repository.chat.value!!.messages.size)

            probeReadable = false
            val before = messageRequests
            assertTrue("an unreadable probe must still discover the message", repository.pollOnce())
            assertTrue("polling must have fetched a window", messageRequests > before)
            assertEquals(listOf(1L), repository.chat.value!!.messages.map { it.id })
        }

    @Test
    fun `a failed open does not claim the top of history was reached`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        val transport = ScriptedTransport { _, path ->
            when (path) {
                "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")
                "/lastMessage/9" -> HttpResponse(200, """{"data":{"hasMessage":false}}""")
                "/getMessages/9" -> HttpResponse(500, """{"error_message":"boom"}""")
                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.login(1L, "secret1")

        runCatching { repository.openChat(9) }

        val snapshot = repository.chat.value!!
        assertFalse(
            "a failed load must stay retryable rather than claiming the earliest message",
            snapshot.reachedTop
        )
    }

    @Test
    fun `an empty initial snapshot does not claim the top of history was reached`() =
        runBlocking {
            val storage = FakeSessionStorage().apply {
                saveAuth(AuthCredentials(1L, "tok"), "alice")
            }
            val transport = ScriptedTransport { _, path ->
                when (path) {
                    // The newest surviving message is 130, so the first window is 101..130.
                    "/lastMessage/9" ->
                        HttpResponse(200, """{"data":{"hasMessage":true,"id":130}}""")

                    "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")
                    // Every sequence in that window was deleted: an empty page, not the
                    // beginning of the conversation.
                    "/getMessages/9" -> HttpResponse(200, """{"data":[]}""")
                    else -> null
                }
            }
            val (repository, _) = repository(storage, transport)
            repository.login(1L, "secret1")
            repository.openChat(9)

            val snapshot = repository.chat.value!!
            assertTrue(snapshot.messages.isEmpty())
            assertFalse(
                "an empty page is not proof that older history does not exist",
                snapshot.reachedTop
            )
        }

    @Test
    fun `an empty older page does not claim the top of history was reached`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        val transport = ScriptedTransport { request, path ->
            when {
                path == "/lastMessage/9" ->
                    HttpResponse(200, """{"data":{"hasMessage":true,"id":130}}""")

                path == "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")

                path == "/getMessages/9" -> {
                    val payload = JSONObject(request.body!!)
                    val start = payload.getLong("start").coerceAtLeast(1L)
                    val end = payload.getLong("end")
                    if (start < 100L) {
                        // Everything in this older window was deleted: an empty page is
                        // normal and must not end paging.
                        HttpResponse(200, """{"data":[]}""")
                    } else {
                        val items = (start..end).joinToString(",") {
                            """{"id":$it,"sender":1,"pieces":[{"type":"text","text":"a$it"}]}"""
                        }
                        HttpResponse(200, """{"data":[$items]}""")
                    }
                }

                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.login(1L, "secret1")
        repository.openChat(9)

        repository.loadOlder()

        val snapshot = repository.chat.value!!
        assertEquals(30, snapshot.messages.size)
        assertFalse("an empty page is not the earliest message", snapshot.reachedTop)
        assertNull(snapshot.error)
    }

    @Test
    fun `opening a chat whose probes both fail fetches a usable window`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        val transport = ScriptedTransport { _, path ->
            when (path) {
                // Both probe routes are unavailable on this server.
                "/receivedMessageSeq/1/9" -> HttpResponse(500, """{"error_message":"boom"}""")
                "/lastMessage/9" -> HttpResponse(500, """{"error_message":"boom"}""")
                "/getMessages/9" -> HttpResponse(
                    200,
                    """{"data":[
                        {"id":1,"sender":1,"pieces":[{"type":"text","text":"a"}]},
                        {"id":2,"sender":1,"pieces":[{"type":"text","text":"b"}]}
                    ]}"""
                )

                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.login(1L, "secret1")

        repository.openChat(9)

        val request = transport.requests.last { it.url.contains("/getMessages/9") }
        val payload = body(request)
        assertFalse(
            "an unknown newest message must not become a 0..0 request",
            payload.getLong("start") == 0L && payload.getLong("end") == 0L
        )
        assertEquals("the fallback window starts at the beginning", 1L, payload.getLong("start"))
        assertTrue("the fallback window is larger than one normal page", payload.getLong("end") > 30L)

        val snapshot = repository.chat.value!!
        assertEquals(listOf(1L, 2L), snapshot.messages.map { it.id })
        assertNull(snapshot.error)
        assertFalse(
            "a window the client picked itself cannot prove the newest page is on screen",
            snapshot.reachedTop
        )
    }

    @Test
    fun `a probe-less open does not skip messages above the guessed window`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        var failProbes = true
        var serverNewest = 2L
        val transport = ScriptedTransport { _, path ->
            when (path) {
                "/receivedMessageSeq/1/9" -> if (failProbes) {
                    HttpResponse(500, """{"error_message":"boom"}""")
                } else {
                    HttpResponse(200, """{"data":{"seq":0}}""")
                }

                "/lastMessage/9" -> if (failProbes) {
                    HttpResponse(500, """{"error_message":"boom"}""")
                } else {
                    HttpResponse(200, """{"data":{"hasMessage":true,"id":$serverNewest}}""")
                }

                "/getMessages/9" -> HttpResponse(
                    200,
                    """{"data":[{"id":1,"sender":1,"pieces":[{"type":"text","text":"a"}]}]}"""
                )

                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.login(1L, "secret1")

        repository.openChat(9)
        assertEquals(listOf(1L), repository.chat.value!!.messages.map { it.id })

        // A message arrives above the guessed window's start. The speculative fetch must
        // not have moved the watermark to the guessed end, or this poll would start past
        // it and the message would be skipped forever.
        failProbes = false
        repository.pollOnce()

        val pollRequest = transport.requests.last { it.url.contains("/getMessages/9") }
        assertEquals(2L, body(pollRequest).getLong("start"))
    }

    @Test
    fun `an empty probe-less open stays retryable instead of claiming the top`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        val transport = ScriptedTransport { _, path ->
            when (path) {
                "/receivedMessageSeq/1/9" -> HttpResponse(500, """{"error_message":"boom"}""")
                "/lastMessage/9" -> HttpResponse(500, """{"error_message":"boom"}""")
                "/getMessages/9" -> HttpResponse(200, """{"data":[]}""")
                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.login(1L, "secret1")

        repository.openChat(9)

        val snapshot = repository.chat.value!!
        assertTrue(snapshot.messages.isEmpty())
        assertNull(snapshot.error)
        assertFalse(
            "an unanchored empty page is not proof that the conversation is exhausted",
            snapshot.reachedTop
        )
    }

    @Test
    fun `paging before any history loaded does not claim the top`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        val transport = ScriptedTransport { _, path ->
            when (path) {
                "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")
                "/lastMessage/9" -> HttpResponse(200, """{"data":{"hasMessage":false}}""")
                // The history request fails, so no window was ever recorded.
                "/getMessages/9" -> HttpResponse(500, """{"error_message":"boom"}""")
                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.login(1L, "secret1")
        runCatching { repository.openChat(9) }

        repository.loadOlder()

        assertFalse(
            "a failed open must leave paging retryable, not claim the earliest message",
            repository.chat.value!!.reachedTop
        )
    }

    @Test
    fun `a forbidden row in the session list is skipped instead of failing the list`() =
        runBlocking {
            val storage = FakeSessionStorage().apply {
                saveAuth(AuthCredentials(1L, "tok"), "alice")
            }
            val transport = ScriptedTransport { _, path ->
                when (path) {
                    "/getJoinedSessions/1" -> HttpResponse(200, """{"data":{"sessions":[20,21]}}""")
                    "/getSessions" -> HttpResponse(403, """{"error_message":"forbidden"}""")
                    "/getSessionInfo/20" -> HttpResponse(
                        200,
                        """{"data":{"sessionId":20,"sessionName":"丙","owner":1,
                            "users":[{"userid":1,"username":"alice"}]}}"""
                    )

                    "/getSessionInfo/21" -> HttpResponse(403, """{"error_message":"forbidden"}""")
                    "/getSession/21" -> HttpResponse(403, """{"error_message":"forbidden"}""")
                    else -> null
                }
            }
            val (repository, _) = repository(storage, transport)
            repository.login(1L, "secret1")

            repository.refreshSessions()

            assertEquals(listOf(20L), repository.sessions.value.map { it.sessionId })
            assertNotNull("a per-session 403 must not clear the token", repository.credentials.value)
            assertFalse(repository.sessionExpired.value)
        }

    @Test
    fun `a read marker does not anchor the first page when the newest probe fails`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        // The read marker is 2, but the conversation already holds far more messages.
        // `lastMessage` is unavailable, so the marker is the only number the client
        // knows — and it must not be mistaken for the newest sequence.
        var newestProbeWorks = false
        var serverNewest = 2L
        val transport = ScriptedTransport { request, path ->
            when {
                path == "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":2}}""")

                path == "/lastMessage/9" -> if (newestProbeWorks) {
                    HttpResponse(200, """{"data":{"hasMessage":true,"id":$serverNewest}}""")
                } else {
                    HttpResponse(500, """{"error_message":"boom"}""")
                }

                path == "/getMessages/9" -> {
                    val payload = JSONObject(request.body!!)
                    val start = payload.getLong("start").coerceAtLeast(1L)
                    val end = payload.getLong("end").coerceAtMost(serverNewest)
                    val items = if (start <= end) {
                        (start..end).joinToString(",") {
                            """{"id":$it,"sender":1,"pieces":[{"type":"text","text":"m$it"}]}"""
                        }
                    } else {
                        ""
                    }
                    HttpResponse(200, """{"data":[$items]}""")
                }

                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.login(1L, "secret1")
        repository.openChat(9)

        val first = body(transport.requests.first { it.url.contains("/getMessages/9") })
        assertEquals(
            "the marker is a read position, not the newest sequence: the first window must start at the beginning",
            1L,
            first.getLong("start")
        )
        assertEquals(
            "an unanchored first screen uses the wide speculative window",
            MessageSyncCoordinator.SPECULATIVE_HISTORY_WINDOW.toLong(),
            first.getLong("end")
        )
        val opened = repository.chat.value!!
        assertEquals(listOf(1L, 2L), opened.messages.map { it.id })
        assertFalse(
            "a window the client guessed cannot prove the newest page is on screen",
            opened.reachedTop
        )

        // A message with a much larger sequence appears. The next poll must continue
        // right above what was actually fetched instead of jumping past the middle.
        newestProbeWorks = true
        serverNewest = 150L
        assertTrue(repository.pollOnce())

        val poll = body(transport.requests.last { it.url.contains("/getMessages/9") })
        assertEquals("the middle range must still be requested", 3L, poll.getLong("start"))
        assertTrue(
            "the poll must not jump to the newest sequence: ${poll.getLong("start")}..${poll.getLong("end")}",
            poll.getLong("end") < serverNewest
        )
        assertEquals(
            "no sequence between the marker and the newest may be skipped",
            (1L..32L).toList(),
            repository.chat.value!!.messages.map { it.id }
        )
    }

    @Test
    fun `a forbidden newest probe denies the session instead of opening an empty chat`() =
        runBlocking {
            // A non-member gets 403 from `/lastMessage` while `receivedMessageSeq` and
            // `getMessages` may soft-return an empty answer. Falling back to a
            // speculative window here would look like a silent, empty conversation.
            val storage = FakeSessionStorage().apply {
                saveAuth(AuthCredentials(1L, "tok"), "alice")
            }
            var messageRequests = 0
            val transport = ScriptedTransport { _, path ->
                when (path) {
                    "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")
                    "/lastMessage/9" -> HttpResponse(403, """{"error_message":"forbidden"}""")
                    "/getMessages/9" -> {
                        messageRequests++
                        HttpResponse(200, """{"data":[]}""")
                    }

                    else -> null
                }
            }
            val (repository, _) = repository(storage, transport)
            repository.login(1L, "secret1")

            val error = runCatching { repository.openChat(9) }.exceptionOrNull()

            assertTrue("a forbidden session must fail the open", error is OxypeException)
            assertEquals(OxypeErrorKind.FORBIDDEN, (error as OxypeException).kind)
            assertEquals(
                "a 403 must not degrade into a speculative message request",
                0,
                messageRequests
            )
            val snapshot = repository.chat.value!!
            assertEquals("forbidden", snapshot.error)
            assertFalse(snapshot.isLoadingInitial)
            assertTrue(snapshot.messages.isEmpty())
            assertNotNull(
                "a per-session denial must not clear the global login",
                repository.credentials.value
            )
            assertEquals(AuthCredentials(1L, "tok"), storage.credentials())
            assertFalse(repository.sessionExpired.value)

            // A denied conversation must not advertise a poller either.
            repository.startPolling()
            assertFalse(repository.isPolling)
        }

    @Test
    fun `a send assigned a sequence far above the watermark does not skip the middle`() =
        runBlocking {
            val storage = FakeSessionStorage().apply {
                saveAuth(AuthCredentials(1L, "tok"), "alice")
            }
            // The newest probe is unavailable and the read marker is 2, so the first
            // screen is the speculative window and the local watermark sits at 2.
            var newestProbeWorks = false
            var serverNewest = 2L
            val transport = ScriptedTransport { request, path ->
                when {
                    path == "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":2}}""")

                    path == "/lastMessage/9" -> if (newestProbeWorks) {
                        HttpResponse(200, """{"data":{"hasMessage":true,"id":$serverNewest}}""")
                    } else {
                        HttpResponse(500, """{"error_message":"boom"}""")
                    }

                    path == "/getMessages/9" -> {
                        val payload = JSONObject(request.body!!)
                        val start = payload.getLong("start").coerceAtLeast(1L)
                        val end = payload.getLong("end").coerceAtMost(serverNewest)
                        val items = if (start <= end) {
                            (start..end).joinToString(",") {
                                """{"id":$it,"sender":1,"pieces":[{"type":"text","text":"m$it"}]}"""
                            }
                        } else {
                            ""
                        }
                        HttpResponse(200, """{"data":[$items]}""")
                    }

                    path == "/sendMessage/9" -> {
                        // Somebody posted a large backlog while this client was idle, so the
                        // new message is assigned a sequence far above the watermark.
                        serverNewest = 150L
                        HttpResponse(200, """{"data":{"seq":150}}""")
                    }

                    else -> null
                }
            }
            val (repository, _) = repository(storage, transport)
            repository.login(1L, "secret1")
            repository.openChat(9)
            assertEquals(listOf(1L, 2L), repository.chat.value!!.messages.map { it.id })

            assertTrue(repository.sendText("hello"))
            assertTrue(
                "the sent message itself is stored",
                repository.chat.value!!.messages.any { it.id == 150L }
            )

            // The middle must not be presumed fetched just because the send was stored.
            newestProbeWorks = true
            assertTrue(repository.pollOnce())

            val poll = body(transport.requests.last { it.url.contains("/getMessages/9") })
            assertEquals(
                "the poll must continue right above the last known sequence",
                3L,
                poll.getLong("start")
            )
            assertEquals(
                "no sequence between the marker and the sent message may be skipped",
                (1L..32L).toList(),
                repository.chat.value!!.messages.map { it.id }.filter { it <= 32L }
            )
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `a forbidden poll stops the loop instead of syncing silently`() = runTest {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        var denied = false
        var probeCalls = 0
        val transport = ScriptedTransport { _, path ->
            when (path) {
                "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")
                "/lastMessage/9" -> {
                    probeCalls++
                    if (denied) {
                        HttpResponse(403, """{"error_message":"forbidden"}""")
                    } else {
                        HttpResponse(200, """{"data":{"hasMessage":true,"id":1}}""")
                    }
                }

                "/getMessages/9" -> HttpResponse(
                    200,
                    """{"data":[{"id":1,"sender":1,"pieces":[{"type":"text","text":"a"}]}]}"""
                )

                else -> null
            }
        }
        val http = OxypeHttpClient(transport = transport, baseUrl = storage.baseUrl)
        val pollScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val repository = OxypeRepository(
            api = OxypeApi(http),
            http = http,
            sessionStore = storage,
            scope = pollScope
        )
        repository.login(1L, "secret1")
        repository.openChat(9)
        testScheduler.runCurrent()
        assertNull(repository.chat.value!!.error)

        denied = true
        repository.startPolling(intervalMillis = 5)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(5)
        testScheduler.runCurrent()

        assertFalse("a denied session must stop its poller", repository.isPolling)
        assertEquals("forbidden", repository.chat.value!!.error)
        assertNotNull("a 403 must not clear the login", repository.credentials.value)
        assertFalse(repository.sessionExpired.value)

        // The loop must stay stopped: no repeated 403 rounds behind a silent screen.
        val callsAfterDenial = probeCalls
        testScheduler.advanceTimeBy(500)
        testScheduler.runCurrent()
        assertEquals("a denied session must not keep probing", callsAfterDenial, probeCalls)

        // Resuming from the foreground must not revive it either.
        repository.startPolling(intervalMillis = 5)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(500)
        testScheduler.runCurrent()
        assertFalse(repository.isPolling)
        assertEquals(callsAfterDenial, probeCalls)
    }

    @Test
    fun `a non array message payload is a protocol error and stays retryable`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        var malformed = true
        val transport = ScriptedTransport { request, path ->
            when (path) {
                "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")
                "/lastMessage/9" ->
                    HttpResponse(200, """{"data":{"hasMessage":true,"id":40}}""")

                "/getMessages/9" -> if (malformed) {
                    // An object where a list belongs: not an empty page.
                    HttpResponse(200, """{"data":{"unexpected":true}}""")
                } else {
                    val payload = JSONObject(request.body!!)
                    val start = payload.getLong("start").coerceAtLeast(1L)
                    val end = payload.getLong("end")
                    val items = (start..end).joinToString(",") {
                        """{"id":$it,"sender":1,"pieces":[{"type":"text","text":"m$it"}]}"""
                    }
                    HttpResponse(200, """{"data":[$items]}""")
                }

                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.login(1L, "secret1")

        val error = runCatching { repository.openChat(9) }.exceptionOrNull()

        assertTrue("a malformed history answer must surface", error is OxypeException)
        assertEquals(OxypeErrorKind.PROTOCOL, (error as OxypeException).kind)
        val failed = repository.chat.value!!
        assertNotNull("the error must be visible", failed.error)
        assertFalse(failed.isLoadingInitial)
        assertTrue("a malformed answer must not look like an empty chat", failed.messages.isEmpty())

        // The failure must be retryable, not a permanent "empty conversation".
        malformed = false
        repository.openChat(9)

        assertEquals((11L..40L).toList(), repository.chat.value!!.messages.map { it.id })
        assertNull(repository.chat.value!!.error)
    }

    @Test
    fun `a non array poll page does not advance the watermark`() = runBlocking {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        var serverNewest = 2L
        var malformedPoll = false
        val transport = ScriptedTransport { request, path ->
            when (path) {
                "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")
                "/lastMessage/9" ->
                    HttpResponse(200, """{"data":{"hasMessage":true,"id":$serverNewest}}""")

                "/getMessages/9" -> {
                    val payload = JSONObject(request.body!!)
                    val start = payload.getLong("start").coerceAtLeast(1L)
                    val end = payload.getLong("end").coerceAtMost(serverNewest)
                    if (malformedPoll && start >= 3L) {
                        HttpResponse(200, """{"data":{"unexpected":true}}""")
                    } else {
                        val items = (start..end).joinToString(",") {
                            """{"id":$it,"sender":1,"pieces":[{"type":"text","text":"m$it"}]}"""
                        }
                        HttpResponse(200, """{"data":[$items]}""")
                    }
                }

                else -> null
            }
        }
        val (repository, _) = repository(storage, transport)
        repository.login(1L, "secret1")
        repository.openChat(9)
        assertEquals(listOf(1L, 2L), repository.chat.value!!.messages.map { it.id })

        serverNewest = 5L
        malformedPoll = true
        val error = runCatching { repository.pollOnce() }.exceptionOrNull()
        assertEquals(
            "an unreadable poll page must be reported, not treated as an empty range",
            OxypeErrorKind.PROTOCOL,
            (error as OxypeException).kind
        )
        assertNotNull(repository.chat.value!!.error)

        // The unread window must be requested again: the watermark may not move past it.
        malformedPoll = false
        assertTrue(repository.pollOnce())

        val poll = body(transport.requests.last { it.url.contains("/getMessages/9") })
        assertEquals("the skipped range must be retried", 3L, poll.getLong("start"))
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), repository.chat.value!!.messages.map { it.id })
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `cancelling the poll loop does not surface an unknown error`() = runTest {
        // stopPolling cancels the loop. That cancellation is the app going to the
        // background, not a failure, so it must not leave "发生了未知错误" on screen.
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        val pollStarted = CompletableDeferred<Unit>()
        var blockPoll = false
        val transport = ScriptedTransport { _, path ->
            when (path) {
                "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")
                "/lastMessage/9" -> HttpResponse(
                    200,
                    if (blockPoll) {
                        """{"data":{"hasMessage":true,"id":5}}"""
                    } else {
                        """{"data":{"hasMessage":false}}"""
                    }
                )

                "/getMessages/9" -> if (blockPoll) {
                    pollStarted.complete(Unit)
                    awaitCancellation()
                } else {
                    HttpResponse(200, """{"data":[]}""")
                }

                else -> null
            }
        }
        val http = OxypeHttpClient(transport = transport, baseUrl = storage.baseUrl)
        val pollScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val repository = OxypeRepository(
            api = OxypeApi(http),
            http = http,
            sessionStore = storage,
            scope = pollScope
        )
        repository.login(1L, "secret1")
        repository.openChat(9)
        testScheduler.runCurrent()
        assertNull(repository.chat.value!!.error)

        blockPoll = true
        repository.startPolling(intervalMillis = 5)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(5)
        testScheduler.runCurrent()
        pollStarted.await()

        repository.stopPolling()
        testScheduler.runCurrent()

        assertNull(
            "stopping the poller must not be reported as an error",
            repository.chat.value!!.error
        )
        assertFalse(repository.isPolling)
        assertFalse(repository.chat.value!!.isPolling)
    }
}
