package dev.oxype.client.data.oxype

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Envelope, alias, and error-mapping tests for the HTTP client and API layer.
 *
 * A fake transport is used instead of a live server: the interesting behaviour is how
 * responses are interpreted, not that the socket works.
 */
class OxypeHttpClientTest {

    /** Records the request it was given and replays one canned response. */
    private class FakeTransport(
        private val response: HttpResponse
    ) : HttpTransport {
        var lastRequest: HttpRequest? = null
            private set

        override suspend fun execute(request: HttpRequest): HttpResponse {
            lastRequest = request
            return response
        }
    }

    private fun client(transport: HttpTransport, baseUrl: String = "http://example.test:12345") =
        OxypeHttpClient(transport = transport, baseUrl = baseUrl)

    private fun jsonResponse(body: String, status: Int = 200) =
        HttpResponse(status = status, body = body)

    @Test
    fun `unwraps kalmia data envelope`() = runBlocking {
        val transport = FakeTransport(
            jsonResponse("""{"request_type":"GET","data":{"seq":7},"http_meta":{"http_status":200}}""")
        )
        val payload = client(transport).postJson("receivedMessageSeq/1/2", JSONObject())

        assertTrue(payload is JSONObject)
        assertEquals(7L, OxypeJson.long(payload as JSONObject, "seq"))
    }

    @Test
    fun `accepts bare array payload without envelope`() = runBlocking {
        val transport = FakeTransport(jsonResponse("""[{"id":1},{"id":2}]"""))
        val payload = client(transport).postJson("getMessages/1", JSONObject())

        assertTrue(payload is JSONArray)
        assertEquals(2, (payload as JSONArray).length())
    }

    @Test
    fun `unwraps data that is itself an array`() = runBlocking {
        val transport = FakeTransport(jsonResponse("""{"data":[{"id":3}]}"""))
        val payload = client(transport).postJson("getMessages/1", JSONObject())

        assertTrue(payload is JSONArray)
        assertEquals(1, (payload as JSONArray).length())
    }

    @Test
    fun `empty body decodes to null instead of failing`() = runBlocking {
        val transport = FakeTransport(HttpResponse(status = 200, body = ""))
        assertNull(client(transport).postJson("logout/1", JSONObject()))
    }

    @Test
    fun `maps 400 to bad request with server message`() = runBlocking {
        val transport = FakeTransport(
            jsonResponse(
                """{"error_message":"密码长度不符合要求","internal_error_name":"Bad Request"}""",
                status = 400
            )
        )
        val error = runCatching {
            runBlocking { client(transport).postJson("register", JSONObject()) }
        }.exceptionOrNull()

        val oxype = error as OxypeException
        assertEquals(OxypeErrorKind.BAD_REQUEST, oxype.kind)
        assertEquals("密码长度不符合要求", oxype.message)
        assertEquals(400, oxype.httpStatus)
    }

    @Test
    fun `prefers specific message over generic protocol banner`() = runBlocking {
        val transport = FakeTransport(
            jsonResponse(
                """{
                    "error":"Server protocol error (Kalmia/1.0.34, HTTP/1.1): Unauthorized",
                    "error_message":"Unauthorized"
                }""",
                status = 401
            )
        )
        val error = runCatching {
            runBlocking { client(transport).get("getJoinedSessions/1") }
        }.exceptionOrNull() as OxypeException

        assertEquals(OxypeErrorKind.UNAUTHORIZED, error.kind)
        assertEquals("Unauthorized", error.message)
    }

    @Test
    fun `maps 401 403 404 500 to distinct kinds`() = runBlocking {
        suspend fun kindFor(status: Int): OxypeErrorKind {
            val transport = FakeTransport(jsonResponse("{}", status = status))
            return (runCatching {
                client(transport).postJson("x", JSONObject())
            }.exceptionOrNull() as OxypeException).kind
        }

        assertEquals(OxypeErrorKind.UNAUTHORIZED, kindFor(401))
        assertEquals(OxypeErrorKind.FORBIDDEN, kindFor(403))
        assertEquals(OxypeErrorKind.NOT_FOUND, kindFor(404))
        assertEquals(OxypeErrorKind.SERVER, kindFor(500))
    }

    @Test
    fun `network failure surfaces as network kind`() = runBlocking {
        val transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse {
                throw OxypeException(OxypeErrorKind.NETWORK, "无法连接服务器")
            }
        }
        val error = runCatching {
            client(transport).get("getJoinedSessions/1")
        }.exceptionOrNull() as OxypeException

        assertEquals(OxypeErrorKind.NETWORK, error.kind)
    }

    @Test
    fun `authenticated post carries userid and token in body`() = runBlocking {
        val transport = FakeTransport(jsonResponse("""{"seq":1}"""))
        client(transport).postJson(
            path = "sendMessage/9",
            body = JSONObject().put("pieces", JSONArray()),
            credentials = AuthCredentials(userId = 42L, token = "tok en+")
        )

        val body = JSONObject(transport.lastRequest!!.body!!)
        assertEquals(42L, body.getLong("userid"))
        assertEquals("tok en+", body.getString("token"))
    }

    @Test
    fun `authenticated get carries userid and token in query`() = runBlocking {
        val transport = FakeTransport(jsonResponse("""{"sessions":[]}"""))
        client(transport).get(
            path = "getJoinedSessions/42",
            credentials = AuthCredentials(userId = 42L, token = "a b+c")
        )

        val url = transport.lastRequest!!.url
        assertTrue("userid must be a query parameter: $url", url.contains("userid=42"))
        // The token is URL-encoded, so a space becomes '+' and a literal '+' becomes %2B.
        assertTrue("token must be encoded: $url", url.contains("token=a+b%2Bc"))
    }

    @Test
    fun `base url normalisation adds scheme and strips trailing slash`() {
        val http = client(FakeTransport(jsonResponse("{}")))
        assertEquals("http://host:1234", http.normalizeBaseUrl("host:1234/"))
        assertEquals("https://host", http.normalizeBaseUrl("  https://host/  "))
        assertEquals(OxypeHttpClient.DEFAULT_BASE_URL, http.normalizeBaseUrl("   "))
    }

    @Test
    fun `api reads is_alive alias and boolean payload`() = runBlocking {
        val transport = FakeTransport(jsonResponse("""{"data":{"is_alive":false}}"""))
        val api = OxypeApi(client(transport))

        assertFalse(api.isAlive(AuthCredentials(1L, "t")))
    }

    @Test
    fun `api accepts isAlive camelCase alias`() = runBlocking {
        val transport = FakeTransport(jsonResponse("""{"data":{"isAlive":true}}"""))
        val api = OxypeApi(client(transport))

        assertTrue(api.isAlive(AuthCredentials(1L, "t")))
    }

    @Test
    fun `register reads userid alias`() = runBlocking {
        val transport = FakeTransport(jsonResponse("""{"data":{"userId":9,"token":"abc"}}"""))
        val credentials = OxypeApi(client(transport)).register("name", "secret123")

        assertEquals(9L, credentials.userId)
        assertEquals("abc", credentials.token)
    }

    @Test
    fun `joined sessions accepts sessions key inside envelope`() = runBlocking {
        val transport = FakeTransport(
            jsonResponse("""{"data":{"isEmpty":false,"sessions":[1,2,3]}}""")
        )
        val ids = OxypeApi(client(transport)).joinedSessionIds(AuthCredentials(1L, "t"))

        assertEquals(listOf(1L, 2L, 3L), ids)
    }

    @Test
    fun `session records tolerate snake_case and camelCase aliases`() = runBlocking {
        val transport = FakeTransport(
            jsonResponse(
                """{"data":{"sessions":[
                    {"session_id":5,"sessionName":"甲","owner":1,"members":[1,2],"admins":[2]},
                    {"sessionId":6,"name":"乙","ownerId":2}
                ]}}"""
            )
        )
        val sessions = OxypeApi(client(transport)).sessionsByIds(listOf(5L, 6L))

        assertEquals(2, sessions.size)
        assertEquals(5L, sessions[0].sessionId)
        assertEquals("甲", sessions[0].name)
        assertEquals(1L, sessions[0].ownerId)
        assertEquals(listOf(1L, 2L), sessions[0].memberIds)
        assertEquals(listOf(2L), sessions[0].adminIds)
        assertEquals(6L, sessions[1].sessionId)
        assertEquals("乙", sessions[1].name)
        assertEquals(2L, sessions[1].ownerId)
    }

    @Test
    fun `message pieces decode text and markdown, ignoring unknown types`() = runBlocking {
        val transport = FakeTransport(
            jsonResponse(
                """{"data":[
                    {"id":1,"sender":2,"timestamp":10,"pieces":[
                        {"type":"text","text":"hello "},
                        {"type":"markdown","text":"*world*"},
                        {"type":"future-media","text":"x"}
                    ]},
                    {"id":2,"sender":3,"editedAt":99,"pieces":[{"type":"text","text":"edited"}]}
                ]}"""
            )
        )
        val messages = OxypeApi(client(transport))
            .messages(AuthCredentials(1L, "t"), sessionId = 1, start = 1, end = 2)

        assertEquals(2, messages.size)
        assertEquals("hello *world*", messages[0].plainText)
        assertEquals(2, messages[0].pieces.size)
        assertFalse(messages[0].isEdited)
        assertTrue(messages[1].isEdited)
        assertEquals(99L, messages[1].editedAtMillis)
    }

    @Test
    fun `messages accepts list key instead of bare array`() = runBlocking {
        val transport = FakeTransport(
            jsonResponse("""{"data":{"list":[{"id":4,"sender":1,"pieces":[{"type":"text","text":"a"}]}]}}""")
        )
        val messages = OxypeApi(client(transport))
            .messages(AuthCredentials(1L, "t"), sessionId = 1, start = 1, end = 4)

        assertEquals(1, messages.size)
        assertEquals(4L, messages[0].id)
    }

    @Test
    fun `a non array messages payload is a protocol error not an empty page`() = runBlocking {
        // An object (or a string, or a number) where a list belongs means this client
        // cannot read the answer. Returning an empty list would be indistinguishable
        // from a conversation whose whole range was deleted, and the caller would then
        // advance its watermark past messages it never saw.
        val bodies = listOf(
            """{"data":{"unexpected":true}}""",
            """{"data":"nope"}""",
            """{"data":7}""",
            """{"data":null}""",
            """{"data":{"messages":"nope"}}"""
        )
        for (body in bodies) {
            val error = runCatching {
                OxypeApi(client(FakeTransport(jsonResponse(body))))
                    .messages(AuthCredentials(1L, "t"), sessionId = 1, start = 1, end = 4)
            }.exceptionOrNull()

            assertTrue("a non-array payload must fail: $body", error is OxypeException)
            assertEquals(
                "a non-array payload must be a protocol error: $body",
                OxypeErrorKind.PROTOCOL,
                (error as OxypeException).kind
            )
        }
    }

    @Test
    fun `an empty messages payload is still an empty page`() = runBlocking {
        // The empty body is a legitimate "nothing in this range" answer: every slot in
        // the window may have been deleted.
        assertTrue(
            OxypeApi(client(FakeTransport(HttpResponse(200, ""))))
                .messages(AuthCredentials(1L, "t"), sessionId = 1, start = 1, end = 4)
                .isEmpty()
        )
        assertTrue(
            OxypeApi(client(FakeTransport(jsonResponse("""{"data":[]}"""))))
                .messages(AuthCredentials(1L, "t"), sessionId = 1, start = 1, end = 4)
                .isEmpty()
        )
    }

    @Test
    fun `a non object entry in the message list is a protocol error`() = runBlocking {
        // A number or a string where a message belongs means this client cannot read the
        // window. Filtering it out would leave a shorter list that looks like a partly
        // deleted range, and the caller would acknowledge the window.
        val bodies = listOf(
            """{"data":[1,2]}""",
            """{"data":["nope"]}""",
            """{"data":[{"id":1,"sender":1,"pieces":[{"type":"text","text":"a"}]},2]}"""
        )
        for (body in bodies) {
            val error = runCatching {
                OxypeApi(client(FakeTransport(jsonResponse(body))))
                    .messages(AuthCredentials(1L, "t"), sessionId = 1, start = 1, end = 4)
            }.exceptionOrNull()

            assertTrue("a malformed entry must fail: $body", error is OxypeException)
            assertEquals(
                "a malformed entry must be a protocol error: $body",
                OxypeErrorKind.PROTOCOL,
                (error as OxypeException).kind
            )
        }
    }

    @Test
    fun `a message missing its required fields is a protocol error`() = runBlocking {
        // id, sender and content are what a bubble needs. Accepting the record without
        // them would either paint a blank row or drop it and claim the sequence was read.
        val bodies = listOf(
            """{"data":[{"id":"abc","sender":1,"pieces":[{"type":"text","text":"a"}]}]}""",
            """{"data":[{"sender":1,"pieces":[{"type":"text","text":"a"}]}]}""",
            """{"data":[{"id":1,"pieces":[{"type":"text","text":"a"}]}]}""",
            """{"data":[{"id":1,"sender":1}]}""",
            """{"data":[{"id":1,"sender":1,"pieces":[]}]}""",
            """{"data":[{"id":1,"sender":1,"pieces":[{"type":"text"}]}]}"""
        )
        for (body in bodies) {
            val error = runCatching {
                OxypeApi(client(FakeTransport(jsonResponse(body))))
                    .messages(AuthCredentials(1L, "t"), sessionId = 1, start = 1, end = 4)
            }.exceptionOrNull()

            assertTrue("an incomplete record must fail: $body", error is OxypeException)
            assertEquals(
                "an incomplete record must be a protocol error: $body",
                OxypeErrorKind.PROTOCOL,
                (error as OxypeException).kind
            )
        }
    }

    @Test
    fun `message field aliases still decode`() = runBlocking {
        // The known spellings must keep working: strictness is about *missing* data, not
        // about a renamed field.
        val transport = FakeTransport(
            jsonResponse(
                """{"data":[
                    {"seq":3,"senderId":2,"time":5,"pieces":[{"text":"aliased"}]},
                    {"messageId":"4","sender_id":2,"pieces":[{"type":"md","value":"*m*"}]}
                ]}"""
            )
        )
        val messages = OxypeApi(client(transport))
            .messages(AuthCredentials(1L, "t"), sessionId = 1, start = 1, end = 4)

        assertEquals(listOf(3L, 4L), messages.map { it.id })
        assertEquals(2L, messages[0].senderId)
        assertEquals(5L, messages[0].timestampMillis)
        assertEquals("aliased", messages[0].plainText)
        assertEquals(MessagePieceKind.MARKDOWN, messages[1].pieces.single().kind)
    }

    @Test
    fun `a forbidden last message probe is a forbidden error not an empty chat`() = runBlocking {
        // A non-member gets 403 from this route. It must stay distinguishable from an
        // unreadable payload so the caller can treat it as a session-level denial.
        val transport = FakeTransport(
            jsonResponse("""{"error_message":"forbidden"}""", status = 403)
        )
        val error = runCatching {
            OxypeApi(client(transport)).lastMessage(AuthCredentials(1L, "t"), 1)
        }.exceptionOrNull()

        assertTrue(error is OxypeException)
        assertEquals(OxypeErrorKind.FORBIDDEN, (error as OxypeException).kind)
        assertEquals(403, (error as OxypeException).httpStatus)
    }

    @Test
    fun `send message returns null for soft refusal seq -1`() = runBlocking {
        val transport = FakeTransport(jsonResponse("""{"data":{"seq":-1}}"""))
        val seq = OxypeApi(client(transport))
            .sendText(AuthCredentials(1L, "t"), sessionId = 1, text = "hi")

        assertNull(seq)
    }

    @Test
    fun `send message returns assigned sequence`() = runBlocking {
        val transport = FakeTransport(jsonResponse("""{"data":{"seq":12}}"""))
        val seq = OxypeApi(client(transport))
            .sendText(AuthCredentials(1L, "t"), sessionId = 1, text = "hi")

        assertEquals(12L, seq)

        val body = JSONObject(transport.lastRequest!!.body!!)
        val pieces = body.getJSONArray("pieces")
        assertEquals("text", pieces.getJSONObject(0).getString("type"))
        assertEquals("hi", pieces.getJSONObject(0).getString("text"))
    }

    @Test
    fun `delete message reports soft failure as false`() = runBlocking {
        val refusing = FakeTransport(jsonResponse("""{"data":{"success":false}}"""))
        assertFalse(
            OxypeApi(client(refusing))
                .deleteMessage(AuthCredentials(1L, "t"), sessionId = 1, messageId = 5)
        )

        val accepting = FakeTransport(jsonResponse("""{"data":{"success":true}}"""))
        assertTrue(
            OxypeApi(client(accepting))
                .deleteMessage(AuthCredentials(1L, "t"), sessionId = 1, messageId = 5)
        )
    }

    @Test
    fun `session info prefers the POST route and parses members`() = runBlocking {
        val transport = object : HttpTransport {
            val calls = mutableListOf<HttpRequest>()
            override suspend fun execute(request: HttpRequest): HttpResponse {
                calls.add(request)
                return when {
                    request.method == "POST" && request.url.contains("/getSessionInfo/8") ->
                        HttpResponse(
                            200,
                            """{"data":{"sessionId":8,"sessionName":"团队","owner":1,"isOwner":true,
                                "users":[{"userid":1,"username":"alice"},{"userid":2,"username":"bob"}]}}"""
                        )

                    else -> HttpResponse(
                        200,
                        """{"data":{"sessionId":8,"sessionName":"团队","owner":0}}"""
                    )
                }
            }
        }
        val detail = OxypeApi(client(transport)).sessionInfo(AuthCredentials(1L, "t"), 8)

        // POST /getSessionInfo is the route that answers the full session view; GET is
        // only a compatibility fallback.
        assertEquals("POST", transport.calls.first().method)
        assertTrue(transport.calls.first().url.contains("/getSessionInfo/8"))
        assertEquals("团队", detail.name)
        assertTrue(detail.isOwner)
        assertEquals(listOf(1L, 2L), detail.members.map { it.userId })
        assertEquals("bob", detail.members[1].username)
        assertEquals(2, detail.memberCount)
    }

    @Test
    fun `session info falls back to GET when POST is unusable`() = runBlocking {
        val transport = object : HttpTransport {
            val calls = mutableListOf<HttpRequest>()
            override suspend fun execute(request: HttpRequest): HttpResponse {
                calls.add(request)
                return if (request.method == "POST") {
                    HttpResponse(404, """{"error_message":"not found"}""")
                } else {
                    HttpResponse(
                        200,
                        """{"data":{"sessionId":8,"sessionName":"团队","owner":1,
                            "users":[{"userid":1,"username":"alice"}]}}"""
                    )
                }
            }
        }
        val detail = OxypeApi(client(transport)).sessionInfo(AuthCredentials(1L, "t"), 8)

        assertEquals("团队", detail.name)
        assertEquals(listOf("POST", "GET"), transport.calls.map { it.method })
        assertTrue(transport.calls[1].url.contains("/getSession/8"))
    }

    @Test
    fun `session info rejects an incomplete GET payload`() = runBlocking {
        val transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse =
                if (request.method == "POST") {
                    // The POST answer carries no members and no owner, so it cannot be
                    // used and the GET compatibility route is tried.
                    HttpResponse(200, """{"data":{"sessionId":8,"sessionName":"团队"}}""")
                } else {
                    // The stale GET view is equally incomplete: owner 0 and no members.
                    HttpResponse(200, """{"data":{"sessionId":8,"sessionName":"团队","owner":0}}""")
                }
        }
        val error = runCatching {
            OxypeApi(client(transport)).sessionInfo(AuthCredentials(1L, "t"), 8)
        }.exceptionOrNull() as OxypeException

        assertEquals(OxypeErrorKind.PROTOCOL, error.kind)
        assertTrue("message must explain the missing details: ${error.message}", error.message!!.contains("不完整"))
    }

    @Test
    fun `api treats a bare false isAlive answer as dead credentials`() = runBlocking {
        val transport = FakeTransport(HttpResponse(status = 200, body = "false"))
        assertFalse(OxypeApi(client(transport)).isAlive(AuthCredentials(1L, "t")))
    }

    @Test
    fun `client parses bare scalar answers`() = runBlocking {
        val booleanTransport = FakeTransport(HttpResponse(status = 200, body = "true"))
        assertEquals(true, client(booleanTransport).postJson("x", JSONObject()))

        val numberTransport = FakeTransport(HttpResponse(status = 200, body = "12"))
        assertEquals(12L, (client(numberTransport).postJson("x", JSONObject()) as Number).toLong())

        val stringTransport = FakeTransport(HttpResponse(status = 200, body = "\"uuid-1\""))
        assertEquals("uuid-1", client(stringTransport).postJson("x", JSONObject()))
    }

    @Test
    fun `delete message accepts a bare boolean answer`() = runBlocking {
        val accepting = FakeTransport(HttpResponse(status = 200, body = "true"))
        assertTrue(
            OxypeApi(client(accepting))
                .deleteMessage(AuthCredentials(1L, "t"), sessionId = 1, messageId = 5)
        )

        val refusing = FakeTransport(HttpResponse(status = 200, body = "false"))
        assertFalse(
            OxypeApi(client(refusing))
                .deleteMessage(AuthCredentials(1L, "t"), sessionId = 1, messageId = 5)
        )
    }

    @Test
    fun `redirect answer explains the https and redirect problem`() = runBlocking {
        val transport = FakeTransport(
            HttpResponse(status = 302, body = "")
        )
        val error = runCatching {
            client(transport).postJson("login/1", JSONObject())
        }.exceptionOrNull() as OxypeException

        assertEquals(OxypeErrorKind.PROTOCOL, error.kind)
        assertEquals(302, error.httpStatus)
        assertTrue("message must mention https: ${error.message}", error.message!!.contains("https"))
        assertTrue("message must mention redirect: ${error.message}", error.message!!.contains("重定向"))
    }

    @Test
    fun `last message reports an explicit empty answer as empty`() = runBlocking {
        val transport = FakeTransport(jsonResponse("""{"data":{"hasMessage":false}}"""))
        val last = OxypeApi(client(transport)).lastMessage(AuthCredentials(1L, "t"), 1)

        assertTrue("an explicit hasMessage=false is an empty chat", last is LastMessageResult.Empty)
    }

    @Test
    fun `last message keeps a present message`() = runBlocking {
        val transport = FakeTransport(
            jsonResponse("""{"data":{"hasMessage":true,"id":9,"content":"hi"}}""")
        )
        val last = OxypeApi(client(transport)).lastMessage(AuthCredentials(1L, "t"), 1)

        assertTrue(last is LastMessageResult.Present)
        assertEquals(9L, (last as LastMessageResult.Present).info.id)
    }

    @Test
    fun `an unrecognised last message payload is unknown not empty`() = runBlocking {
        val bodies = listOf(
            // No `hasMessage` flag at all: the payload says nothing about the transcript.
            """{"data":{"weird":true}}""",
            // Claims a message but carries no usable sequence.
            """{"data":{"hasMessage":true}}""",
            // A null data node cannot be told apart from an unreadable answer.
            """{"data":null}""",
            // A non-object payload (e.g. a proxy or a different route) is not an answer.
            """{"data":"nope"}"""
        )
        for (body in bodies) {
            val one = OxypeApi(client(FakeTransport(jsonResponse(body))))
                .lastMessage(AuthCredentials(1L, "t"), 1)
            assertTrue(
                "an unreadable payload must not be treated as an empty chat: $body",
                one is LastMessageResult.Unknown
            )
        }
    }

    @Test
    fun `malformed body raises protocol error`() = runBlocking {
        val transport = FakeTransport(HttpResponse(status = 200, body = "{not json"))
        val error = runCatching {
            client(transport).postJson("x", JSONObject())
        }.exceptionOrNull() as OxypeException

        assertEquals(OxypeErrorKind.PROTOCOL, error.kind)
    }

    @Test
    fun `an html redirect keeps its status and explains https`() = runBlocking {
        val transport = FakeTransport(
            HttpResponse(
                status = 301,
                body = "<html><head><title>301 Moved Permanently</title></head><body></body></html>"
            )
        )
        val error = runCatching {
            client(transport).postJson("login/1", JSONObject())
        }.exceptionOrNull() as OxypeException

        assertEquals("the status must survive an HTML body", 301, error.httpStatus)
        assertEquals(OxypeErrorKind.PROTOCOL, error.kind)
        assertTrue("message must mention https: ${error.message}", error.message!!.contains("https"))
        assertTrue("message must mention redirect: ${error.message}", error.message!!.contains("重定向"))
        assertFalse(
            "a parse failure must not replace the status message: ${error.message}",
            error.message!!.contains("无法解析")
        )
    }

    @Test
    fun `an html server error keeps its status and reports a server error`() = runBlocking {
        val transport = FakeTransport(
            HttpResponse(
                status = 500,
                body = "<!DOCTYPE html><html><body><h1>Internal Server Error</h1></body></html>"
            )
        )
        val error = runCatching {
            client(transport).get("getJoinedSessions/1")
        }.exceptionOrNull() as OxypeException

        assertEquals(500, error.httpStatus)
        assertEquals(OxypeErrorKind.SERVER, error.kind)
        assertEquals("服务器内部错误", error.message)
    }

    @Test
    fun `an empty 500 body still reports a server error`() = runBlocking {
        val transport = FakeTransport(HttpResponse(status = 503, body = ""))
        val error = runCatching {
            client(transport).postJson("x", JSONObject())
        }.exceptionOrNull() as OxypeException

        assertEquals(503, error.httpStatus)
        assertEquals(OxypeErrorKind.SERVER, error.kind)
        assertEquals("服务器内部错误", error.message)
    }

    @Test
    fun `a non json 2xx body is still a protocol error`() = runBlocking {
        val transport = FakeTransport(HttpResponse(status = 200, body = "<html>ok</html>"))
        val error = runCatching {
            client(transport).postJson("x", JSONObject())
        }.exceptionOrNull() as OxypeException

        assertEquals(OxypeErrorKind.PROTOCOL, error.kind)
        assertEquals(200, error.httpStatus)
    }
}
