package dev.oxype.client.data

import dev.oxype.client.data.oxype.AuthCredentials
import dev.oxype.client.data.oxype.GroupRole
import dev.oxype.client.data.oxype.HttpResponse
import dev.oxype.client.data.oxype.OxypeErrorKind
import dev.oxype.client.data.oxype.OxypeException
import dev.oxype.client.testsupport.FakeSessionStorage
import dev.oxype.client.testsupport.ScriptedTransport
import dev.oxype.client.testsupport.repositoryWith
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the group info page.
 *
 * Two routes answer, and neither is guaranteed to be complete: the session detail may
 * omit `admins` or the session avatar, and the member directory may deny access while the
 * detail route still works. The page must degrade to *something* readable instead of
 * failing, and the "am I owner/admin" answer has exactly one source so the header badge
 * and the member rows cannot disagree.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupInfoTest {

    private fun signedIn() = FakeSessionStorage().apply {
        saveAuth(AuthCredentials(1L, "tok"), "alice")
    }

    private fun transport(
        detail: String? = null,
        directory: String? = """{"users":[{"userid":2,"username":"bob"}]}""",
        directoryStatus: Int = 200,
        detailStatus: Int = 200
    ) = ScriptedTransport { _, route ->
        when {
            route.startsWith("/getSessionInfo/") -> when {
                detail != null -> HttpResponse(detailStatus, detail)
                detailStatus != 200 -> HttpResponse(detailStatus, """{"error_message":"forbidden"}""")
                else -> HttpResponse(404, """{"error_message":"no detail"}""")
            }

            route.startsWith("/getSession/") -> HttpResponse(404, """{"error_message":"no get"}""")

            route.startsWith("/getSessionUsers/") -> HttpResponse(directoryStatus, directory ?: "")

            else -> null
        }
    }

    private suspend fun open(
        repository: OxypeRepository,
        sessionId: Long = 42L
    ) = repository.loadGroupInfo(sessionId)

    @Test
    fun `admins and the session avatar are read from the detail payload`() = runTest {
        val transport = transport(
            detail = """{"data":{
                "sessionId":42,"name":"周末徒步小队","description":"每周六出发",
                "owner":2,"admins":[2,3],"avatarUrl":"/static/sessions/42.png",
                "users":[{"userid":2,"username":"林间小道"},{"userid":3,"username":"青柠"}]
            }}"""
        )
        val repository = repositoryWith(signedIn(), transport, backgroundScope)
        repository.login(1L, "secret1")

        val info = open(repository)

        assertEquals(listOf(2L, 3L), info.adminIds)
        assertEquals(
            "a relative session avatar resolves against the configured server",
            "http://example.test:12345/static/sessions/42.png",
            info.avatarUrl
        )
        assertEquals("周末徒步小队", info.name)
    }

    @Test
    fun `a camel-case admin list is read too`() = runTest {
        val transport = transport(
            detail = """{"data":{"sessionId":42,"name":"群","owner":2,"adminIds":[3],
                "users":[{"userid":2,"username":"a"},{"userid":3,"username":"b"}]}}"""
        )
        val repository = repositoryWith(signedIn(), transport, backgroundScope)
        repository.login(1L, "secret1")

        assertEquals(listOf(3L), open(repository).adminIds)
    }

    @Test
    fun `a detail without admins or an avatar still yields a usable page`() = runTest {
        val transport = transport(
            detail = """{"data":{"sessionId":42,"name":"群","owner":2,
                "users":[{"userid":2,"username":"a"}]}}"""
        )
        val repository = repositoryWith(signedIn(), transport, backgroundScope)
        repository.login(1L, "secret1")

        val info = open(repository)

        // Missing is not an error: an empty admin list and no avatar are perfectly valid
        // answers from a server that never had those fields.
        assertTrue(info.adminIds.isEmpty())
        assertNull(info.avatarUrl)
        assertEquals("群", info.name)
    }

    @Test
    fun `the directory fills in names the detail route omitted`() = runTest {
        val transport = transport(
            detail = """{"data":{"sessionId":42,"name":"群","owner":2,
                "users":[{"userid":2},{"userid":3}]}}""",
            directory = """{"users":[
                {"userid":2,"username":"林间小道","avatarUrl":"https://example.test/a.png"},
                {"userid":3,"username":"青柠"}
            ]}"""
        )
        val repository = repositoryWith(signedIn(), transport, backgroundScope)
        repository.login(1L, "secret1")

        val info = open(repository)

        // The detail order wins; the directory only supplies the missing labels.
        assertEquals(listOf(2L, 3L), info.members.map { it.userId })
        assertEquals("林间小道", info.members[0].username)
        assertEquals("https://example.test/a.png", info.members[0].avatarUrl)
        assertEquals("青柠", info.members[1].username)
    }

    @Test
    fun `a denied directory degrades to numeric labels instead of failing the page`() = runTest {
        val transport = transport(
            detail = """{"data":{"sessionId":42,"name":"群","owner":2,
                "users":[{"userid":2},{"userid":3}]}}""",
            directoryStatus = 403
        )
        val repository = repositoryWith(signedIn(), transport, backgroundScope)
        repository.login(1L, "secret1")

        val info = open(repository)

        assertEquals(2, info.members.size)
        assertEquals("用户 2", info.members[0].username)
        // A 403 on a supplementary route is about that route, never about the login.
        assertNotNull(repository.credentials.value)
    }

    @Test
    fun `the id list wins over a contradictory server boolean`() = runTest {
        val transport = transport(
            detail = """{"data":{"sessionId":42,"name":"群","owner":2,"admins":[2],
                "isAdmin":true,"isOwner":false,
                "users":[{"userid":1,"username":"alice"},{"userid":2,"username":"bob"}]}}"""
        )
        val repository = repositoryWith(signedIn(), transport, backgroundScope)
        repository.login(1L, "secret1")

        val info = open(repository)

        // Account 1 is neither the owner nor in the admin list, so the header must not
        // claim otherwise even though the server said `isAdmin: true`.
        assertFalse("ids are the single source of truth", info.isAdmin)
        assertFalse(info.isOwner)
    }

    @Test
    fun `ownership comes from the owner id`() = runTest {
        val transport = transport(
            detail = """{"data":{"sessionId":42,"name":"群","owner":1,"admins":[],
                "users":[{"userid":1,"username":"alice"}]}}"""
        )
        val repository = repositoryWith(signedIn(), transport, backgroundScope)
        repository.login(1L, "secret1")

        val info = open(repository)

        assertTrue(info.isOwner)
        assertTrue("an owner is also an admin", info.isAdmin)
    }

    @Test
    fun `an admin is not the owner`() = runTest {
        val transport = transport(
            detail = """{"data":{"sessionId":42,"name":"群","owner":2,"admins":[1],
                "users":[{"userid":1,"username":"alice"},{"userid":2,"username":"bob"}]}}"""
        )
        val repository = repositoryWith(signedIn(), transport, backgroundScope)
        repository.login(1L, "secret1")

        val info = open(repository)

        assertFalse(info.isOwner)
        assertTrue(info.isAdmin)
    }

    @Test
    fun `a boolean-only answer is folded into ids so the row and the header agree`() = runTest {
        // A server that reports roles as booleans only (no owner/admin id lists) still
        // says the current account is the owner. The header reads that; the member rows
        // read ids. Folding the booleans into the ids once is what keeps the two from
        // contradicting each other.
        val transport = transport(
            detail = """{"data":{"sessionId":42,"name":"群","isOwner":true,"isAdmin":true,
                "users":[{"userid":1,"username":"alice"},{"userid":2,"username":"bob"}]}}"""
        )
        val repository = repositoryWith(signedIn(), transport, backgroundScope)
        repository.login(1L, "secret1")

        val info = open(repository)

        assertTrue(info.isOwner)
        assertTrue(info.isAdmin)
        assertEquals("the owner id must be filled in for the member row", 1L, info.ownerId)
        assertEquals(GroupRole.OWNER, info.roleOf(1L))
        assertEquals(GroupRole.MEMBER, info.roleOf(2L))
    }

    @Test
    fun `a boolean-only admin becomes an admin row without claiming ownership`() = runTest {
        val transport = transport(
            detail = """{"data":{"sessionId":42,"name":"群","isOwner":false,"isAdmin":true,
                "users":[{"userid":1,"username":"alice"},{"userid":2,"username":"bob"}]}}"""
        )
        val repository = repositoryWith(signedIn(), transport, backgroundScope)
        repository.login(1L, "secret1")

        val info = open(repository)

        assertFalse(info.isOwner)
        assertTrue(info.isAdmin)
        assertEquals(listOf(1L), info.adminIds)
        assertEquals(GroupRole.ADMIN, info.roleOf(1L))
    }

    @Test
    fun `an admin row label never contradicts the header`() = runTest {
        // Both answers come from the same ids, so any input at all must agree.
        val transport = transport(
            detail = """{"data":{"sessionId":42,"name":"群","owner":2,"admins":[2],
                "isOwner":true,"isAdmin":true,
                "users":[{"userid":1,"username":"alice"},{"userid":2,"username":"bob"}]}}"""
        )
        val repository = repositoryWith(signedIn(), transport, backgroundScope)
        repository.login(1L, "secret1")

        val info = open(repository)

        assertFalse(info.isOwner)
        assertFalse(info.isAdmin)
        assertEquals(GroupRole.MEMBER, info.roleOf(1L))
        assertEquals(GroupRole.OWNER, info.roleOf(2L))
    }

    @Test
    fun `a forbidden detail route throws without clearing the login`() = runTest {
        val transport = transport(detailStatus = 403)
        val repository = repositoryWith(signedIn(), transport, backgroundScope)
        repository.login(1L, "secret1")

        val failure = try {
            open(repository)
            null
        } catch (e: OxypeException) {
            e
        }

        assertNotNull(failure)
        assertEquals(OxypeErrorKind.FORBIDDEN, failure!!.kind)
        assertNotNull("403 is per-conversation, never a sign-out", repository.credentials.value)
        assertFalse(repository.sessionExpired.value)
    }

    @Test
    fun `a rejected token clears the login`() = runTest {
        val transport = transport(detailStatus = 401)
        val repository = repositoryWith(signedIn(), transport, backgroundScope)
        repository.login(1L, "secret1")

        val failure = try {
            open(repository)
            null
        } catch (e: OxypeException) {
            e
        }

        assertNotNull(failure)
        assertEquals(OxypeErrorKind.UNAUTHORIZED, failure!!.kind)
        assertNull(repository.credentials.value)
        assertTrue(repository.sessionExpired.value)
    }
}
