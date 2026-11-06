package dev.oxype.client.ui.home

import dev.oxype.client.data.OxypeRepository
import dev.oxype.client.data.oxype.AuthCredentials
import dev.oxype.client.data.oxype.HttpResponse
import dev.oxype.client.testsupport.FakeSessionStorage
import dev.oxype.client.testsupport.ScriptedTransport
import dev.oxype.client.testsupport.repositoryWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The list ViewModel must not ask the server before a stored token has been restored.
 *
 * Creating it happens before [OxypeRepository.restoreSession] runs, so an unconditional
 * refresh in `init` would race the restore and surface a bogus "请先登录".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun homeTransport() = ScriptedTransport { _, route ->
        when (route) {
            "/getJoinedSessions/1" -> HttpResponse(200, """{"data":{"sessions":[10]}}""")
            "/getSessions" -> HttpResponse(
                200,
                """{"data":{"sessions":[{"sessionId":10,"sessionName":"甲","owner":1,
                    "users":[{"userid":1,"username":"alice"}]}]}}"""
            )

            else -> null
        }
    }

    @Test
    fun `no session request is made before the stored token is restored`() = runTest {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        val transport = homeTransport()
        val repository = repositoryWith(storage, transport, backgroundScope)

        val viewModel = HomeViewModel(repository)

        assertFalse(transport.requests.any { it.url.contains("/getJoinedSessions") })
        assertNull(viewModel.state.value.error)
        assertFalse(viewModel.state.value.isLoading)
    }

    @Test
    fun `restoring the session refreshes the list exactly once`() = runTest {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        val transport = homeTransport()
        val repository = repositoryWith(storage, transport, backgroundScope)
        val viewModel = HomeViewModel(repository)

        assertTrue(repository.restoreSession())

        assertEquals(listOf(10L), viewModel.state.value.sessions.map { it.sessionId })
        assertNull("restore must not surface a fake login error", viewModel.state.value.error)
        assertEquals(1, transport.requests.count { it.url.contains("/getJoinedSessions") })
    }

    @Test
    fun `manual refresh before restore is refused without an error`() = runTest {
        val storage = FakeSessionStorage()
        val transport = homeTransport()
        val repository = repositoryWith(storage, transport, backgroundScope)
        val viewModel = HomeViewModel(repository)

        viewModel.refresh()

        assertNull(viewModel.state.value.error)
        assertEquals(0, transport.requests.count { it.url.contains("/getJoinedSessions") })
    }

    @Test
    fun `list refresh waits until the token check has finished`() = runTest {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        val aliveGate = CompletableDeferred<Unit>()
        val transport = ScriptedTransport { _, route ->
            when (route) {
                "/isAlive/1" -> {
                    aliveGate.await()
                    HttpResponse(200, """{"data":{"is_alive":true}}""")
                }

                "/getJoinedSessions/1" -> HttpResponse(200, """{"data":{"sessions":[10]}}""")
                "/getSessions" -> HttpResponse(
                    200,
                    """{"data":{"sessions":[{"sessionId":10,"sessionName":"甲","owner":1,
                        "users":[{"userid":1,"username":"alice"}]}]}}"""
                )

                else -> null
            }
        }
        val repository = repositoryWith(storage, transport, backgroundScope)
        val viewModel = HomeViewModel(repository)

        val restore = backgroundScope.launch { repository.restoreSession() }
        testScheduler.runCurrent()

        // The token is being verified: no session request may be in flight yet, and no
        // "请先登录" error may have been produced.
        assertEquals(0, transport.requests.count { it.url.contains("/getJoinedSessions") })
        assertNull(viewModel.state.value.error)

        aliveGate.complete(Unit)
        restore.join()
        testScheduler.runCurrent()

        assertEquals(1, transport.requests.count { it.url.contains("/getJoinedSessions") })
        assertEquals(listOf(10L), viewModel.state.value.sessions.map { it.sessionId })
    }

    @Test
    fun `a 401 from the list invalidates the login instead of showing an error`() = runTest {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        val transport = ScriptedTransport { _, route ->
            when (route) {
                "/isAlive/1" -> HttpResponse(200, """{"data":{"is_alive":true}}""")
                "/getJoinedSessions/1" ->
                    HttpResponse(401, """{"error_message":"Unauthorized"}""")

                else -> null
            }
        }
        val repository = repositoryWith(storage, transport, backgroundScope)
        val viewModel = HomeViewModel(repository)
        assertTrue(repository.restoreSession())

        testScheduler.runCurrent()

        // The credential signal moves the app back to auth; the chat/state must be gone
        // and no English snackbar is left behind.
        assertNull("a revoked token must not be shown as a list error", viewModel.state.value.error)
        assertNull(repository.credentials.value)
        assertNull(storage.credentials())
        assertTrue(repository.sessionExpired.value)
        assertTrue(viewModel.state.value.sessions.isEmpty())
    }

    @Test
    fun `a 403 from the list is shown as an error and keeps the login`() = runTest {
        // Oxype answers 403 when the caller is authenticated but lacks permission. It
        // says nothing about the token, so the app must not sign the user out.
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        val transport = ScriptedTransport { _, route ->
            when (route) {
                "/isAlive/1" -> HttpResponse(200, """{"data":{"is_alive":true}}""")
                "/getJoinedSessions/1" ->
                    HttpResponse(403, """{"error_message":"Forbidden"}""")

                else -> null
            }
        }
        val repository = repositoryWith(storage, transport, backgroundScope)
        val viewModel = HomeViewModel(repository)
        assertTrue(repository.restoreSession())

        testScheduler.runCurrent()

        assertNotNull("a 403 must be visible as a list error", viewModel.state.value.error)
        assertNotNull("a 403 must not clear the token", repository.credentials.value)
        assertEquals(AuthCredentials(1L, "tok"), storage.credentials())
        assertFalse(repository.sessionExpired.value)
    }

    @Test
    fun `a 403 on one session does not sign out or fail the whole list`() = runTest {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        val transport = ScriptedTransport { _, route ->
            when (route) {
                "/isAlive/1" -> HttpResponse(200, """{"data":{"is_alive":true}}""")
                "/getJoinedSessions/1" -> HttpResponse(200, """{"data":{"sessions":[10,11]}}""")
                "/getSessions" -> HttpResponse(403, """{"error_message":"Forbidden"}""")
                "/getSessionInfo/10" -> HttpResponse(
                    200,
                    """{"data":{"sessionId":10,"sessionName":"甲","owner":1,
                        "users":[{"userid":1,"username":"alice"}]}}"""
                )

                "/getSessionInfo/11" -> HttpResponse(403, """{"error_message":"Forbidden"}""")
                "/getSession/11" -> HttpResponse(403, """{"error_message":"Forbidden"}""")
                else -> null
            }
        }
        val repository = repositoryWith(storage, transport, backgroundScope)
        val viewModel = HomeViewModel(repository)
        assertTrue(repository.restoreSession())

        testScheduler.runCurrent()

        assertEquals(listOf(10L), viewModel.state.value.sessions.map { it.sessionId })
        assertNotNull("a per-session 403 must not clear the token", repository.credentials.value)
        assertFalse(repository.sessionExpired.value)
    }

    @Test
    fun `a server error keeps the login and reports it`() = runTest {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        val transport = ScriptedTransport { _, route ->
            when (route) {
                "/isAlive/1" -> HttpResponse(200, """{"data":{"is_alive":true}}""")
                "/getJoinedSessions/1" -> HttpResponse(500, """{"error_message":"boom"}""")
                else -> null
            }
        }
        val repository = repositoryWith(storage, transport, backgroundScope)
        val viewModel = HomeViewModel(repository)
        assertTrue(repository.restoreSession())

        testScheduler.runCurrent()

        assertNotNull("a server failure must be surfaced", viewModel.state.value.error)
        assertNotNull("5xx says nothing about the token", repository.credentials.value)
        assertFalse(repository.sessionExpired.value)
    }

    @Test
    fun `a network failure keeps the login and reports it`() = runTest {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        val transport = ScriptedTransport { _, route ->
            when (route) {
                "/isAlive/1" -> HttpResponse(200, """{"data":{"is_alive":true}}""")
                "/getJoinedSessions/1" ->
                    throw dev.oxype.client.data.oxype.OxypeException(
                        dev.oxype.client.data.oxype.OxypeErrorKind.NETWORK,
                        "无法连接服务器"
                    )

                else -> null
            }
        }
        val repository = repositoryWith(storage, transport, backgroundScope)
        val viewModel = HomeViewModel(repository)
        assertTrue(repository.restoreSession())

        testScheduler.runCurrent()

        assertNotNull(viewModel.state.value.error)
        assertNotNull(repository.credentials.value)
    }

    @Test
    fun `switching accounts cancels the previous account's list load`() = runTest {
        // A is loading its list when B signs in. A's answer must not be shown, and the
        // "already loading" guard must not keep B from starting its own refresh.
        val aStarted = CompletableDeferred<Unit>()
        val aGate = CompletableDeferred<Unit>()
        val transport = ScriptedTransport { request, route ->
            when (route) {
                "/isAlive/1" -> HttpResponse(200, """{"data":{"is_alive":true}}""")
                "/login/2" -> HttpResponse(200, """{"data":{"token":"b-tok"}}""")
                "/getJoinedSessions/1" -> {
                    aStarted.complete(Unit)
                    aGate.await()
                    HttpResponse(200, """{"data":{"sessions":[10]}}""")
                }

                "/getJoinedSessions/2" -> HttpResponse(200, """{"data":{"sessions":[20]}}""")
                "/getSessions" -> {
                    val ids = org.json.JSONObject(request.body!!).getJSONArray("sessions")
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
        val repository = repositoryWith(storage(), transport, backgroundScope)
        assertTrue(repository.restoreSession())
        val viewModel = HomeViewModel(repository)
        testScheduler.runCurrent()

        aStarted.await()
        assertTrue("A's refresh must be in flight", viewModel.state.value.isLoading)

        repository.login(2L, "secret2")
        aGate.complete(Unit)
        testScheduler.runCurrent()

        assertEquals(
            "B must see its own list, not A's",
            listOf(20L),
            viewModel.state.value.sessions.map { it.sessionId }
        )
        assertFalse("B's refresh must not be blocked by A's", viewModel.state.value.isLoading)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `a 401 for the previous account does not sign the new one out`() = runTest {
        val aStarted = CompletableDeferred<Unit>()
        val aGate = CompletableDeferred<Unit>()
        val transport = ScriptedTransport { _, route ->
            when (route) {
                "/isAlive/1" -> HttpResponse(200, """{"data":{"is_alive":true}}""")
                "/login/2" -> HttpResponse(200, """{"data":{"token":"b-tok"}}""")
                "/getJoinedSessions/1" -> {
                    aStarted.complete(Unit)
                    aGate.await()
                    HttpResponse(401, """{"error_message":"Unauthorized"}""")
                }

                "/getJoinedSessions/2" -> HttpResponse(200, """{"data":{"sessions":[20]}}""")
                "/getSessions" -> HttpResponse(
                    200,
                    """{"data":{"sessions":[{"sessionId":20,"sessionName":"乙","owner":2,
                        "users":[{"userid":2,"username":"bob"}]}]}}"""
                )

                else -> null
            }
        }
        val repository = repositoryWith(storage(), transport, backgroundScope)
        assertTrue(repository.restoreSession())
        val viewModel = HomeViewModel(repository)
        testScheduler.runCurrent()
        aStarted.await()

        repository.login(2L, "secret2")
        aGate.complete(Unit)
        testScheduler.runCurrent()

        assertEquals(
            "A's rejected token must not clear B's login",
            AuthCredentials(2L, "b-tok"),
            repository.credentials.value
        )
        assertFalse(repository.sessionExpired.value)
        assertEquals(listOf(20L), viewModel.state.value.sessions.map { it.sessionId })
    }

    @Test
    fun `a create failure for the previous account does not show on the new list`() = runTest {
        // The create action is not the list load, so it is not cancelled when the login
        // changes. Its late failure must therefore be dropped by the owner check instead
        // of painting "创建失败" over B's freshly loaded list.
        val createStarted = CompletableDeferred<Unit>()
        val createGate = CompletableDeferred<Unit>()
        val bListStarted = CompletableDeferred<Unit>()
        val bListGate = CompletableDeferred<Unit>()
        val transport = ScriptedTransport { _, route ->
            when (route) {
                "/isAlive/1" -> HttpResponse(200, """{"data":{"is_alive":true}}""")
                "/login/2" -> HttpResponse(200, """{"data":{"token":"b-tok"}}""")
                "/createSession" -> {
                    createStarted.complete(Unit)
                    createGate.await()
                    HttpResponse(500, """{"error_message":"create failed"}""")
                }

                "/getJoinedSessions/2" -> {
                    bListStarted.complete(Unit)
                    // Hold B's list so A's late failure lands while B's screen is still
                    // the one on display, instead of being overwritten by B's refresh.
                    bListGate.await()
                    HttpResponse(200, """{"data":{"sessions":[20]}}""")
                }

                "/getSessions" -> HttpResponse(
                    200,
                    """{"data":{"sessions":[{"sessionId":20,"sessionName":"乙","owner":2,
                        "users":[{"userid":2,"username":"bob"}]}]}}"""
                )

                else -> null
            }
        }
        val repository = repositoryWith(storage(), transport, backgroundScope)
        assertTrue(repository.restoreSession())
        val viewModel = HomeViewModel(repository)
        testScheduler.runCurrent()

        viewModel.createSession("甲组", "")
        testScheduler.runCurrent()
        createStarted.await()

        repository.login(2L, "secret2")
        testScheduler.runCurrent()
        bListStarted.await()

        // A's create answers now, while B is signed in and its list is in flight.
        createGate.complete(Unit)
        testScheduler.runCurrent()

        assertNull("A's failure must not appear on B's screen", viewModel.state.value.error)
        assertFalse("B's own action was never started", viewModel.state.value.isBusy)

        bListGate.complete(Unit)
        testScheduler.runCurrent()

        assertEquals(
            "B must keep its own list",
            listOf(20L),
            viewModel.state.value.sessions.map { it.sessionId }
        )
        assertNull("A's failure must not reappear after B's refresh", viewModel.state.value.error)
    }

    @Test
    fun `a join that succeeds for the previous account stays off the new list`() = runTest {
        // The join announcement ("已加入…") belongs to the login that asked for it. If it
        // were published after a switch it would tell B they joined a conversation they
        // never touched.
        val joinStarted = CompletableDeferred<Unit>()
        val joinGate = CompletableDeferred<Unit>()
        val transport = ScriptedTransport { _, route ->
            when (route) {
                "/isAlive/1" -> HttpResponse(200, """{"data":{"is_alive":true}}""")
                "/login/2" -> HttpResponse(200, """{"data":{"token":"b-tok"}}""")
                "/joinSessionByUuid" -> {
                    joinStarted.complete(Unit)
                    joinGate.await()
                    HttpResponse(
                        200,
                        """{"data":{"sessionId":99,"sessionName":"甲的会话","owner":1,
                            "users":[{"userid":1,"username":"alice"}]}}"""
                    )
                }

                "/getJoinedSessions/2" -> HttpResponse(200, """{"data":{"sessions":[20]}}""")
                "/getSessions" -> HttpResponse(
                    200,
                    """{"data":{"sessions":[{"sessionId":20,"sessionName":"乙","owner":2,
                        "users":[{"userid":2,"username":"bob"}]}]}}"""
                )

                else -> null
            }
        }
        val repository = repositoryWith(storage(), transport, backgroundScope)
        assertTrue(repository.restoreSession())
        val viewModel = HomeViewModel(repository)
        testScheduler.runCurrent()

        viewModel.joinSession("uuid-1")
        testScheduler.runCurrent()
        joinStarted.await()

        repository.login(2L, "secret2")
        testScheduler.runCurrent()
        joinGate.complete(Unit)
        testScheduler.runCurrent()

        assertNull("B must not see A's join announcement", viewModel.state.value.info)
        assertEquals(listOf(20L), viewModel.state.value.sessions.map { it.sessionId })
    }

    private fun storage() = FakeSessionStorage().apply {
        saveAuth(AuthCredentials(1L, "tok"), "alice")
    }
}
