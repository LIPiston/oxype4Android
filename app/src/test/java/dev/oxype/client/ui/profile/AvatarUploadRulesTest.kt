package dev.oxype.client.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The local gate in front of an avatar upload.
 *
 * The server validates the same things, but a round trip that ends in a 400 is a worse
 * experience than an immediate answer, and the client already has the bytes in hand. The
 * rules mirror the server's own (`AvatarStorage.decode` upstream): format by magic bytes
 * (never by the claimed MIME type), at most 3 MB after decoding, at most 4096 px per edge.
 */
class AvatarUploadRulesTest {

    // ---- samples ---------------------------------------------------------------

    private fun png(width: Int, height: Int): ByteArray {
        val bytes = ByteArray(33)
        val signature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        signature.copyInto(bytes, 0)
        // Chunk length 13 + "IHDR", then width and height as big-endian 32-bit values.
        bytes[11] = 0x0D
        "IHDR".toByteArray(Charsets.US_ASCII).copyInto(bytes, 12)
        putBe32(bytes, 16, width)
        putBe32(bytes, 20, height)
        return bytes
    }

    private fun jpeg(width: Int, height: Int): ByteArray {
        val bytes = ByteArray(20)
        bytes[0] = 0xFF.toByte()
        bytes[1] = 0xD8.toByte()
        bytes[2] = 0xFF.toByte()
        bytes[3] = 0xC0.toByte()
        // Segment length 0x0011 and 8-bit precision, then height and width.
        bytes[4] = 0x00
        bytes[5] = 0x11
        bytes[6] = 0x08
        putBe16(bytes, 7, height)
        putBe16(bytes, 9, width)
        return bytes
    }

    private fun gif(width: Int, height: Int): ByteArray {
        val bytes = ByteArray(13)
        "GIF89a".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
        putLe16(bytes, 6, width)
        putLe16(bytes, 8, height)
        return bytes
    }

    private fun webpVp8x(width: Int, height: Int): ByteArray {
        val bytes = ByteArray(30)
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
        putLe32(bytes, 4, 22)
        "WEBP".toByteArray(Charsets.US_ASCII).copyInto(bytes, 8)
        "VP8X".toByteArray(Charsets.US_ASCII).copyInto(bytes, 12)
        putLe32(bytes, 16, 10)
        // byte 20 = flags, 21..23 reserved, then 24-bit (width - 1) and (height - 1).
        putLe24(bytes, 24, width - 1)
        putLe24(bytes, 27, height - 1)
        return bytes
    }

    private fun putBe32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun putBe16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 8).toByte()
        target[offset + 1] = value.toByte()
    }

    private fun putLe16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }

    private fun putLe24(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
        target[offset + 2] = (value ushr 16).toByte()
    }

    private fun putLe32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
        target[offset + 2] = (value ushr 16).toByte()
        target[offset + 3] = (value ushr 24).toByte()
    }

    private fun padded(sample: ByteArray, totalBytes: Int): ByteArray {
        val bytes = ByteArray(totalBytes)
        sample.copyInto(bytes, 0)
        return bytes
    }

    // ---- format detection -------------------------------------------------------

    @Test
    fun `the four accepted formats are recognised by their magic bytes`() {
        assertEquals(AvatarFormat.PNG, AvatarUploadRules.detectFormat(png(4, 4)))
        assertEquals(AvatarFormat.JPEG, AvatarUploadRules.detectFormat(jpeg(4, 4)))
        assertEquals(AvatarFormat.GIF, AvatarUploadRules.detectFormat(gif(4, 4)))
        assertEquals(AvatarFormat.WEBP, AvatarUploadRules.detectFormat(webpVp8x(4, 4)))
    }

    @Test
    fun `a claimed extension does not make a document an image`() {
        val html = "<!doctype html><html><body>not a picture</body></html>"
            .toByteArray(Charsets.UTF_8)

        assertNull(AvatarUploadRules.detectFormat(html))
        assertEquals("图片必须是 PNG、JPEG、WebP 或 GIF", AvatarUploadRules.rejection(html))
    }

    @Test
    fun `an empty payload is refused`() {
        assertNull(AvatarUploadRules.detectFormat(ByteArray(0)))
        assertEquals("没有可用的图片数据", AvatarUploadRules.rejection(ByteArray(0)))
    }

    // ---- dimensions -------------------------------------------------------------

    @Test
    fun `dimensions are read from each format header`() {
        assertEquals(1920 to 1080, AvatarUploadRules.dimensions(png(1920, 1080), AvatarFormat.PNG))
        assertEquals(800 to 600, AvatarUploadRules.dimensions(jpeg(800, 600), AvatarFormat.JPEG))
        assertEquals(320 to 240, AvatarUploadRules.dimensions(gif(320, 240), AvatarFormat.GIF))
        assertEquals(4096 to 2048, AvatarUploadRules.dimensions(webpVp8x(4096, 2048), AvatarFormat.WEBP))
    }

    @Test
    fun `a truncated header yields no dimensions rather than a wrong size`() {
        assertNull(AvatarUploadRules.dimensions(png(4, 4).copyOf(12), AvatarFormat.PNG))
        assertNull(AvatarUploadRules.dimensions(gif(4, 4).copyOf(6), AvatarFormat.GIF))
    }

    // ---- limits -----------------------------------------------------------------

    @Test
    fun `exactly three megabytes is accepted and one byte more is refused`() {
        val atLimit = padded(png(512, 512), AvatarUploadRules.MAX_BYTES)
        val overLimit = padded(png(512, 512), AvatarUploadRules.MAX_BYTES + 1)

        assertNull(AvatarUploadRules.rejection(atLimit))
        assertEquals("图片不能超过 3 MB", AvatarUploadRules.rejection(overLimit))
    }

    @Test
    fun `exactly 4096 pixels on an edge is accepted and 4097 is refused`() {
        assertNull(AvatarUploadRules.rejection(png(4096, 4096)))
        assertEquals(
            "图片每条边不能超过 4096 像素",
            AvatarUploadRules.rejection(png(4096, 4097))
        )
    }

    @Test
    fun `a header this client cannot measure is not rejected on size alone`() {
        // The server refuses a picture only when it can read the dimensions; a format
        // whose header is laid out differently must not be blocked by a guess.
        val unmeasurable = padded(jpeg(4, 4), 64).copyOf(64).also {
            // Keep the JPEG signature but remove the frame header marker.
            it[3] = 0x00
        }

        assertNull(AvatarUploadRules.dimensions(unmeasurable, AvatarFormat.JPEG))
        assertNull(AvatarUploadRules.rejection(unmeasurable))
    }

    // ---- data URL ---------------------------------------------------------------

    @Test
    fun `a data url prefix is stripped and a bare payload is kept`() {
        assertEquals("aGVsbG8=", AvatarUploadRules.stripDataUrlPrefix("data:image/png;base64,aGVsbG8="))
        assertEquals("aGVsbG8=", AvatarUploadRules.stripDataUrlPrefix("aGVsbG8="))
        assertEquals(
            "an unterminated prefix is left alone for the server to reject",
            "data:image/png;base64",
            AvatarUploadRules.stripDataUrlPrefix("data:image/png;base64")
        )
        assertEquals(
            "surrounding whitespace is not part of the payload",
            "aGVsbG8=",
            AvatarUploadRules.stripDataUrlPrefix("  aGVsbG8=  ")
        )
    }
}
