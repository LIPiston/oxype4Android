package dev.oxype.client.data

import dev.oxype.client.data.oxype.AuthCredentials
import dev.oxype.client.data.oxype.HttpResponse
import dev.oxype.client.testsupport.FakeSessionStorage
import dev.oxype.client.testsupport.ScriptedTransport
import dev.oxype.client.testsupport.repositoryWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Which login a request belongs to.
 *
 * A request outlives the session that made it: the answer can arrive after the user
 * signed out, after a different account signed in, or after the *same* account signed in
 * again with a new token. Every one of those must leave the current login alone — a
 * stale 401 is about a credential that is no longer in use, and stale data describes
 * conversations the current account may not even be in.
 */
class CredentialOwnershipTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun storage(credentials: AuthCredentials = AuthCredentials(1L, "a-tok")) =
        FakeSessionStorage().apply { saveAuth(credentials, "alice") }

    @Test
    fun `an old login cannot invalidate the account that replaced it`() = runBlocking {
        val transport = ScriptedTransport { _, route ->
            when (route) {
                "/isAlive/1" -> HttpResponse(200, """{"data":{"is_alive":true}}""")
                "/login/2" -> HttpResponse(200, """{"data":{"token":"b-tok"}}""")
                else -> null
            }
        }
        val repository = repositoryWith(storage(), transport, scope)
        repository.restoreSession()
        val staleOwner = repository.authOwner()
        assertNotNull(staleOwner)

        // A different account signs in while a request from the previous one is out.
        repository.login(2L, "secret2")

        // The old callback's 401 arrives now.
        repository.invalidateCredentials(staleOwner)

        assertEquals(
            "a rejected token from the previous login must not sign the new one out",
            AuthCredentials(2L, "b-tok"),
            repository.credentials.value
        )
        assertFalse("the new login is not expired", repository.sessionExpired.value)
    }

    @Test
    fun `the same account signing in again is a different owner`() = runBlocking {
        // A new token for the same user id is still a different credential: a 401 about
        // the old token says nothing about the new one.
        var token = "tok-1"
        val transport = ScriptedTransport { _, route ->
            when {
                route.startsWith("/isAlive/") ->
                    HttpResponse(200, """{"data":{"is_alive":true}}""")

                route.startsWith("/login/") -> {
                    token = "tok-2"
                    HttpResponse(200, """{"data":{"token":"$token"}}""")
                }

                else -> null
            }
        }
        val repository = repositoryWith(storage(), transport, scope)
        repository.restoreSession()
        val firstOwner = repository.authOwner()!!

        repository.login(1L, "secret1")
        assertEquals("tok-2", repository.credentials.value?.token)

        repository.invalidateCredentials(firstOwner)

        assertEquals(
            "a 401 about the previous token must not clear the freshly issued one",
            AuthCredentials(1L, "tok-2"),
            repository.credentials.value
        )
        assertFalse(repository.sessionExpired.value)
    }

    @Test
    fun `a manual sign-out still clears the current login`() = runBlocking {
        val stored = storage()
        val transport = ScriptedTransport { _, route ->
            if (route == "/isAlive/1") {
                HttpResponse(200, """{"data":{"is_alive":true}}""")
            } else {
                null
            }
        }
        val repository = repositoryWith(stored, transport, scope)
        repository.restoreSession()

        // No owner passed: this is a deliberate local invalidation.
        repository.invalidateCredentials()

        assertNull(repository.credentials.value)
        assertNull("the stored token must be gone", stored.credentials())
    }

    @Test
    fun `a session list answered for the previous account is not published`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val transport = ScriptedTransport { request, route ->
            when {
                route == "/isAlive/1" -> HttpResponse(200, """{"data":{"is_alive":true}}""")
                route == "/login/2" -> HttpResponse(200, """{"data":{"token":"b-tok"}}""")
                route == "/getJoinedSessions/1" -> {
                    started.complete(Unit)
                    gate.await()
                    HttpResponse(200, """{"data":{"sessions":[10]}}""")
                }

                route == "/getJoinedSessions/2" ->
                    HttpResponse(200, """{"data":{"sessions":[20]}}""")

                route == "/getSessions" -> {
                    val ids = JSONObject(request.body!!).getJSONArray("sessions")
                    val items = (0 until ids.length()).joinToString(",") { index ->
                        val id = ids.getLong(index)
                        """{"sessionId":$id,"sessionName":"会话$id","owner":1,
                            "users":[{"userid":1,"username":"alice"}]}"""
                    }
                    HttpResponse(200, """{"data":{"sessions":[$items]}}""")
                }

                else -> null
            }
        }
        val repository = repositoryWith(storage(), transport, scope)
        repository.restoreSession()

        val staleRefresh = launch(Dispatchers.Default) { repository.refreshSessions() }
        started.await()

        // The account changes while A's list request is still in flight.
        repository.login(2L, "secret2")
        repository.refreshSessions()
        assertEquals(listOf(20L), repository.sessions.value.map { it.sessionId })

        gate.complete(Unit)
        staleRefresh.join()

        assertEquals(
            "A's late list must not replace B's",
            listOf(20L),
            repository.sessions.value.map { it.sessionId }
        )
        assertEquals("会话20", repository.sessions.value.single().name)
    }

    @Test
    fun `a poll answered for the previous account does not touch the new transcript`() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            var probeCalls = 0
            val transport = ScriptedTransport { _, route ->
                when {
                    route == "/isAlive/1" -> HttpResponse(200, """{"data":{"is_alive":true}}""")
                    route == "/login/2" -> HttpResponse(200, """{"data":{"token":"b-tok"}}""")
                    route == "/lastMessage/9" -> {
                        // The first probe is the initial open; only the poll's is held.
                        probeCalls++
                        if (probeCalls > 1) {
                            started.complete(Unit)
                            gate.await()
                        }
                        HttpResponse(200, """{"data":{"hasMessage":true,"id":5}}""")
                    }

                    route == "/getMessages/9" -> HttpResponse(
                        200,
                        """{"data":[
                            {"id":1,"sender":1,"pieces":[{"type":"text","text":"a"}]},
                            {"id":2,"sender":1,"pieces":[{"type":"text","text":"b"}]}
                        ]}"""
                    )

                    else -> null
                }
            }
            val repository = repositoryWith(storage(), transport, scope)
            repository.restoreSession()
            repository.openChat(9)
            assertEquals(listOf(1L, 2L), repository.chat.value!!.messages.map { it.id })

            val stalePoll = launch(Dispatchers.Default) { repository.pollOnce() }
            started.await()
            repository.login(2L, "secret2")
            gate.complete(Unit)
            stalePoll.join()

            assertEquals(
                "an answer from the previous login must not merge into the transcript",
                listOf(1L, 2L),
                repository.chat.value!!.messages.map { it.id }
            )
        }

    @Test
    fun `a stale 401 while paging does not sign out the current login`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val requests = CopyOnWriteArrayList<String>()
        val transport = ScriptedTransport { request, route ->
            requests.add("$route ${request.body}")
            when {
                route == "/isAlive/1" -> HttpResponse(200, """{"data":{"is_alive":true}}""")
                route == "/login/2" -> HttpResponse(200, """{"data":{"token":"b-tok"}}""")
                route == "/lastMessage/9" ->
                    HttpResponse(200, """{"data":{"hasMessage":true,"id":60}}""")

                route == "/getMessages/9" -> {
                    val body = JSONObject(request.body!!)
                    val start = body.getLong("start")
                    val end = body.getLong("end").coerceAtMost(60L)
                    // Only the older page (below the first window) is held and rejected;
                    // the initial load has to complete before paging can be asked for.
                    if (start < 31L) {
                        started.complete(Unit)
                        gate.await()
                        HttpResponse(401, """{"error_message":"expired"}""")
                    } else {
                        val items = (start.coerceAtLeast(1L)..end).joinToString(",") {
                            """{"id":$it,"sender":1,"pieces":[{"type":"text","text":"m$it"}]}"""
                        }
                        HttpResponse(200, """{"data":[$items]}""")
                    }
                }

                else -> null
            }
        }
        val repository = repositoryWith(storage(), transport, scope)
        repository.restoreSession()
        repository.openChat(9)

        val paging = launch(Dispatchers.Default) { runCatching { repository.loadOlder() } }
        started.await()
        repository.login(2L, "secret2")
        gate.complete(Unit)
        paging.join()

        assertEquals(
            "an old page's 401 must not clear the account that replaced it",
            AuthCredentials(2L, "b-tok"),
            repository.credentials.value
        )
        assertFalse(repository.sessionExpired.value)
    }
}
