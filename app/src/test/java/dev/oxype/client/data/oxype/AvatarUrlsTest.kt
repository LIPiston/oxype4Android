package dev.oxype.client.data.oxype

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Avatar source resolution against the configured server.
 *
 * Oxype does not pin the avatar field's shape. A deployment may answer an absolute URL
 * (possibly on a CDN), a path relative to the server root, or a bare relative path; the
 * client has to turn all of those into something loadable — and reject everything that
 * is not HTTP(S), because a `file:` or `data:` value must never be fetched.
 */
class AvatarUrlsTest {

    private val base = "http://192.168.1.20:12345"

    @Test
    fun `an absolute http url is kept as it is`() {
        assertEquals(
            "https://cdn.example.test/a.png",
            AvatarUrls.resolve("https://cdn.example.test/a.png", base)
        )
        assertEquals(
            "http://cdn.example.test/a.png",
            AvatarUrls.resolve("http://cdn.example.test/a.png", base)
        )
    }

    @Test
    fun `a root relative path is joined to the configured server`() {
        assertEquals(
            "http://192.168.1.20:12345/static/avatars/7.png",
            AvatarUrls.resolve("/static/avatars/7.png", base)
        )
    }

    @Test
    fun `a bare relative path is resolved under the server`() {
        assertEquals(
            "http://192.168.1.20:12345/avatars/7.png",
            AvatarUrls.resolve("avatars/7.png", base)
        )
        assertEquals(
            "http://192.168.1.20:12345/avatars/7.png",
            AvatarUrls.resolve("  avatars/7.png  ", base)
        )
    }

    @Test
    fun `a protocol relative value takes the server scheme`() {
        assertEquals(
            "https://cdn.example.test/a.png",
            AvatarUrls.resolve("//cdn.example.test/a.png", "https://chat.example.test")
        )
    }

    @Test
    fun `a non http scheme is refused`() {
        // None of these may become a request: `file:` is a local read, `data:` bypasses
        // the fetch entirely, and `javascript:`/`content:` are not images at all.
        val rejected = listOf(
            "file:///etc/passwd",
            "data:image/png;base64,AAAA",
            "javascript:alert(1)",
            "content://media/external/images/1",
            "ftp://example.test/a.png"
        )
        for (value in rejected) {
            assertNull("must be refused: $value", AvatarUrls.resolve(value, base))
        }
    }

    @Test
    fun `blank and malformed values resolve to nothing`() {
        assertNull(AvatarUrls.resolve(null, base))
        assertNull(AvatarUrls.resolve("", base))
        assertNull(AvatarUrls.resolve("   ", base))
        assertNull(AvatarUrls.resolve("http://", base))
        assertNull(AvatarUrls.resolve("/a.png", "not a url"))
    }

    @Test
    fun `isLoadable accepts only absolute http urls with a host`() {
        assertTrue(AvatarUrls.isLoadable("https://example.test/a.png"))
        assertTrue(AvatarUrls.isLoadable("http://10.0.2.2:12345/a.png"))
        assertTrue(!AvatarUrls.isLoadable("/a.png"))
        assertTrue(!AvatarUrls.isLoadable("file:///a.png"))
        assertTrue(!AvatarUrls.isLoadable(""))
        assertTrue(!AvatarUrls.isLoadable(null))
    }

    @Test
    fun `session members resolve avatars against the current server`() = runBlocking {
        val api = OxypeApi(
            OxypeHttpClient(
                transport = object : HttpTransport {
                    override suspend fun execute(request: HttpRequest): HttpResponse =
                        HttpResponse(
                            200,
                            """{"data":{"users":[
                                {"userid":1,"username":"alice","avatar":"/static/1.png"},
                                {"userid":2,"username":"bob","avatar":"relative/2.png"},
                                {"userid":3,"username":"carol","avatar":"https://cdn.test/3.png"},
                                {"userid":4,"username":"dave","avatar":"file:///tmp/4.png"},
                                {"userid":5,"username":"erin","avatar":""}
                            ]}}"""
                        )
                },
                baseUrl = "http://10.0.2.2:12345"
            )
        )
        val members = api.sessionMembers(AuthCredentials(1L, "t"), sessionId = 9)
            .associateBy { it.userId }

        assertEquals("http://10.0.2.2:12345/static/1.png", members.getValue(1L).avatarUrl)
        assertEquals("http://10.0.2.2:12345/relative/2.png", members.getValue(2L).avatarUrl)
        assertEquals("https://cdn.test/3.png", members.getValue(3L).avatarUrl)
        assertNull("a local file must not become an avatar", members.getValue(4L).avatarUrl)
        assertNull(members.getValue(5L).avatarUrl)
    }

    @Test
    fun `an avatar follows a changed server address`() = runBlocking {
        // The resolver must read the *current* base URL, not the one at construction: the
        // user can change the server from the settings screen without restarting.
        val http = OxypeHttpClient(
            transport = object : HttpTransport {
                override suspend fun execute(request: HttpRequest): HttpResponse =
                    HttpResponse(
                        200,
                        """{"data":{"users":[{"userid":1,"username":"a","avatar":"/a.png"}]}}"""
                    )
            },
            baseUrl = "http://first.test:12345"
        )
        val api = OxypeApi(http)
        assertEquals(
            "http://first.test:12345/a.png",
            api.sessionMembers(AuthCredentials(1L, "t"), 9).single().avatarUrl
        )

        http.updateBaseUrl("https://second.test:4443")

        assertEquals(
            "https://second.test:4443/a.png",
            api.sessionMembers(AuthCredentials(1L, "t"), 9).single().avatarUrl
        )
    }
}
