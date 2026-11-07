package dev.oxype.client.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a picked picture is reduced before it is sent.
 *
 * A phone camera produces pictures far larger than the server accepts, and the reduction
 * happens twice: a power-of-two decode sample so the bitmap never allocates at full size,
 * then an exact scale down to the limit. The arithmetic is here, without Android types,
 * because it is the part that would silently leave a picture just over the edge limit.
 */
class AvatarEncodingTest {

    @Test
    fun `a picture already within the limit is decoded at full size`() {
        assertEquals(1, AvatarEncoding.sampleSize(longEdge = 1024, maxEdge = 4096))
    }

    @Test
    fun `an oversized picture is sampled by a power of two`() {
        // 8000 / 2 = 4000, already under 4096; one more halving would lose detail for
        // nothing.
        assertEquals(2, AvatarEncoding.sampleSize(longEdge = 8000, maxEdge = 4096))
        assertEquals(4, AvatarEncoding.sampleSize(longEdge = 16000, maxEdge = 4096))
        assertEquals(
            "40000 still needs 16 to fall under 4096",
            16,
            AvatarEncoding.sampleSize(longEdge = 40000, maxEdge = 4096)
        )
    }

    @Test
    fun `the sample never goes below one`() {
        assertEquals(1, AvatarEncoding.sampleSize(longEdge = 0, maxEdge = 4096))
        assertEquals(1, AvatarEncoding.sampleSize(longEdge = 4096, maxEdge = 4096))
    }

    @Test
    fun `a square picture at the limit is not scaled`() {
        assertEquals(1f, AvatarEncoding.scaleFor(width = 4096, height = 4096, maxEdge = 4096))
        assertEquals(1f, AvatarEncoding.scaleFor(width = 800, height = 600, maxEdge = 4096))
    }

    @Test
    fun `the longer edge decides the scale`() {
        assertEquals(
            "a tall picture is limited by its height",
            1f,
            AvatarEncoding.scaleFor(width = 1000, height = 4096, maxEdge = 4096)
        )
        assertEquals(0.5f, AvatarEncoding.scaleFor(width = 8192, height = 1000, maxEdge = 4096))
        assertEquals(0.5f, AvatarEncoding.scaleFor(width = 1000, height = 8192, maxEdge = 4096))
    }

    @Test
    fun `a degenerate size yields no scale rather than a divide by zero`() {
        assertEquals(1f, AvatarEncoding.scaleFor(width = 0, height = 0, maxEdge = 4096))
    }

    @Test
    fun `the quality ladder tries lossless first and ends at the server limit`() {
        val ladder = AvatarEncoding.qualityLadder()

        assertTrue("lossless is tried before any lossy pass", ladder.first() > 100)
        assertTrue("later attempts must lose quality, not gain it", ladder.zipWithNext().all { (a, b) -> a >= b })
        assertEquals(70, ladder.last())
    }

    @Test
    fun `the scaled size keeps the aspect ratio and never exceeds the edge`() {
        assertEquals(4096 to 2048, AvatarEncoding.scaledSize(8192, 4096, maxEdge = 4096))
        assertEquals(2048 to 4096, AvatarEncoding.scaledSize(4096, 8192, maxEdge = 4096))
        assertEquals(96 to 96, AvatarEncoding.scaledSize(96, 96, maxEdge = 4096))
    }
}
