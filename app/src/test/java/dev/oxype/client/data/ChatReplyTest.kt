package dev.oxype.client.data

import dev.oxype.client.data.oxype.AuthCredentials
import dev.oxype.client.data.oxype.HttpRequest
import dev.oxype.client.data.oxype.HttpResponse
import dev.oxype.client.data.session.SessionStorage
import dev.oxype.client.data.session.ThemeMode
import dev.oxype.client.testsupport.ScriptedTransport
import dev.oxype.client.testsupport.repositoryWith
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sending a reply.
 *
 * Two requests have to stay distinguishable: a plain send must still look exactly like
 * it did before replies existed, and a reply must lead with a `reply` piece that carries
 * only `messageId` (the server ignores a client-supplied text snapshot).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatReplyTest {

    private class FakeStorage : SessionStorage {
        override var baseUrl: String = "http://example.test:12345"
        override var themeMode: ThemeMode = ThemeMode.SYSTEM
        private var credentials: AuthCredentials? = AuthCredentials(1L, "tok")
        private var name: String? = null

        override fun credentials(): AuthCredentials? = credentials
        override fun username(): String? = name
        override fun saveAuth(credentials: AuthCredentials, username: String?) {
            this.credentials = credentials
            name = username
        }

        override fun clearAuth() {
            credentials = null
            name = null
        }

        override fun hasStoredAuth(): Boolean = credentials != null
    }

    private fun openChatTransport(
        sendResponse: HttpResponse,
        storedPieces: String = """[{"type":"text","text":"收到"}]"""
    ) = ScriptedTransport { _, route ->
        when {
            route.startsWith("/login/") -> HttpResponse(200, """{"data":{"token":"tok"}}""")

            route.startsWith("/lastMessage/") ->
                HttpResponse(200, """{"data":{"hasMessage":true,"id":8}}""")

            route.startsWith("/getMessages/") ->
                HttpResponse(
                    200,
                    """{"data":[{"id":8,"sender":1,"timestamp":1767225600000,"pieces":$storedPieces}]}"""
                )

            route.startsWith("/sendMessage/") -> sendResponse

            else -> null
        }
    }

    private fun sendBody(request: HttpRequest): JSONObject = JSONObject(request.body!!)

    @Test
    fun `a reply leads with a reply piece carrying only the message id`() = runTest {
        val transport = openChatTransport(HttpResponse(200, """{"data":{"seq":9}}"""))
        val repository = repositoryWith(FakeStorage(), transport, this)

        repository.login(1L, "pw")
        repository.openChat(42L)
        repository.sendText("收到", replyTo = 7L)

        val pieces = sendBody(transport.requests.last { it.url.contains("/sendMessage/") })
            .getJSONArray("pieces")
        assertEquals(2, pieces.length())
        val reply = pieces.getJSONObject(0)
        assertEquals("reply", reply.getString("type"))
        assertEquals(7L, reply.getLong("messageId"))
        // The server fills the snapshot; sending a text here would be ignored at best and
        // could look like a client-side feature that does not exist.
        assertFalse(reply.has("text"))
        val text = pieces.getJSONObject(1)
        assertEquals("text", text.getString("type"))
        assertEquals("收到", text.getString("text"))
    }

    @Test
    fun `a plain send keeps the single text piece shape`() = runTest {
        val transport = openChatTransport(HttpResponse(200, """{"data":{"seq":9}}"""))
        val repository = repositoryWith(FakeStorage(), transport, this)

        repository.login(1L, "pw")
        repository.openChat(42L)
        repository.sendText("收到")

        val pieces = sendBody(transport.requests.last { it.url.contains("/sendMessage/") })
            .getJSONArray("pieces")
        assertEquals(1, pieces.length())
        assertEquals("text", pieces.getJSONObject(0).getString("type"))
        assertEquals("收到", pieces.getJSONObject(0).getString("text"))
    }

    @Test
    fun `a missing reply target surfaces the server message and keeps the login`() = runTest {
        val transport = openChatTransport(
            HttpResponse(
                404,
                """{"error_message":"Message 7 to reply to was not found"}"""
            )
        )
        val repository = repositoryWith(FakeStorage(), transport, this)

        repository.login(1L, "pw")
        repository.openChat(42L)
        val sent = repository.sendText("收到", replyTo = 7L)

        assertFalse(sent)
        assertEquals(
            "Message 7 to reply to was not found",
            repository.chat.value?.error
        )
        // A 404 is about one message, never about the token.
        assertNotNull(repository.credentials.value)
    }

    @Test
    fun `a sent reply comes back with its quote intact`() = runTest {
        val stored = """
            [
              {"type":"reply","messageId":7,"sender":2,"senderName":"林间小道","text":"在那边等你们"},
              {"type":"text","text":"收到"}
            ]
        """.trimIndent()
        val transport = openChatTransport(
            sendResponse = HttpResponse(200, """{"data":{"seq":8}}"""),
            storedPieces = stored
        )
        val repository = repositoryWith(FakeStorage(), transport, this)

        repository.login(1L, "pw")
        repository.openChat(42L)
        // Overwrite the tail re-read so the sent sequence is present in the answer.
        val sent = repository.sendText("收到", replyTo = 7L)

        assertTrue(sent)
        val tailRequest = transport.requests.last { it.url.contains("/getMessages/") }
        val window = sendBody(tailRequest)
        assertEquals(8L, window.getLong("end"))
        assertEquals(4L, window.getLong("start"))
        val message = repository.chat.value?.messages?.firstOrNull { it.id == 8L }
        assertNotNull(message)
        assertEquals(7L, message!!.reply?.messageId)
        assertEquals("在那边等你们", message.reply?.text)
        assertNull(repository.chat.value?.error)
    }
}
