package dev.oxype.client.data

import dev.oxype.client.data.oxype.AuthCredentials
import dev.oxype.client.data.oxype.HttpResponse
import dev.oxype.client.data.oxype.OxypeErrorKind
import dev.oxype.client.data.oxype.OxypeException
import dev.oxype.client.testsupport.FakeSessionStorage
import dev.oxype.client.testsupport.ScriptedTransport
import dev.oxype.client.testsupport.repositoryWith
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocol edges around opening and polling a conversation.
 *
 * The three cases pinned here are the ones a real Oxype deployment produces: a read
 * marker that is far behind the transcript, a non-member session (403 only on
 * `/lastMessage`, empty answers elsewhere), and an unreadable `getMessages` payload.
 */
class ChatAccessRegressionTest {

    private fun storage() = FakeSessionStorage().apply {
        saveAuth(AuthCredentials(1L, "tok"), "alice")
    }

    @Test
    fun `a read marker of two does not anchor the first screen`() = runTest {
        // `lastMessage` is down, `receivedMessageSeq` answers 2. That is how far the
        // account has *read*, not where the transcript ends. The first request must be
        // the wide speculative window from the first sequence.
        var serverNewest = 2L
        var newestProbeWorks = false
        val transport = ScriptedTransport { request, route ->
            when (route) {
                "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":2}}""")

                "/lastMessage/9" -> if (newestProbeWorks) {
                    HttpResponse(200, """{"data":{"hasMessage":true,"id":$serverNewest}}""")
                } else {
                    HttpResponse(500, """{"error_message":"boom"}""")
                }

                "/getMessages/9" -> {
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
        val repository = repositoryWith(storage(), transport, backgroundScope)
        repository.login(1L, "secret1")

        repository.openChat(9)

        val first = JSONObject(
            transport.requests.first { it.url.contains("/getMessages/9") }.body!!
        )
        assertEquals("the first window must start at sequence 1", 1L, first.getLong("start"))
        assertEquals(
            "the first window must be the wide speculative one",
            dev.oxype.client.data.sync.MessageSyncCoordinator.SPECULATIVE_HISTORY_WINDOW.toLong(),
            first.getLong("end")
        )

        val opened = repository.chat.value!!
        assertEquals(listOf(1L, 2L), opened.messages.map { it.id })
        assertFalse("a guessed window cannot prove the top", opened.reachedTop)

        // A burst of new messages: the next poll must still walk the middle range and the
        // watermark must not jump to the server's newest sequence.
        newestProbeWorks = true
        serverNewest = 150L
        assertTrue(repository.pollOnce())

        val poll = JSONObject(
            transport.requests.last { it.url.contains("/getMessages/9") }.body!!
        )
        assertEquals("the middle range must still be requested", 3L, poll.getLong("start"))
        assertTrue(
            "the poll must not jump to the newest sequence: ${poll.getLong("start")}..${poll.getLong("end")}",
            poll.getLong("end") < serverNewest
        )
    }

    @Test
    fun `a non member is a session denial that stops polling without clearing the login`() =
        runTest {
        // The real answer sequence for a non-member: `lastMessage` 403, while
        // `receivedMessageSeq` answers 0 and `getMessages` would answer an empty list.
        var messageRequests = 0
        val transport = ScriptedTransport { _, route ->
            when (route) {
                "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")
                "/lastMessage/9" -> HttpResponse(403, """{"error_message":"forbidden"}""")
                "/getMessages/9" -> {
                    messageRequests++
                    HttpResponse(200, """{"data":[]}""")
                }

                else -> null
            }
        }
        val storage = storage()
        val repository = repositoryWith(storage, transport, backgroundScope)
        repository.login(1L, "secret1")

        val error = runCatching { repository.openChat(9) }.exceptionOrNull()

        assertTrue(error is OxypeException)
        assertEquals(OxypeErrorKind.FORBIDDEN, (error as OxypeException).kind)
        assertEquals(
            "a denial must not degrade into a speculative empty loop",
            0,
            messageRequests
        )
        val snapshot = repository.chat.value!!
        assertNotNull("the denial must be visible", snapshot.error)
        assertTrue(snapshot.messages.isEmpty())
        assertFalse(snapshot.isLoadingInitial)
        assertNotNull("a 403 is per-session, not a sign-out", repository.credentials.value)
        assertEquals(AuthCredentials(1L, "tok"), storage.credentials())
        assertFalse(repository.sessionExpired.value)

        repository.startPolling(intervalMillis = 1L)
        assertFalse("a denied conversation must not poll", repository.isPolling)
    }

    @Test
    fun `an unreadable poll page is a protocol error that keeps its window requestable`() =
        runTest {
        var serverNewest = 2L
        var malformed = true
        val transport = ScriptedTransport { request, route ->
            when (route) {
                "/lastMessage/9" -> HttpResponse(
                    200,
                    """{"data":{"hasMessage":true,"id":$serverNewest}}"""
                )

                "/getMessages/9" -> {
                    val payload = JSONObject(request.body!!)
                    val start = payload.getLong("start").coerceAtLeast(1L)
                    val end = payload.getLong("end").coerceAtMost(serverNewest)
                    if (malformed && start > 2L) {
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
        val repository = repositoryWith(storage(), transport, backgroundScope)
        repository.login(1L, "secret1")
        repository.openChat(9)
        assertEquals(listOf(1L, 2L), repository.chat.value!!.messages.map { it.id })

        serverNewest = 5L
        val error = runCatching { repository.pollOnce() }.exceptionOrNull()

        assertEquals(
            OxypeErrorKind.PROTOCOL,
            (error as OxypeException).kind
        )
        assertNotNull("the failure must be visible", repository.chat.value!!.error)

        malformed = false
        assertTrue(repository.pollOnce())
        val retried = JSONObject(
            transport.requests.last { it.url.contains("/getMessages/9") }.body!!
        )
        assertEquals("the unread window must be requested again", 3L, retried.getLong("start"))
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), repository.chat.value!!.messages.map { it.id })
    }
}
