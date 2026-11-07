package dev.oxype.client.ui.groupinfo

import dev.oxype.client.data.oxype.AuthCredentials
import dev.oxype.client.data.oxype.HttpResponse
import dev.oxype.client.testsupport.FakeSessionStorage
import dev.oxype.client.testsupport.ScriptedTransport
import dev.oxype.client.testsupport.repositoryWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * Loading state for the group info page.
 *
 * The page can be left while a load is in flight (back, or a tap on another session), so
 * a late answer must not paint over whatever the user is looking at now.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupInfoViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun signedIn() = FakeSessionStorage().apply {
        saveAuth(AuthCredentials(1L, "tok"), "alice")
    }

    private fun detail(sessionId: Long = 42L, name: String = "周末徒步小队") = """{"data":{
        "sessionId":$sessionId,"name":"$name","description":"每周六出发","owner":2,"admins":[2],
        "users":[{"userid":2,"username":"林间小道"}]
    }}"""

    private fun transport(
        sessionInfo: suspend (Long) -> HttpResponse = { HttpResponse(200, detail(it)) },
        directory: HttpResponse = HttpResponse(200, """{"users":[{"userid":2,"username":"林间小道"}]}""")
    ) = ScriptedTransport { _, route ->
        when {
            route.startsWith("/getSessionInfo/") ->
                sessionInfo(route.substringAfterLast('/').toLong())

            route.startsWith("/getSession/") -> HttpResponse(404, """{"error_message":"no"}""")
            route.startsWith("/getSessionUsers/") -> directory
            else -> null
        }
    }

    @Test
    fun `a successful load fills the info and clears the spinner`() = runTest {
        val repository = repositoryWith(signedIn(), transport(), backgroundScope)
        repository.login(1L, "secret1")
        val viewModel = GroupInfoViewModel(repository)

        viewModel.open(42L)
        testScheduler.runCurrent()

        val state = viewModel.state.value
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertEquals("周末徒步小队", state.info?.name)
        assertEquals(42L, state.sessionId)
        assertEquals("每周六出发", state.info?.description)
    }

    @Test
    fun `a failed load reports a readable error and stops loading`() = runTest {
        val repository = repositoryWith(
            signedIn(),
            transport(sessionInfo = { HttpResponse(403, """{"error_message":"forbidden"}""") }),
            backgroundScope
        )
        repository.login(1L, "secret1")
        val viewModel = GroupInfoViewModel(repository)

        viewModel.open(42L)
        testScheduler.runCurrent()

        val state = viewModel.state.value
        assertFalse(state.isLoading)
        assertNull(state.info)
        assertNotNull("a denied page must say why", state.error)
        assertTrue(state.error!!.contains("forbidden"))
        // A page about one conversation must not sign the user out.
        assertNotNull(repository.credentials.value)
    }

    @Test
    fun `refresh retries the same session and can succeed`() = runTest {
        var deny = true
        val repository = repositoryWith(
            signedIn(),
            transport(sessionInfo = {
                if (deny) HttpResponse(403, """{"error_message":"forbidden"}""") else HttpResponse(200, detail())
            }),
            backgroundScope
        )
        repository.login(1L, "secret1")
        val viewModel = GroupInfoViewModel(repository)
        viewModel.open(42L)
        testScheduler.runCurrent()
        assertNotNull(viewModel.state.value.error)

        deny = false
        viewModel.refresh()
        testScheduler.runCurrent()

        assertNull(viewModel.state.value.error)
        assertEquals("周末徒步小队", viewModel.state.value.info?.name)
    }

    @Test
    fun `an answer for a page the user already left does not overwrite`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val firstGate = CompletableDeferred<Unit>()
        val repository = repositoryWith(
            signedIn(),
            transport(sessionInfo = { sessionId ->
                if (sessionId == 42L) {
                    firstStarted.complete(Unit)
                    firstGate.await()
                    HttpResponse(200, detail(42L, "旧群"))
                } else {
                    HttpResponse(200, detail(43L, "新群"))
                }
            }),
            backgroundScope
        )
        repository.login(1L, "secret1")
        val viewModel = GroupInfoViewModel(repository)

        viewModel.open(42L)
        firstStarted.await()
        viewModel.open(43L)
        testScheduler.runCurrent()
        assertEquals("新群", viewModel.state.value.info?.name)

        firstGate.complete(Unit)
        testScheduler.runCurrent()

        assertEquals("the abandoned page's answer must be dropped", 43L, viewModel.state.value.sessionId)
        assertEquals("新群", viewModel.state.value.info?.name)
    }

    @Test
    fun `dismissing the error clears it`() = runTest {        val repository = repositoryWith(
            signedIn(),
            transport(sessionInfo = { HttpResponse(403, """{"error_message":"forbidden"}""") }),
            backgroundScope
        )
        repository.login(1L, "secret1")
        val viewModel = GroupInfoViewModel(repository)
        viewModel.open(42L)
        testScheduler.runCurrent()
        assertNotNull(viewModel.state.value.error)

        viewModel.consumeError()

        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `re-opening the same session keeps what is already loaded`() = runTest {
        var calls = 0
        val repository = repositoryWith(
            signedIn(),
            ScriptedTransport { _, route ->
                when {
                    route.startsWith("/getSessionInfo/") -> {
                        calls++
                        HttpResponse(200, detail())
                    }

                    route.startsWith("/getSession/") -> HttpResponse(404, """{"error_message":"no"}""")
                    route.startsWith("/getSessionUsers/") ->
                        HttpResponse(200, """{"users":[{"userid":2,"username":"林间小道"}]}""")

                    else -> null
                }
            },
            backgroundScope
        )
        repository.login(1L, "secret1")
        val viewModel = GroupInfoViewModel(repository)
        viewModel.open(42L)
        testScheduler.runCurrent()
        assertEquals(1, calls)

        // Coming back to the page (a rotation, or a round trip through the chat) must not
        // flash the spinner over content the user has already seen.
        viewModel.open(42L)
        testScheduler.runCurrent()

        assertEquals(1, calls)
        assertFalse(viewModel.state.value.isLoading)
        assertEquals("周末徒步小队", viewModel.state.value.info?.name)
    }

    @Test
    fun `a cached page is not reused after signing out and back in`() = runTest {
        var calls = 0
        val repository = repositoryWith(
            signedIn(),
            ScriptedTransport { _, route ->
                when {
                    route.startsWith("/getSessionInfo/") -> {
                        calls++
                        HttpResponse(200, detail())
                    }

                    route.startsWith("/getSession/") -> HttpResponse(404, """{"error_message":"no"}""")
                    route.startsWith("/getSessionUsers/") ->
                        HttpResponse(200, """{"users":[{"userid":2,"username":"林间小道"}]}""")

                    else -> null
                }
            },
            backgroundScope
        )
        repository.login(1L, "secret1")
        val viewModel = GroupInfoViewModel(repository)
        viewModel.open(42L)
        testScheduler.runCurrent()
        assertEquals(1, calls)

        // The same account signs in again with a new token: `（我）`, the role badges and
        // the membership list all belong to that *login*, not to the user id alone.
        repository.logout()
        repository.login(1L, "secret1")
        viewModel.open(42L)
        testScheduler.runCurrent()

        assertEquals("a new login must not inherit the previous page", 2, calls)
        assertFalse(viewModel.state.value.isLoading)
        assertEquals("周末徒步小队", viewModel.state.value.info?.name)
    }

    @Test
    fun `a cached page is not reused after the server address changes`() = runTest {
        var calls = 0
        val repository = repositoryWith(
            signedIn(),
            ScriptedTransport { _, route ->
                when {
                    route.startsWith("/getSessionInfo/") -> {
                        calls++
                        HttpResponse(200, detail())
                    }

                    route.startsWith("/getSession/") -> HttpResponse(404, """{"error_message":"no"}""")
                    route.startsWith("/getSessionUsers/") ->
                        HttpResponse(200, """{"users":[{"userid":2,"username":"林间小道"}]}""")

                    else -> null
                }
            },
            backgroundScope
        )
        repository.login(1L, "secret1")
        val viewModel = GroupInfoViewModel(repository)
        viewModel.open(42L)
        testScheduler.runCurrent()
        assertEquals(1, calls)

        // Session 42 on another Oxype instance is a different group with different
        // members, even though the account and the ids look the same.
        repository.updateBaseUrl("http://other.example.test:12345")
        viewModel.open(42L)
        testScheduler.runCurrent()

        assertEquals(2, calls)
    }

    @Test
    fun `entering the page again reloads instead of showing a stale roster`() = runTest {
        var name = "旧名字"
        val repository = repositoryWith(
            signedIn(),
            ScriptedTransport { _, route ->
                when {
                    route.startsWith("/getSessionInfo/") -> HttpResponse(200, detail(name = name))
                    route.startsWith("/getSession/") -> HttpResponse(404, """{"error_message":"no"}""")
                    route.startsWith("/getSessionUsers/") ->
                        HttpResponse(200, """{"users":[{"userid":2,"username":"林间小道"}]}""")

                    else -> null
                }
            },
            backgroundScope
        )
        repository.login(1L, "secret1")
        val viewModel = GroupInfoViewModel(repository)
        viewModel.open(42L)
        testScheduler.runCurrent()
        assertEquals("旧名字", viewModel.state.value.info?.name)

        // A fresh visit (back from the chat, or the page opened again) asks the server:
        // names, roles and the roster legitimately change while the app is open, and a
        // page that only ever shows the first answer can never recover.
        name = "新名字"
        viewModel.open(42L, reload = true)
        testScheduler.runCurrent()

        assertEquals("新名字", viewModel.state.value.info?.name)
    }
}
