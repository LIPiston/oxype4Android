package dev.oxype.client.ui.profile

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * The cap in front of the decode.
 *
 * The picker is allowed to hand back a stream of any length, and the length is only
 * meaningful *after* the read. `readBytes()` would hold the whole thing first, so a
 * provider ten times over the limit costs ten times the memory before the client can
 * say no. These tests pin that the reader stops at the first byte over the cap.
 */
class BoundedReadTest {

    @Test
    fun `a stream under the cap is read whole`() {
        val bytes = ByteArray(100) { it.toByte() }

        val result = ByteArrayInputStream(bytes).readAtMost(100)

        assertTrue(result is BoundedRead.Complete)
        assertArrayEquals(bytes, (result as BoundedRead.Complete).bytes)
    }

    @Test
    fun `a stream of exactly the cap is accepted`() {
        val bytes = ByteArray(64) { 7 }

        val result = ByteArrayInputStream(bytes).readAtMost(64)

        assertTrue("the limit itself is allowed", result is BoundedRead.Complete)
        assertEquals(64, (result as BoundedRead.Complete).bytes.size)
    }

    @Test
    fun `the first byte over the cap abandons the read`() {
        val bytes = ByteArray(65) { 7 }

        assertEquals(BoundedRead.TooLong, ByteArrayInputStream(bytes).readAtMost(64))
    }

    @Test
    fun `a huge stream is not read to the end`() {
        val source = CountingStream(total = 8L * 1024 * 1024)
        val cap = 64 * 1024

        val result = source.readAtMost(cap)

        assertEquals(BoundedRead.TooLong, result)
        assertTrue(
            "the reader must stop soon after the cap, not consume the whole stream",
            source.consumed <= cap + READ_CHUNK_SLACK
        )
    }

    @Test
    fun `an empty stream is an empty complete read`() {
        val result = ByteArrayInputStream(ByteArray(0)).readAtMost(16)

        assertTrue(result is BoundedRead.Complete)
        assertEquals(0, (result as BoundedRead.Complete).bytes.size)
    }

    /** Counts how much was actually pulled from the underlying stream. */
    private class CountingStream(private val total: Long) : InputStream() {
        var consumed: Long = 0
            private set

        override fun read(): Int {
            if (consumed >= total) return -1
            consumed++
            return 0
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (consumed >= total) return -1
            val read = minOf(length.toLong(), total - consumed).toInt()
            consumed += read
            return read
        }
    }

    private companion object {
        /** One chunk past the cap is the most the reader should ever ask for. */
        const val READ_CHUNK_SLACK = 8L * 1024
    }
}
