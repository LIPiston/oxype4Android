package dev.oxype.client.ui.profile

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The profile page's own state.
 *
 * The page can be left while a load, an upload or a removal is in flight, so an answer
 * that arrives after the user moved on must not write into whatever is on screen now.
 * That is what [ProfileUiState] plus the page generation guard, and these tests pin it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {

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

    private val validPng: ByteArray = ByteArray(40).also { bytes ->
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            .copyInto(bytes, 0)
        bytes[12] = 0x49
        bytes[13] = 0x48
        bytes[14] = 0x44
        bytes[15] = 0x52
        // 8 x 8, big-endian.
        bytes[19] = 8
        bytes[23] = 8
    }

    private fun transport(
        user: suspend () -> HttpResponse = {
            HttpResponse(
                200,
                """{"data":{"id":1,"username":"alice","avatarUrl":"/avatar/u1-77802f6e.png"}}"""
            )
        },
        avatar: suspend () -> HttpResponse = {
            HttpResponse(
                200,
                """{"data":{"hasAvatar":true,"maxBytes":3145728,"maxEdge":4096,
                    "formats":["image/png"]}}"""
            )
        },
        upload: suspend () -> HttpResponse = {
            HttpResponse(200, """{"data":{"avatarUrl":"/avatar/u1-deadbeef.png","hasAvatar":true}}""")
        },
        remove: suspend () -> HttpResponse = {
            HttpResponse(200, """{"data":{"avatarUrl":"","hasAvatar":false}}""")
        }
    ) = ScriptedTransport { _, route ->
        when {
            route.startsWith("/getUser/") -> user()
            route.startsWith("/getAvatar/") -> avatar()
            route.startsWith("/uploadAvatar/") -> upload()
            route.startsWith("/removeAvatar/") -> remove()
            else -> null
        }
    }

    @Test
    fun `opening the page loads the profile`() = runTest {
        val repository = repositoryWith(signedIn(), transport(), backgroundScope)
        repository.login(1L, "secret1")
        val viewModel = ProfileViewModel(repository)

        viewModel.open()
        testScheduler.runCurrent()

        val state = viewModel.state.value
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertEquals("alice", state.username)
        assertEquals(1L, state.userId)
        assertEquals(
            "http://example.test:12345/avatar/u1-77802f6e.png",
            state.avatarUrl
        )
        assertTrue(state.hasAvatar)
        assertEquals(3145728L, state.maxBytes)
    }

    @Test
    fun `a failing load reports a readable error and stops the spinner`() = runTest {
        val repository = repositoryWith(
            signedIn(),
            transport(user = { HttpResponse(500, """{"error_message":"服务器坏了"}""") }),
            backgroundScope
        )
        repository.login(1L, "secret1")
        val viewModel = ProfileViewModel(repository)

        viewModel.open()
        testScheduler.runCurrent()

        val state = viewModel.state.value
        assertFalse(state.isLoading)
        assertEquals("服务器坏了", state.error)
    }

    @Test
    fun `an image the server would refuse never leaves the device`() = runTest {
        val transport = transport()
        val repository = repositoryWith(signedIn(), transport, backgroundScope)
        repository.login(1L, "secret1")
        val viewModel = ProfileViewModel(repository)
        viewModel.open()
        testScheduler.runCurrent()
        transport.requests.clear()

        viewModel.setAvatar("not an image".toByteArray())

        assertTrue("no upload request may be sent", transport.requests.isEmpty())
        assertEquals("图片必须是 PNG、JPEG、WebP 或 GIF", viewModel.state.value.message)
        assertFalse(viewModel.state.value.isUploading)
    }

    @Test
    fun `a successful upload replaces the avatar and says so`() = runTest {
        val repository = repositoryWith(signedIn(), transport(), backgroundScope)
        repository.login(1L, "secret1")
        val viewModel = ProfileViewModel(repository)
        viewModel.open()
        testScheduler.runCurrent()

        viewModel.setAvatar(validPng)
        testScheduler.runCurrent()

        val state = viewModel.state.value
        assertEquals(
            "http://example.test:12345/avatar/u1-deadbeef.png",
            state.avatarUrl
        )
        assertTrue(state.hasAvatar)
        assertFalse(state.isUploading)
        assertEquals("头像已更新", state.message)
    }

    @Test
    fun `a failed upload keeps the previous avatar`() = runTest {
        val repository = repositoryWith(
            signedIn(),
            transport(upload = { HttpResponse(400, """{"error_message":"图片太大"}""") }),
            backgroundScope
        )
        repository.login(1L, "secret1")
        val viewModel = ProfileViewModel(repository)
        viewModel.open()
        testScheduler.runCurrent()

        viewModel.setAvatar(validPng)
        testScheduler.runCurrent()

        val state = viewModel.state.value
        assertEquals(
            "the old picture stays until the server accepts a new one",
            "http://example.test:12345/avatar/u1-77802f6e.png",
            state.avatarUrl
        )
        assertEquals("图片太大", state.message)
        assertFalse(state.isUploading)
    }

    @Test
    fun `removing the avatar clears the picture and says so`() = runTest {
        val repository = repositoryWith(signedIn(), transport(), backgroundScope)
        repository.login(1L, "secret1")
        val viewModel = ProfileViewModel(repository)
        viewModel.open()
        testScheduler.runCurrent()

        viewModel.removeAvatar()
        testScheduler.runCurrent()

        val state = viewModel.state.value
        assertNull(state.avatarUrl)
        assertFalse(state.hasAvatar)
        assertEquals("头像已移除", state.message)
        assertFalse(state.isRemoving)
    }

    @Test
    fun `removing when there is no avatar does nothing`() = runTest {
        val transport = transport(
            user = { HttpResponse(200, """{"data":{"id":1,"username":"alice"}}""") },
            avatar = { HttpResponse(200, """{"data":{"hasAvatar":false}}""") }
        )
        val repository = repositoryWith(signedIn(), transport, backgroundScope)
        repository.login(1L, "secret1")
        val viewModel = ProfileViewModel(repository)
        viewModel.open()
        testScheduler.runCurrent()
        transport.requests.clear()

        viewModel.removeAvatar()

        assertTrue(transport.requests.isEmpty())
        assertFalse(viewModel.state.value.isRemoving)
    }

    @Test
    fun `a late upload result is dropped after the page is reopened`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = repositoryWith(
            signedIn(),
            transport(
                upload = {
                    gate.await()
                    HttpResponse(200, """{"data":{"avatarUrl":"/avatar/u1-old.png","hasAvatar":true}}""")
                }
            ),
            backgroundScope
        )
        repository.login(1L, "secret1")
        val viewModel = ProfileViewModel(repository)
        viewModel.open()
        testScheduler.runCurrent()

        viewModel.setAvatar(validPng)
        testScheduler.runCurrent()
        // The user leaves and comes back before the upload answers.
        viewModel.close()
        viewModel.open()
        testScheduler.runCurrent()

        gate.complete(Unit)
        testScheduler.runCurrent()

        assertEquals(
            "the second visit's profile must not be overwritten by the first one's upload",
            "http://example.test:12345/avatar/u1-77802f6e.png",
            viewModel.state.value.avatarUrl
        )
    }

    @Test
    fun `a 401 leaves no error text behind because the app is going to auth`() = runTest {
        val repository = repositoryWith(
            signedIn(),
            transport(
                upload = { HttpResponse(401, """{"error_message":"登录已失效"}""") }
            ),
            backgroundScope
        )
        repository.login(1L, "secret1")
        val viewModel = ProfileViewModel(repository)
        viewModel.open()
        testScheduler.runCurrent()

        viewModel.setAvatar(validPng)
        testScheduler.runCurrent()

        val state = viewModel.state.value
        assertNull("the sign-in screen is the explanation", state.message)
        assertNull(state.error)
        assertNull(repository.credentials.value)
    }

    @Test
    fun `a 403 on upload is an inline message and keeps the login`() = runTest {
        val repository = repositoryWith(
            signedIn(),
            transport(
                upload = { HttpResponse(403, """{"error_message":"只能改自己的头像"}""") }
            ),
            backgroundScope
        )
        repository.login(1L, "secret1")
        val viewModel = ProfileViewModel(repository)
        viewModel.open()
        testScheduler.runCurrent()

        viewModel.setAvatar(validPng)
        testScheduler.runCurrent()

        assertEquals("只能改自己的头像", viewModel.state.value.message)
        assertTrue("a permission answer is not a sign-out", repository.credentials.value != null)
    }

    @Test
    fun `consuming the message clears it once`() = runTest {
        val repository = repositoryWith(signedIn(), transport(), backgroundScope)
        repository.login(1L, "secret1")
        val viewModel = ProfileViewModel(repository)
        viewModel.open()
        testScheduler.runCurrent()
        viewModel.setAvatar(validPng)
        testScheduler.runCurrent()

        viewModel.consumeMessage()

        assertNull(viewModel.state.value.message)
    }
}
