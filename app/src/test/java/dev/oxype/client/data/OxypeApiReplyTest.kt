package dev.oxype.client.data

import dev.oxype.client.data.oxype.AuthCredentials
import dev.oxype.client.data.oxype.HttpResponse
import dev.oxype.client.data.oxype.OxypeErrorKind
import dev.oxype.client.data.oxype.OxypeException
import dev.oxype.client.testsupport.ScriptedTransport
import dev.oxype.client.testsupport.repositoryWith
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Decoding the stored `reply` piece.
 *
 * The server writes the quoted snapshot itself, and this client cannot verify the
 * exact spelling those snapshot fields use. The rules under test therefore are:
 * `messageId` is the only hard dependency, everything else may be missing, and a
 * reply piece may never make an otherwise readable message fail to decode.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OxypeApiReplyTest {

    private val credentials = AuthCredentials(userId = 1L, token = "tok")

    private fun storage() = dev.oxype.client.testsupport.FakeSessionStorage()

    /** Serves one `getMessages` page built from [pieces] and nothing else. */
    private fun pageWith(pieces: String): ScriptedTransport = ScriptedTransport { _, route ->
        when {
            route.startsWith("/getMessages/") ->
                HttpResponse(200, """{"data":[$pieces]}""")

            route.startsWith("/lastMessage/") ->
                HttpResponse(200, """{"data":{"hasMessage":true,"id":8}}""")

            else -> null
        }
    }

    private fun messageJson(pieces: String, sender: Long = 2L, id: Long = 8L): String = """
        {"id":$id,"sender":$sender,"timestamp":1767225600000,"pieces":[$pieces]}
    """.trimIndent()

    /** Opens a chat and returns the message with [id] from the published snapshot. */
    private suspend fun loadMessage(transport: ScriptedTransport, id: Long = 8L) =
        kotlinx.coroutines.test.runTest {
            val repository = repositoryWith(storage(), transport, this)
            repository.login(1L, "pw")
            repository.openChat(42L)
            repository.chat.value?.messages?.firstOrNull { it.id == id }
        }

    @Test
    fun `reply piece exposes message id and snapshot text`() = runTest {
        val transport = pageWith(
            messageJson(
                """
                {"type":"reply","messageId":7,"sender":2,"senderName":"林间小道","text":"在那边等你们"},
                {"type":"text","text":"收到"}
                """.trimIndent()
            )
        )
        val repository = repositoryWith(storage(), transport, this)
        repository.login(1L, "pw")
        repository.openChat(42L)
        val message = repository.chat.value?.messages?.firstOrNull { it.id == 8L }

        assertNotNull(message)
        val reply = message!!.reply
        assertNotNull(reply)
        assertEquals(7L, reply!!.messageId)
        assertEquals(2L, reply.senderId)
        assertEquals("林间小道", reply.senderName)
        assertEquals("在那边等你们", reply.text)
    }

    @Test
    fun `plain text never includes the quoted snapshot`() = runTest {
        val transport = pageWith(
            messageJson(
                """
                {"type":"reply","messageId":7,"senderName":"林间小道","text":"被引用的原话"},
                {"type":"text","text":"我的回复"}
                """.trimIndent()
            )
        )
        val repository = repositoryWith(storage(), transport, this)
        repository.login(1L, "pw")
        repository.openChat(42L)
        val message = repository.chat.value?.messages?.first { it.id == 8L }

        assertEquals("我的回复", message!!.plainText)
    }

    @Test
    fun `reply piece with only a message id still decodes`() = runTest {
        val transport = pageWith(
            messageJson(
                """
                {"type":"reply","messageId":7},
                {"type":"text","text":"收到"}
                """.trimIndent()
            )
        )
        val repository = repositoryWith(storage(), transport, this)
        repository.login(1L, "pw")
        repository.openChat(42L)
        val reply = repository.chat.value?.messages?.first { it.id == 8L }?.reply

        assertNotNull(reply)
        assertEquals(7L, reply!!.messageId)
        assertNull(reply.senderId)
        assertNull(reply.senderName)
        assertNull(reply.text)
    }

    @Test
    fun `reply piece without a message id is ignored but the body survives`() = runTest {
        val transport = pageWith(
            messageJson(
                """
                {"type":"reply","senderName":"林间小道","text":"没有目标"},
                {"type":"text","text":"正文还在"}
                """.trimIndent()
            )
        )
        val repository = repositoryWith(storage(), transport, this)
        repository.login(1L, "pw")
        repository.openChat(42L)
        val message = repository.chat.value?.messages?.first { it.id == 8L }

        assertNull(message!!.reply)
        assertEquals("正文还在", message.plainText)
    }

    @Test
    fun `the first of several reply pieces wins`() = runTest {
        val transport = pageWith(
            messageJson(
                """
                {"type":"reply","messageId":7,"text":"第一条"},
                {"type":"reply","messageId":5,"text":"第二条"},
                {"type":"text","text":"正文"}
                """.trimIndent()
            )
        )
        val repository = repositoryWith(storage(), transport, this)
        repository.login(1L, "pw")
        repository.openChat(42L)
        val reply = repository.chat.value?.messages?.first { it.id == 8L }?.reply

        assertEquals(7L, reply?.messageId)
        assertEquals("第一条", reply?.text)
    }

    @Test
    fun `a reply piece after the text is still recognized`() = runTest {
        val transport = pageWith(
            messageJson(
                """
                {"type":"text","text":"正文"},
                {"type":"reply","messageId":7,"text":"引用"}
                """.trimIndent()
            )
        )
        val repository = repositoryWith(storage(), transport, this)
        repository.login(1L, "pw")
        repository.openChat(42L)
        val message = repository.chat.value?.messages?.first { it.id == 8L }

        assertEquals(7L, message!!.reply?.messageId)
        assertEquals("正文", message.plainText)
    }

    @Test
    fun `a message with only a reply piece has nothing to show`() = runTest {
        val transport = pageWith(
            messageJson("""{"type":"reply","messageId":7,"text":"引用"}""")
        )
        val repository = repositoryWith(storage(), transport, this)
        repository.login(1L, "pw")

        val failure = try {
            repository.openChat(42L)
            null
        } catch (e: OxypeException) {
            e
        }
        assertNotNull(failure)
        assertEquals(OxypeErrorKind.PROTOCOL, failure!!.kind)
        assertTrueText(failure.message.orEmpty(), "没有可显示的内容")
    }

    private fun assertTrueText(actual: String, needle: String) {
        if (!actual.contains(needle)) {
            throw AssertionError("expected \"$actual\" to contain \"$needle\"")
        }
    }
}
