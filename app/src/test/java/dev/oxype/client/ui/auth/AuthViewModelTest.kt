package dev.oxype.client.ui.auth

import dev.oxype.client.data.oxype.HttpResponse
import dev.oxype.client.testsupport.FakeSessionStorage
import dev.oxype.client.testsupport.ScriptedTransport
import dev.oxype.client.testsupport.repositoryWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Auth screen state.
 *
 * The server address has to be reachable *before* signing in: when the default
 * `10.0.2.2:12345` is wrong, a user who can only change it after login is stuck.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun transport() = ScriptedTransport { _, route ->
        when (route) {
            "/login/7" -> HttpResponse(200, """{"data":{"token":"tok"}}""")
            else -> null
        }
    }

    @Test
    fun `state starts with the currently configured server url`() = runTest {
        val storage = FakeSessionStorage().apply { baseUrl = "http://host.example:9000" }
        val repository = repositoryWith(storage, transport(), backgroundScope)

        val viewModel = AuthViewModel(repository)

        assertEquals("http://host.example:9000", viewModel.state.value.serverUrl)
    }

    @Test
    fun `saving the server url normalises and persists it`() = runTest {
        val storage = FakeSessionStorage()
        val repository = repositoryWith(storage, transport(), backgroundScope)
        val viewModel = AuthViewModel(repository)

        viewModel.updateServerUrl("host.example:8080/")
        viewModel.saveServerUrl()

        assertEquals("http://host.example:8080", viewModel.state.value.serverUrl)
        assertEquals("http://host.example:8080", storage.baseUrl)
        assertTrue(viewModel.state.value.serverNotice!!.contains("已保存"))
    }

    @Test
    fun `saving a blank server url is refused`() = runTest {
        val storage = FakeSessionStorage()
        val repository = repositoryWith(storage, transport(), backgroundScope)
        val viewModel = AuthViewModel(repository)
        val before = storage.baseUrl

        viewModel.updateServerUrl("   ")
        viewModel.saveServerUrl()

        assertEquals(before, storage.baseUrl)
        assertTrue(viewModel.state.value.serverNotice!!.contains("不能为空"))
    }

    @Test
    fun `signing in clears the busy flag and the credentials are stored`() = runTest {
        val storage = FakeSessionStorage()
        val repository = repositoryWith(storage, transport(), backgroundScope)
        val viewModel = AuthViewModel(repository)

        viewModel.setUserId("7")
        viewModel.setPassword("secret1")
        viewModel.submit()

        assertEquals(7L, repository.credentials.value?.userId)
        assertEquals(false, viewModel.state.value.isBusy)
    }
}
