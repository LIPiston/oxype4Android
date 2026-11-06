package dev.oxype.client.data

import dev.oxype.client.data.oxype.AuthCredentials
import dev.oxype.client.data.oxype.HttpResponse
import dev.oxype.client.testsupport.FakeSessionStorage
import dev.oxype.client.testsupport.ScriptedTransport
import dev.oxype.client.testsupport.repositoryWith
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sender directory (`getSessionUsers`) that a group transcript renders from.
 *
 * Member names are supplementary data: they must load after a conversation opens, they
 * must never block the history, and a failed lookup must leave the transcript usable
 * with numeric fallbacks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatMembersTest {

    private fun storage() = FakeSessionStorage().apply {
        saveAuth(AuthCredentials(1L, "tok"), "alice")
    }

    @Test
    fun `opening a chat exposes the member directory`() = runTest {
        val transport = ScriptedTransport { _, route ->
            when (route) {
                "/lastMessage/9" ->
                    HttpResponse(200, """{"data":{"hasMessage":true,"id":2}}""")

                "/getMessages/9" -> HttpResponse(
                    200,
                    """{"data":[
                        {"id":1,"sender":1,"pieces":[{"type":"text","text":"hi"}]},
                        {"id":2,"sender":2,"pieces":[{"type":"text","text":"hello"}]}
                    ]}"""
                )

                "/getSessionUsers/9" -> HttpResponse(
                    200,
                    """{"data":{"users":[
                        {"userid":1,"username":"alice"},
                        {"userid":2,"username":"bob","avatarUrl":"https://example.test/b.png"}
                    ]}}"""
                )

                else -> null
            }
        }
        val repository = repositoryWith(storage(), transport, backgroundScope)
        repository.login(1L, "secret1")

        repository.openChat(9)
        testScheduler.runCurrent()

        val participants = repository.chat.value!!.participants
        assertEquals(setOf(1L, 2L), participants.keys)
        assertEquals("bob", participants.getValue(2L).username)
        assertEquals("https://example.test/b.png", participants.getValue(2L).avatarUrl)
        assertEquals(listOf(1L, 2L), repository.chat.value!!.messages.map { it.id })
    }

    @Test
    fun `a failed member lookup still shows the history`() = runTest {
        val transport = ScriptedTransport { _, route ->
            when (route) {
                "/lastMessage/9" ->
                    HttpResponse(200, """{"data":{"hasMessage":true,"id":1}}""")

                "/getMessages/9" -> HttpResponse(
                    200,
                    """{"data":[{"id":1,"sender":2,"pieces":[{"type":"text","text":"hi"}]}]}"""
                )

                "/getSessionUsers/9" -> HttpResponse(500, """{"error_message":"boom"}""")
                else -> null
            }
        }
        val repository = repositoryWith(storage(), transport, backgroundScope)
        repository.login(1L, "secret1")

        repository.openChat(9)
        testScheduler.runCurrent()

        val snapshot = repository.chat.value!!
        assertEquals(listOf(1L), snapshot.messages.map { it.id })
        assertTrue("no directory means numeric fallbacks", snapshot.participants.isEmpty())
        assertNull("a supplementary failure must not surface as a chat error", snapshot.error)
        assertFalse(snapshot.isLoadingInitial)
    }

    @Test
    fun `an unreadable member payload keeps the history and the login`() = runTest {
        val transport = ScriptedTransport { _, route ->
            when (route) {
                "/lastMessage/9" ->
                    HttpResponse(200, """{"data":{"hasMessage":true,"id":1}}""")

                "/getMessages/9" -> HttpResponse(
                    200,
                    """{"data":[{"id":1,"sender":2,"pieces":[{"type":"text","text":"hi"}]}]}"""
                )

                "/getSessionUsers/9" -> HttpResponse(200, """{"data":{"unexpected":true}}""")
                else -> null
            }
        }
        val repository = repositoryWith(storage(), transport, backgroundScope)
        repository.login(1L, "secret1")

        repository.openChat(9)
        testScheduler.runCurrent()

        assertEquals(1, repository.chat.value!!.messages.size)
        assertTrue(repository.chat.value!!.participants.isEmpty())
        assertNotNull(repository.credentials.value)
    }

    @Test
    fun `a rejected token on the member lookup signs the user out`() = runTest {
        val storage = storage()
        val transport = ScriptedTransport { _, route ->
            when (route) {
                "/lastMessage/9" ->
                    HttpResponse(200, """{"data":{"hasMessage":true,"id":1}}""")

                "/getMessages/9" -> HttpResponse(
                    200,
                    """{"data":[{"id":1,"sender":2,"pieces":[{"type":"text","text":"hi"}]}]}"""
                )

                "/getSessionUsers/9" -> HttpResponse(401, """{"error_message":"expired"}""")
                else -> null
            }
        }
        val repository = repositoryWith(storage, transport, backgroundScope)
        repository.login(1L, "secret1")

        repository.openChat(9)
        testScheduler.runCurrent()

        assertNull("a 401 on the directory is still a rejected token", repository.credentials.value)
        assertTrue(repository.sessionExpired.value)
        assertNull(storage.credentials())
    }

    @Test
    fun `switching chats drops the previous member directory`() = runTest {
        val transport = ScriptedTransport { _, route ->
            when (route) {
                "/lastMessage/9" ->
                    HttpResponse(200, """{"data":{"hasMessage":true,"id":1}}""")

                "/getMessages/9" -> HttpResponse(
                    200,
                    """{"data":[{"id":1,"sender":2,"pieces":[{"type":"text","text":"a"}]}]}"""
                )

                "/getSessionUsers/9" -> HttpResponse(
                    200,
                    """{"data":{"users":[{"userid":2,"username":"bob"}]}}"""
                )

                "/lastMessage/10" ->
                    HttpResponse(200, """{"data":{"hasMessage":true,"id":5}}""")

                "/getMessages/10" -> HttpResponse(
                    200,
                    """{"data":[{"id":5,"sender":3,"pieces":[{"type":"text","text":"b"}]}]}"""
                )

                "/getSessionUsers/10" -> HttpResponse(
                    200,
                    """{"data":{"users":[{"userid":3,"username":"carol"}]}}"""
                )

                else -> null
            }
        }
        val repository = repositoryWith(storage(), transport, backgroundScope)
        repository.login(1L, "secret1")

        repository.openChat(9)
        testScheduler.runCurrent()
        assertEquals(setOf(2L), repository.chat.value!!.participants.keys)

        repository.openChat(10)
        testScheduler.runCurrent()
        assertEquals(setOf(3L), repository.chat.value!!.participants.keys)
    }

    @Test
    fun `a late member answer for the previous chat does not land in the new one`() = runTest {
        var releaseMembers: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        val transport = ScriptedTransport { _, route ->
            when (route) {
                "/lastMessage/9" ->
                    HttpResponse(200, """{"data":{"hasMessage":true,"id":1}}""")

                "/getMessages/9" -> HttpResponse(
                    200,
                    """{"data":[{"id":1,"sender":2,"pieces":[{"type":"text","text":"a"}]}]}"""
                )

                "/getSessionUsers/9" -> {
                    releaseMembers?.await()
                    HttpResponse(200, """{"data":{"users":[{"userid":2,"username":"bob"}]}}""")
                }

                "/lastMessage/10" ->
                    HttpResponse(200, """{"data":{"hasMessage":true,"id":5}}""")

                "/getMessages/10" -> HttpResponse(
                    200,
                    """{"data":[{"id":5,"sender":3,"pieces":[{"type":"text","text":"b"}]}]}"""
                )

                "/getSessionUsers/10" -> HttpResponse(
                    200,
                    """{"data":{"users":[{"userid":3,"username":"carol"}]}}"""
                )

                else -> null
            }
        }
        val repository = repositoryWith(storage(), transport, backgroundScope)
        repository.login(1L, "secret1")

        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        releaseMembers = gate
        val firstOpen = backgroundScope.launch { repository.openChat(9) }
        testScheduler.runCurrent()

        repository.openChat(10)
        testScheduler.runCurrent()
        releaseMembers = null
        gate.complete(Unit)
        firstOpen.join()
        testScheduler.runCurrent()

        assertEquals(10L, repository.chat.value!!.sessionId)
        assertEquals(
            "A's member directory must not overwrite B's",
            setOf(3L),
            repository.chat.value!!.participants.keys
        )
    }
}
