package dev.oxype.client.ui.settings

import dev.oxype.client.data.oxype.AuthCredentials
import dev.oxype.client.data.oxype.HttpResponse
import dev.oxype.client.data.session.ThemeMode
import dev.oxype.client.testsupport.FakeSessionStorage
import dev.oxype.client.testsupport.ScriptedTransport
import dev.oxype.client.testsupport.repositoryWith
import dev.oxype.client.ui.ThemeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The settings screen outlives a single server address or account: it is created with
 * the rest of the app, while the address is still being configured on the auth screen.
 * Its state therefore has to follow the repository instead of keeping the values that
 * happened to be current when it was constructed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeThemeSettings : ThemeSettings {
        private val flow = MutableStateFlow(ThemeMode.SYSTEM)
        override val themeMode: StateFlow<ThemeMode> = flow
        override fun setThemeMode(mode: ThemeMode) {
            flow.value = mode
        }
    }

    @Test
    fun `an address configured before sign-in is not overwritten by a stale snapshot`() = runTest {
        val storage = FakeSessionStorage()
        val repository = repositoryWith(storage, ScriptedTransport { _, _ -> null }, backgroundScope)
        // Created while the app still shows the auth screen, before the user points the
        // app at their own server.
        val viewModel = SettingsViewModel(repository, FakeThemeSettings())
        assertEquals("http://example.test:12345", viewModel.state.value.serverUrl)

        repository.updateBaseUrl("192.168.1.20:12345")
        repository.login(1L, "secret1")

        assertEquals(
            "the screen must follow the address the user actually configured",
            "http://192.168.1.20:12345",
            viewModel.state.value.serverUrl
        )

        viewModel.saveServerUrl()

        assertEquals(
            "saving must not put the old default back",
            "http://192.168.1.20:12345",
            repository.baseUrl()
        )
        assertEquals("http://192.168.1.20:12345", viewModel.state.value.serverUrl)
    }

    @Test
    fun `entering the screen picks up an address saved while signed out`() = runTest {
        val storage = FakeSessionStorage()
        val repository = repositoryWith(storage, ScriptedTransport { _, _ -> null }, backgroundScope)
        val viewModel = SettingsViewModel(repository, FakeThemeSettings())

        // The auth screen saved a server before this screen was ever shown. No credential
        // changed, so only the entry hook can make the field current.
        repository.updateBaseUrl("https://chat.example.test")
        viewModel.syncFromRepository()

        assertEquals("https://chat.example.test", viewModel.state.value.serverUrl)

        viewModel.saveServerUrl()
        assertEquals("https://chat.example.test", repository.baseUrl())
    }

    @Test
    fun `signing in publishes the account fields`() = runTest {        val storage = FakeSessionStorage()
        val transport = ScriptedTransport { _, route ->
            if (route == "/register") {
                HttpResponse(200, """{"data":{"userid":9,"token":"newtok"}}""")
            } else {
                null
            }
        }
        val repository = repositoryWith(storage, transport, backgroundScope)
        val viewModel = SettingsViewModel(repository, FakeThemeSettings())
        assertNull(viewModel.state.value.userId)
        assertNull(viewModel.state.value.username)

        repository.register("alice", "secret1")

        assertEquals(9L, viewModel.state.value.userId)
        assertEquals("alice", viewModel.state.value.username)
    }

    @Test
    fun `signing out clears the account fields`() = runTest {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(5L, "tok"), "bob")
        }
        val repository = repositoryWith(storage, ScriptedTransport { _, _ -> null }, backgroundScope)
        val viewModel = SettingsViewModel(repository, FakeThemeSettings())

        repository.login(5L, "secret1")
        assertEquals(5L, viewModel.state.value.userId)

        repository.logout()

        assertNull(viewModel.state.value.userId)
        assertNull(viewModel.state.value.username)
    }
}
