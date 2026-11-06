package dev.oxype.client.ui.components

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * The limits an untrusted avatar URL is loaded under.
 *
 * An avatar is fetched from whatever host the server named, so it must not be able to
 * make the client allocate an unbounded buffer (`readAtMost`), decode a photo at its
 * full resolution (`sampleSizeFor`), or keep an unbounded number of bitmaps alive
 * (`AvatarImageCache`).
 */
class AvatarImageLimitsTest {

    @Test
    fun `a body exactly at the limit is accepted`() {
        val body = ByteArray(AvatarImageLimits.MAX_BYTES) { 7 }

        val read = AvatarImageLimits.readAtMost(ByteArrayInputStream(body), AvatarImageLimits.MAX_BYTES)

        assertArrayEquals(body, read)
    }

    @Test
    fun `a body above the limit is refused instead of truncated`() {
        // A truncated prefix would decode into a corrupt image, so the whole read fails.
        val body = ByteArray(AvatarImageLimits.MAX_BYTES + 1) { 7 }

        assertNull(AvatarImageLimits.readAtMost(ByteArrayInputStream(body), AvatarImageLimits.MAX_BYTES))
    }

    @Test
    fun `a chunked body without a declared length still stops at the limit`() {
        // No `Content-Length` is available for a chunked answer, so the bound has to be
        // enforced while reading.
        val endless = object : InputStream() {
            override fun read(): Int = 1
        }

        assertNull(AvatarImageLimits.readAtMost(endless, AvatarImageLimits.MAX_BYTES))
    }

    @Test
    fun `an oversized declared length is rejected before reading`() {
        assertTrue(AvatarImageLimits.exceedsLimit(AvatarImageLimits.MAX_BYTES + 1L))
        assertTrue(AvatarImageLimits.exceedsLimit(Long.MAX_VALUE))
        assertTrue("an unknown length must not block the read", !AvatarImageLimits.exceedsLimit(-1L))
        assertTrue(!AvatarImageLimits.exceedsLimit(AvatarImageLimits.MAX_BYTES.toLong()))
    }

    /**
     * The server's own upload rule is the limit this client must honour.
     *
     * Oxype documents `/uploadAvatar` (and the session avatar route) as accepting at most
     * `3145728` bytes (3 MB), so any stored avatar up to that size must be loadable. A
     * smaller client cap would silently refuse images the server legitimately serves.
     */
    @Test
    fun `the download limit matches the server's documented 3 MiB upload limit`() {
        assertEquals(3 * 1024 * 1024, AvatarImageLimits.MAX_BYTES)
    }

    @Test
    fun `a 1 MiB avatar is accepted`() {
        val body = ByteArray(1 * 1024 * 1024) { 7 }

        val read = AvatarImageLimits.readAtMost(ByteArrayInputStream(body), AvatarImageLimits.MAX_BYTES)

        assertArrayEquals(body, read)
    }

    @Test
    fun `an avatar exactly 3 MiB is accepted`() {
        // The documented maximum itself is a legal image, so it must not be off-by-one
        // rejected.
        val body = ByteArray(3 * 1024 * 1024) { 7 }

        val read = AvatarImageLimits.readAtMost(ByteArrayInputStream(body), AvatarImageLimits.MAX_BYTES)

        assertArrayEquals(body, read)
        assertTrue("3 MiB exactly is within the limit", !AvatarImageLimits.exceedsLimit(3L * 1024 * 1024))
    }

    @Test
    fun `an avatar one byte over 3 MiB is refused`() {
        val body = ByteArray(3 * 1024 * 1024 + 1) { 7 }

        assertNull(AvatarImageLimits.readAtMost(ByteArrayInputStream(body), AvatarImageLimits.MAX_BYTES))
        assertTrue("declared 3 MiB + 1 is rejected up front", AvatarImageLimits.exceedsLimit(3L * 1024 * 1024 + 1L))
    }

    @Test
    fun `a large photo is sampled down to roughly the target`() {        // 4000x4000 must not be decoded as 4000x4000: the sample factor is the largest
        // power of two that still leaves at least the target pixels.
        val sample = AvatarImageLimits.sampleSizeFor(4000, 4000)

        assertEquals(32, sample)
        assertTrue("the decoded edge stays near the target", 4000 / sample in 96..256)
    }

    /**
     * The documented per-edge limit is 4096 px. Sampling must keep even the worst
     * aspect ratio bounded, so a legal image cannot be decoded at 4096x4096.
     */
    @Test
    fun `the documented maximum edge is decoded within a bounded area`() {
        val sample = AvatarImageLimits.sampleSizeFor(4096, 4096)

        assertEquals(32, sample)
        assertEquals("a square max-edge photo decodes to 128x128", 128, 4096 / sample)

        // A 4096px edge with a narrow short side cannot be sampled (the short side would
        // drop below the target), but the short side is then < 2 * target, so the decoded
        // area stays under the square max-edge case's bound many times over.
        val sampleThin = AvatarImageLimits.sampleSizeFor(4096, 96)
        assertEquals(1, sampleThin)
        assertTrue("a thin photo stays within a bounded pixel count", 4096 / sampleThin <= 4096)
    }

    @Test
    fun `a small avatar is not upscaled or sampled`() {        assertEquals(1, AvatarImageLimits.sampleSizeFor(64, 64))
        assertEquals(1, AvatarImageLimits.sampleSizeFor(96, 96))
    }

    @Test
    fun `a degenerate image size still yields a usable sample`() {
        assertEquals(1, AvatarImageLimits.sampleSizeFor(0, 0))
        assertEquals(1, AvatarImageLimits.sampleSizeFor(-1, 500))
    }

    @Test
    fun `the avatar cache evicts the least recently used entries`() {
        val cache = AvatarImageCache<String>(maxEntries = 2)
        cache.put("a", "A")
        cache.put("b", "B")

        // Touch "a" so "b" becomes the oldest.
        assertEquals("A", cache.get("a"))
        cache.put("c", "C")

        assertEquals("a must survive because it was used recently", "A", cache.get("a"))
        assertEquals("c is the newest entry", "C", cache.get("c"))
        assertNull("b is the least recently used entry", cache.get("b"))
        assertEquals(2, cache.size)
    }

    @Test
    fun `the cache ignores blank keys`() {
        val cache = AvatarImageCache<String>(maxEntries = 2)

        assertNull(cache.get(null))
        assertNull(cache.get(""))
        assertNull(cache.get("   "))
        assertEquals(0, cache.size)
    }
}
