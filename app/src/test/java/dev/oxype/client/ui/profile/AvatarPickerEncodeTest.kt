package dev.oxype.client.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shrinking pass, without Android.
 *
 * Two things can go wrong here and neither should reach the user as a crash: the encoder
 * can still be over the limit after every quality step, and any allocation can fail on a
 * low-memory device. Both are answers, not exceptions.
 */
class AvatarPickerEncodeTest {

    /** A stand-in "bitmap": only a size, plus counters for the recycling check. */
    private class FakeImage(val width: Int, val height: Int) {
        var recycled = false
    }

    private companion object {
        /** The shortest byte string the magic-byte check accepts as a PNG. */
        fun validPng(size: Int = 64): ByteArray = ByteArray(size).also { bytes ->
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
                .copyInto(bytes, 0)
            bytes[12] = 0x49
            bytes[13] = 0x48
            bytes[14] = 0x44
            bytes[15] = 0x52
            bytes[19] = 8
            bytes[23] = 8
        }
    }

    private class FakePipeline(
        private val header: Pair<Int, Int>? = 96 to 96,
        private val decoded: FakeImage? = FakeImage(96, 96),
        private val failDecode: Boolean = false,
        private val failScale: Boolean = false,
        private val failEncode: Boolean = false,
        private val encodedSize: (Int) -> Int = { 1024 }
    ) : AvatarImagePipeline<FakeImage> {
        val recycled = mutableListOf<FakeImage>()
        var scales = 0

        override fun headerSize(raw: ByteArray): Pair<Int, Int>? = header

        override fun decode(raw: ByteArray, sampleSize: Int): FakeImage? {
            if (failDecode) throw OutOfMemoryError("no room to decode")
            return decoded
        }

        override fun size(image: FakeImage): Pair<Int, Int> = image.width to image.height

        override fun scale(image: FakeImage, width: Int, height: Int): FakeImage {
            if (failScale) throw OutOfMemoryError("no room to scale")
            scales++
            return FakeImage(width, height)
        }

        override fun encode(image: FakeImage, lossless: Boolean, quality: Int): ByteArray? {
            if (failEncode) throw OutOfMemoryError("no room to encode")
            val size = encodedSize(if (lossless) AvatarEncoding.LOSSLESS else quality)
            if (size < 0) return null
            return validPng(size)
        }

        override fun recycle(image: FakeImage) {
            image.recycled = true
            recycled += image
        }
    }

    private fun validPng(size: Int = 64): ByteArray = AvatarPickerEncodeTest.validPng(size)

    @Test
    fun `an image within every limit is encoded losslessly`() {
        val pipeline = FakePipeline()

        val result = AvatarPicker.encodeForUpload(validPng(), pipeline)

        assertTrue(result is PickedAvatar.Ready)
        assertEquals(1024, (result as PickedAvatar.Ready).bytes.size)
    }

    @Test
    fun `an oversized long edge is scaled before encoding`() {
        // A header this client can measure, and a decode that still came back over the
        // edge limit — the exact-scale pass is what lands it on 4096.
        val pipeline = FakePipeline(
            header = 5000 to 2500,
            decoded = FakeImage(5000, 2500)
        )

        val result = AvatarPicker.encodeForUpload(validPng(), pipeline)

        assertTrue(result is PickedAvatar.Ready)
        assertEquals("a second pass lands exactly on the edge limit", 1, pipeline.scales)
        assertTrue("both bitmaps are released", pipeline.recycled.size >= 2)
    }

    @Test
    fun `an unreadable header is a readable rejection`() {
        val pipeline = FakePipeline(header = null)

        val result = AvatarPicker.encodeForUpload(validPng(), pipeline)

        assertEquals(
            PickedAvatar.Rejected("无法读取这张图片，请换一张试试"),
            result
        )
    }

    @Test
    fun `an allocation failure while decoding is a readable rejection`() {
        val pipeline = FakePipeline(failDecode = true)

        val result = AvatarPicker.encodeForUpload(validPng(), pipeline)

        assertEquals(PickedAvatar.Rejected("这张图片过大，无法处理"), result)
    }

    @Test
    fun `an allocation failure while scaling is a readable rejection and recycles the decode`() {
        val decoded = FakeImage(8192, 4096)
        val pipeline = FakePipeline(header = 8192 to 4096, decoded = decoded, failScale = true)

        val result = AvatarPicker.encodeForUpload(validPng(), pipeline)

        assertEquals(PickedAvatar.Rejected("这张图片过大，无法处理"), result)
        assertTrue("the decoded bitmap is released on the failure path", decoded.recycled)
    }

    @Test
    fun `an allocation failure while encoding is a readable rejection and recycles both bitmaps`() {
        val decoded = FakeImage(8192, 4096)
        val pipeline = FakePipeline(header = 8192 to 4096, decoded = decoded, failEncode = true)

        val result = AvatarPicker.encodeForUpload(validPng(), pipeline)

        assertEquals(
            PickedAvatar.Rejected("缩小后仍然超过 3 MB，请换一张"),
            result
        )
        assertTrue(decoded.recycled)
        assertTrue(pipeline.recycled.size >= 2)
    }

    @Test
    fun `an image still over the limit after every step is rejected with the size reason`() {
        val pipeline = FakePipeline(
            encodedSize = { AvatarUploadRules.MAX_BYTES + 1 }
        )

        val result = AvatarPicker.encodeForUpload(validPng(), pipeline)

        assertEquals(
            PickedAvatar.Rejected("缩小后仍然超过 3 MB，请换一张"),
            result
        )
    }

    @Test
    fun `an image that only fits at a lower quality is re-encoded until it does`() {
        val pipeline = FakePipeline(
            encodedSize = { quality ->
                if (quality >= 90) AvatarUploadRules.MAX_BYTES + 1 else 2048
            }
        )

        val result = AvatarPicker.encodeForUpload(validPng(), pipeline)

        assertTrue(result is PickedAvatar.Ready)
        assertEquals(2048, (result as PickedAvatar.Ready).bytes.size)
    }

    @Test
    fun `a successful run recycles both the decode and the scaled copy`() {
        val decoded = FakeImage(8192, 4096)
        val pipeline = FakePipeline(header = 8192 to 4096, decoded = decoded)

        AvatarPicker.encodeForUpload(validPng(), pipeline)

        assertTrue("the sampled bitmap is released", decoded.recycled)
        assertTrue("the exact-scale copy is released too", pipeline.recycled.size >= 2)
    }
}
