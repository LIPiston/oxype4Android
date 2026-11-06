package dev.oxype.client.data.oxype

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Member directory decoding, including the optional avatar aliases.
 *
 * The Oxype server may not send an avatar at all, so every alias is optional and a
 * missing/blank value must stay `null` instead of becoming an empty string or a made-up
 * URL.
 */
class SessionMembersTest {

    private fun apiWith(body: String): OxypeApi = OxypeApi(
        OxypeHttpClient(
            transport = object : HttpTransport {
                override suspend fun execute(request: HttpRequest): HttpResponse =
                    HttpResponse(200, body)
            }
        )
    )

    @Test
    fun `member avatars accept every known alias`() = runBlocking {
        val api = apiWith(
            """{"data":{"users":[
                {"userid":1,"username":"alice","avatarUrl":"https://example.test/a.png"},
                {"userid":2,"username":"bob","avatar_url":"https://example.test/b.png"},
                {"userid":3,"username":"carol","avatar":"https://example.test/c.png"},
                {"userid":4,"username":"dave","photoUrl":"https://example.test/d.png"},
                {"userid":5,"username":"erin","photo":"https://example.test/e.png"},
                {"userid":6,"username":"frank"}
            ]}}"""
        )
        val members = api.sessionMembers(AuthCredentials(1L, "t"), sessionId = 9)
            .associateBy { it.userId }

        assertEquals("https://example.test/a.png", members.getValue(1L).avatarUrl)
        assertEquals("https://example.test/b.png", members.getValue(2L).avatarUrl)
        assertEquals("https://example.test/c.png", members.getValue(3L).avatarUrl)
        assertEquals("https://example.test/d.png", members.getValue(4L).avatarUrl)
        assertEquals("https://example.test/e.png", members.getValue(5L).avatarUrl)
        assertNull("no avatar field must stay null", members.getValue(6L).avatarUrl)
    }

    @Test
    fun `a blank avatar field is not an image source`() = runBlocking {
        val api = apiWith(
            """{"data":{"users":[
                {"userid":1,"username":"alice","avatarUrl":"   "},
                {"userid":2,"username":"bob","avatar":""}
            ]}}"""
        )
        val members = api.sessionMembers(AuthCredentials(1L, "t"), sessionId = 9)

        assertNull(members[0].avatarUrl)
        assertNull(members[1].avatarUrl)
    }

    @Test
    fun `a member without a name keeps a numeric label`() = runBlocking {
        val api = apiWith("""{"data":{"users":[{"userid":7},{"userid":8,"username":""}]}}""")
        val members = api.sessionMembers(AuthCredentials(1L, "t"), sessionId = 9)

        assertEquals(listOf("用户 7", "用户 8"), members.map { it.username })
    }

    @Test
    fun `an unreadable member payload is an empty directory not a failure`() = runBlocking {
        // The directory is supplementary: a changed payload must not break opening a
        // conversation. The transcript still renders with numeric fallbacks.
        val api = apiWith("""{"data":{"unexpected":true}}""")

        assertTrue(api.sessionMembers(AuthCredentials(1L, "t"), sessionId = 9).isEmpty())
    }

    @Test
    fun `session detail members also decode avatars`() = runBlocking {
        val api = apiWith(
            """{"data":{"sessionId":9,"sessionName":"团队","owner":1,
                "users":[{"userid":1,"username":"alice","photoUrl":"https://example.test/a.png"}]}}"""
        )
        val detail = api.sessionInfo(AuthCredentials(1L, "t"), sessionId = 9)

        assertEquals("https://example.test/a.png", detail.members.single().avatarUrl)
    }
}
