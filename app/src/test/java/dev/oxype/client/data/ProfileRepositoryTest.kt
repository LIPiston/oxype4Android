package dev.oxype.client.data

import dev.oxype.client.data.oxype.AuthCredentials
import dev.oxype.client.data.oxype.HttpResponse
import dev.oxype.client.data.oxype.OxypeErrorKind
import dev.oxype.client.data.oxype.OxypeException
import dev.oxype.client.testsupport.FakeSessionStorage
import dev.oxype.client.testsupport.ScriptedTransport
import dev.oxype.client.testsupport.repositoryWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The account profile the repository holds.
 *
 * A profile belongs to one login on one server: the same id on another server is a
 * different person, and signing the same account in again issues a different token. The
 * cached snapshot therefore has to be dropped whenever either changes, or the profile
 * page would show the previous account's name and picture.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileRepositoryTest {

    private fun signedIn(userId: Long = 1L, token: String = "tok") =
        FakeSessionStorage().apply { saveAuth(AuthCredentials(userId, token), "alice") }

    private fun okProfile(
        userId: Long = 1L,
        username: String = "alice",
        avatarUrl: String = "/avatar/u1-77802f6e.png"
    ): String = """{"data":{"id":$userId,"username":"$username","avatarUrl":"$avatarUrl"}}"""

    private fun avatarView(
        avatarUrl: String,
        hasAvatar: Boolean = avatarUrl.isNotEmpty()
    ): String = """{"data":{"id":1,"name":"alice","avatarUrl":"$avatarUrl","hasAvatar":$hasAvatar,
        "maxBytes":3145728,"maxEdge":4096,"formats":["image/png"]}}"""

    /** A transport that answers profile routes from lambdas, so each test states its own. */
    private fun transport(
        user: suspend (String) -> HttpResponse = { HttpResponse(200, okProfile()) },
        avatar: suspend (String) -> HttpResponse = { HttpResponse(200, avatarView(it)) },
        upload: suspend () -> HttpResponse = { HttpResponse(200, avatarView("/avatar/u1-deadbeef.png")) },
        remove: suspend () -> HttpResponse = { HttpResponse(200, avatarView("")) }
    ) = ScriptedTransport { request, route ->
        when {
            route.startsWith("/getUser/") -> user(route.substringAfterLast('/'))
            route.startsWith("/getAvatar/") -> avatar("/avatar/u1-77802f6e.png")
            route == "/uploadAvatar/1" -> upload()
            route == "/removeAvatar/1" -> remove()
            else -> null
        }
    }

    @Test
    fun `a successful load publishes the merged profile`() = runTest {
        val repository = repositoryWith(signedIn(), transport(), backgroundScope)
        repository.login(1L, "secret1")

        val profile = repository.loadProfile()

        assertEquals("alice", profile.username)
        assertEquals(
            "http://example.test:12345/avatar/u1-77802f6e.png",
            profile.avatarUrl
        )
        assertEquals(profile, repository.profile.value)
    }

    @Test
    fun `a 401 on the profile signs the account out`() = runTest {
        val repository = repositoryWith(
            signedIn(),
            transport(user = { HttpResponse(401, """{"error_message":"token expired"}""") }),
            backgroundScope
        )
        repository.login(1L, "secret1")

        runCatching { repository.loadProfile() }

        assertNull("the rejected token is gone", repository.credentials.value)
        assertNull("its profile goes with it", repository.profile.value)
        assertTrue(repository.sessionExpired.value)
    }

    @Test
    fun `a 403 leaves the login and the stored profile alone`() = runTest {
        val repository = repositoryWith(
            signedIn(),
            transport(user = { HttpResponse(403, """{"error_message":"not allowed"}""") }),
            backgroundScope
        )
        repository.login(1L, "secret1")

        val error = runCatching { repository.loadProfile() }.exceptionOrNull()

        assertEquals(OxypeErrorKind.FORBIDDEN, (error as OxypeException).kind)
        assertNotNull("a permission answer is not a sign-out", repository.credentials.value)
        assertFalse(repository.sessionExpired.value)
    }

    @Test
    fun `signing in as another account drops the previous profile`() = runTest {
        val transport = ScriptedTransport { _, route ->
            when {
                route == "/getUser/1" -> HttpResponse(200, okProfile(1L, "alice"))
                route == "/getAvatar/1" -> HttpResponse(200, avatarView("/avatar/u1-aaaaaaaa.png"))
                route == "/login/2" -> HttpResponse(200, """{"data":{"token":"b-tok"}}""")
                route == "/getUser/2" -> HttpResponse(200, okProfile(2L, "bob", "/avatar/u2-bbbbbbbb.png"))
                route == "/getAvatar/2" -> HttpResponse(200, avatarView("/avatar/u2-bbbbbbbb.png"))
                else -> null
            }
        }
        val repository = repositoryWith(signedIn(), transport, backgroundScope)
        repository.login(1L, "secret1")
        repository.loadProfile()
        assertEquals("alice", repository.profile.value?.username)

        repository.login(2L, "secret2")

        assertNull("B must never see A's profile", repository.profile.value)
        val profile = repository.loadProfile()
        assertEquals("bob", profile.username)
        assertTrue(profile.avatarUrl.orEmpty().endsWith("/avatar/u2-bbbbbbbb.png"))
    }

    @Test
    fun `changing the server re-reads the profile against the new address`() = runTest {
        val hosts = mutableListOf<String>()
        val transport = ScriptedTransport { request, route ->
            hosts += request.url.substringBefore("/get")
            when {
                route.startsWith("/getUser/") -> HttpResponse(200, okProfile())
                route.startsWith("/getAvatar/") -> HttpResponse(
                    200,
                    avatarView("/avatar/u1-cccccccc.png")
                )

                else -> null
            }
        }
        val repository = repositoryWith(signedIn(), transport, backgroundScope)
        repository.login(1L, "secret1")
        repository.loadProfile()

        repository.updateBaseUrl("http://other.test:9999")
        val profile = repository.loadProfile()

        assertTrue(hosts.any { it.startsWith("http://example.test:12345") })
        assertTrue("the new server is asked, not the old one", hosts.last().startsWith("http://other.test:9999"))
        assertEquals(
            "the avatar is resolved against the server that answered",
            "http://other.test:9999/avatar/u1-77802f6e.png",
            profile.avatarUrl
        )
    }

    @Test
    fun `a successful upload publishes the new avatar URL`() = runTest {
        val repository = repositoryWith(signedIn(), transport(), backgroundScope)
        repository.login(1L, "secret1")

        val profile = repository.uploadAvatar("aGVsbG8=")

        assertEquals(
            "http://example.test:12345/avatar/u1-deadbeef.png",
            profile.avatarUrl
        )
        assertTrue(profile.hasAvatar)
        assertEquals(profile, repository.profile.value)
    }

    @Test
    fun `removing the avatar leaves the profile without a picture`() = runTest {
        val repository = repositoryWith(signedIn(), transport(), backgroundScope)
        repository.login(1L, "secret1")
        repository.loadProfile()

        val profile = repository.removeAvatar()

        assertNull(profile.avatarUrl)
        assertFalse(profile.hasAvatar)
        assertNull(repository.profile.value?.avatarUrl)
        assertEquals("the name survives the removal", "alice", repository.profile.value?.username)
    }

    @Test
    fun `signing out clears the profile`() = runTest {
        val repository = repositoryWith(signedIn(), transport(), backgroundScope)
        repository.login(1L, "secret1")
        repository.loadProfile()

        repository.logout()

        assertNull(repository.profile.value)
    }

    @Test
    fun `a profile with no login is refused before any request`() = runTest {
        val transport = transport()
        val repository = repositoryWith(FakeSessionStorage(), transport, backgroundScope)

        val error = runCatching { repository.loadProfile() }.exceptionOrNull()

        assertEquals(OxypeErrorKind.UNAUTHORIZED, (error as OxypeException).kind)
        assertTrue("nothing was sent", transport.requests.isEmpty())
    }
}
