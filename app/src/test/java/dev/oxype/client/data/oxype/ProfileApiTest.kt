package dev.oxype.client.data.oxype

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The profile and avatar routes.
 *
 * `getUser` carries the name and the avatar URL, `getAvatar` carries the upload rules and
 * the authoritative `hasAvatar`. They are separate routes on purpose (a stranger may read
 * the name without being allowed to see the picture), so the client has to merge them and
 * survive the second one being denied.
 */
class ProfileApiTest {

    private val credentials = AuthCredentials(1L, "tok")

    private fun api(handler: suspend (String) -> HttpResponse) = OxypeApi(
        OxypeHttpClient(
            transport = object : HttpTransport {
                val seen = mutableListOf<HttpRequest>()
                override suspend fun execute(request: HttpRequest): HttpResponse {
                    seen.add(request)
                    return handler("/" + request.url.substringAfter("://").substringAfter('/').substringBefore('?'))
                }
            },
            baseUrl = "http://example.test:12345"
        )
    )

    @Test
    fun `getUser and getAvatar merge into one profile`() = runTest {
        val api = api { route ->
            when (route) {
                "/getUser/1" -> HttpResponse(
                    200,
                    """{"data":{"id":1,"username":"alice","avatarUrl":"/avatar/u1-77802f6e.png"}}"""
                )

                "/getAvatar/1" -> HttpResponse(
                    200,
                    """{"data":{"kind":"user","id":1,"avatarUrl":"/avatar/u1-77802f6e.png",
                        "hasAvatar":true,"maxBytes":3145728,"maxEdge":4096,
                        "formats":["image/png","image/jpeg","image/webp","image/gif"]}}"""
                )

                else -> HttpResponse(404, """{"error_message":"no"}""")
            }
        }

        val profile = api.profile(credentials)

        assertEquals(1L, profile.userId)
        assertEquals("alice", profile.username)
        assertTrue(profile.hasAvatar)
        assertEquals("http://example.test:12345/avatar/u1-77802f6e.png", profile.avatarUrl)
        assertEquals(3L * 1024 * 1024, profile.maxBytes)
        assertEquals(4096, profile.maxEdge)
        assertEquals(
            listOf("image/png", "image/jpeg", "image/webp", "image/gif"),
            profile.formats
        )
    }

    @Test
    fun `a denied getAvatar still yields the profile from getUser`() = runTest {
        val api = api { route ->
            when (route) {
                "/getUser/1" -> HttpResponse(
                    200,
                    """{"data":{"id":1,"username":"alice"}}"""
                )

                "/getAvatar/1" -> HttpResponse(403, """{"error_message":"forbidden"}""")
                else -> HttpResponse(404, """{"error_message":"no"}""")
            }
        }

        val profile = api.profile(credentials)

        assertEquals("alice", profile.username)
        assertNull("no avatar field means no avatar", profile.avatarUrl)
        assertFalse(profile.hasAvatar)
        assertEquals("the server's upload limits are defaulted", 3L * 1024 * 1024, profile.maxBytes)
    }

    @Test
    fun `snake case avatar fields are read`() = runTest {
        val api = api { route ->
            when (route) {
                "/getUser/1" -> HttpResponse(
                    200,
                    """{"data":{"id":"1","username":"alice","avatar_url":"avatar/u1-abcd1234.png"}}"""
                )

                "/getAvatar/1" -> HttpResponse(
                    200,
                    """{"data":{"has_avatar":true,"max_bytes":1048576,"max_edge":2048,
                        "formats":["image/png"]}}"""
                )

                else -> HttpResponse(404, """{"error_message":"no"}""")
            }
        }

        val profile = api.profile(credentials)

        assertEquals("http://example.test:12345/avatar/u1-abcd1234.png", profile.avatarUrl)
        assertTrue(profile.hasAvatar)
        assertEquals(1_048_576L, profile.maxBytes)
        assertEquals(2048, profile.maxEdge)
        assertEquals(listOf("image/png"), profile.formats)
    }

    @Test
    fun `an avatar URL that is not http is ignored`() = runTest {
        val api = api { route ->
            when (route) {
                "/getUser/1" -> HttpResponse(
                    200,
                    """{"data":{"id":1,"username":"alice","avatarUrl":"data:image/png;base64,AAAA"}}"""
                )

                "/getAvatar/1" -> HttpResponse(200, """{"data":{"hasAvatar":true}}""")
                else -> HttpResponse(404, """{"error_message":"no"}""")
            }
        }

        val profile = api.profile(credentials)

        assertNull(profile.avatarUrl)
    }

    @Test
    fun `upload sends the bare base64 payload`() = runTest {
        val transport = RecordingTransport { route ->
            when (route) {
                "/uploadAvatar/1" -> HttpResponse(
                    200,
                    """{"data":{"avatarUrl":"/avatar/u1-deadbeef.png","hasAvatar":true,
                        "maxBytes":3145728,"maxEdge":4096,"formats":["image/png"]}}"""
                )

                else -> HttpResponse(404, """{"error_message":"no"}""")
            }
        }
        val api = OxypeApi(
            OxypeHttpClient(transport = transport, baseUrl = "http://example.test:12345")
        )

        val profile = api.uploadAvatar(credentials, "data:image/png;base64,aGVsbG8=")

        val upload = transport.seen.first()
        val body = org.json.JSONObject(upload.body.orEmpty())
        assertEquals("aGVsbG8=", body.getString("image"))
        assertEquals("tok", body.getString("token"))
        assertEquals(1L, body.getLong("userid"))
        assertEquals("http://example.test:12345/avatar/u1-deadbeef.png", profile.avatarUrl)
        assertTrue(profile.hasAvatar)
    }

    @Test
    fun `upload keeps the server reason on a 400`() = runTest {
        val api = api { route ->
            if (route == "/uploadAvatar/1") {
                HttpResponse(400, """{"error_message":"The image is larger than 3 MB"}""")
            } else {
                HttpResponse(404, """{"error_message":"no"}""")
            }
        }

        val error = runCatching { api.uploadAvatar(credentials, "aGVsbG8=") }.exceptionOrNull()

        val oxype = error as OxypeException
        assertEquals(OxypeErrorKind.BAD_REQUEST, oxype.kind)
        assertEquals("The image is larger than 3 MB", oxype.message)
    }

    @Test
    fun `upload classifies a 403 as forbidden and a 401 as unauthorized`() = runTest {
        val forbidden = runCatching {
            api { HttpResponse(403, """{"error_message":"only your own"}""") }
                .uploadAvatar(credentials, "aGVsbG8=")
        }.exceptionOrNull() as OxypeException
        assertEquals(OxypeErrorKind.FORBIDDEN, forbidden.kind)

        val unauthorized = runCatching {
            api { HttpResponse(401, """{"error_message":"token expired"}""") }
                .uploadAvatar(credentials, "aGVsbG8=")
        }.exceptionOrNull() as OxypeException
        assertEquals(OxypeErrorKind.UNAUTHORIZED, unauthorized.kind)
    }

    @Test
    fun `remove returns the state without an avatar`() = runTest {
        val api = api { route ->
            if (route == "/removeAvatar/1") {
                HttpResponse(
                    200,
                    """{"data":{"avatarUrl":"","hasAvatar":false,"maxBytes":3145728,
                        "maxEdge":4096,"formats":["image/png"]}}"""
                )
            } else {
                HttpResponse(404, """{"error_message":"no"}""")
            }
        }

        val profile = api.removeAvatar(credentials)

        assertNull(profile.avatarUrl)
        assertFalse(profile.hasAvatar)
    }

    @Test
    fun `a getUser answer without a name falls back to the numeric label`() = runTest {
        val api = api { route ->
            when (route) {
                "/getUser/1" -> HttpResponse(200, """{"data":{"id":1}}""")
                "/getAvatar/1" -> HttpResponse(404, """{"error_message":"no"}""")
                else -> HttpResponse(404, """{"error_message":"no"}""")
            }
        }

        val profile = api.profile(credentials)

        assertEquals("用户 1", profile.username)
    }

    private class RecordingTransport(
        private val handler: suspend (String) -> HttpResponse
    ) : HttpTransport {
        val seen = mutableListOf<HttpRequest>()

        override suspend fun execute(request: HttpRequest): HttpResponse {
            seen.add(request)
            return handler(
                "/" + request.url.substringAfter("://").substringAfter('/').substringBefore('?')
            )
        }
    }
}
