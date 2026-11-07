package dev.oxype.client.ui.profile

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * The result of picking a picture: either the bytes to upload, or why it cannot go.
 *
 * The screen shows [rejection] verbatim, so the wording lives next to the rule that
 * produced it rather than in the composable.
 */
internal sealed interface PickedAvatar {
    data class Ready(val bytes: ByteArray) : PickedAvatar
    data class Rejected(val reason: String) : PickedAvatar
}

/**
 * The platform half of the pipeline, kept behind an interface so the decision-making part
 * ([encodeForUpload]) runs on the JVM without a device.
 *
 * [B] is `Bitmap` in production and a stand-in in tests; nothing here assumes Android.
 */
internal interface AvatarImagePipeline<B> {
    /** Header dimensions in pixels, or `null` when this client cannot read the layout. */
    fun headerSize(raw: ByteArray): Pair<Int, Int>?

    /** Decodes [raw] sampled by a power of two, or `null` when the bytes are unreadable. */
    fun decode(raw: ByteArray, sampleSize: Int): B?

    fun size(image: B): Pair<Int, Int>

    /** Returns [image] itself when it already fits. */
    fun scale(image: B, width: Int, height: Int): B

    /** `null` when the encoder refuses the image. */
    fun encode(image: B, lossless: Boolean, quality: Int): ByteArray?

    fun recycle(image: B)
}

/**
 * Reads, shrinks and re-encodes a picture chosen from the system photo picker.
 *
 * Everything heavy runs on [Dispatchers.Default]: a modern camera photo is tens of
 * megabytes decoded, and doing that on the main thread would stall the frame the picker
 * returns to. The pipeline is:
 *
 * 1. read the stream with a hard byte cap, so a hostile or broken provider cannot fill
 *    memory ([AvatarUploadRules.MAX_BYTES] is the server's own limit),
 * 2. check the magic bytes and the header dimensions before decoding,
 * 3. decode sampled, scale to the edge limit, and re-encode — lossless PNG first, then
 *    JPEG at falling quality — until the result fits.
 *
 * The picker itself hands back a content URI; `openInputStream` may fail on a provider
 * that has been revoked between the pick and the read, which is reported as a rejection
 * rather than an exception.
 */
internal object AvatarPicker {

    /** The most this pipeline will do to one picture before giving up. */
    private const val MAX_ATTEMPTS = 4

    private object AndroidPipeline : AvatarImagePipeline<Bitmap> {
        override fun headerSize(raw: ByteArray): Pair<Int, Int>? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            return if (width > 0 && height > 0) width to height else null
        }

        override fun decode(raw: ByteArray, sampleSize: Int): Bitmap? =
            BitmapFactory.decodeByteArray(
                raw,
                0,
                raw.size,
                BitmapFactory.Options().apply { inSampleSize = sampleSize }
            )

        override fun size(image: Bitmap): Pair<Int, Int> = image.width to image.height

        override fun scale(image: Bitmap, width: Int, height: Int): Bitmap =
            if (width == image.width && height == image.height) {
                image
            } else {
                Bitmap.createScaledBitmap(image, width, height, true)
            }

        override fun encode(image: Bitmap, lossless: Boolean, quality: Int): ByteArray? {
            val buffer = ByteArrayOutputStream()
            val compressed = if (lossless) {
                image.compress(Bitmap.CompressFormat.PNG, 100, buffer)
            } else {
                image.compress(Bitmap.CompressFormat.JPEG, quality, buffer)
            }
            return if (compressed) buffer.toByteArray() else null
        }

        override fun recycle(image: Bitmap) {
            if (!image.isRecycled) image.recycle()
        }
    }

    suspend fun read(resolver: ContentResolver, uri: Uri): PickedAvatar =
        withContext(Dispatchers.Default) {
            val raw = try {
                resolver.openInputStream(uri)?.use { input ->
                    when (val bounded = input.readAtMost(AvatarUploadRules.MAX_BYTES)) {
                        is BoundedRead.Complete -> bounded.bytes
                        BoundedRead.TooLong -> return@withContext PickedAvatar.Rejected(
                            tooLargeMessage()
                        )
                    }
                }
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            } ?: return@withContext PickedAvatar.Rejected(unreadableMessage())
            if (raw.isEmpty()) return@withContext PickedAvatar.Rejected(unreadableMessage())

            AvatarUploadRules.detectFormat(raw)
                ?: return@withContext PickedAvatar.Rejected("图片必须是 PNG、JPEG、WebP 或 GIF")

            encodeForUpload(raw, AndroidPipeline)
        }

    /**
     * Turns validated bytes into something the server accepts.
     *
     * Every allocation here can fail on a low-memory device — a 4096x4096 bitmap is about
     * 64 MiB before the encoder's own buffers — so each step converts an allocation
     * failure into a readable rejection instead of letting an [OutOfMemoryError] escape
     * the coroutine and take the app down. Both bitmaps are recycled on every path,
     * including the early returns.
     */
    internal fun <B> encodeForUpload(
        raw: ByteArray,
        pipeline: AvatarImagePipeline<B>,
        maxAttempts: Int = MAX_ATTEMPTS
    ): PickedAvatar {
        val bounds = attempt { pipeline.headerSize(raw) }
            ?: return PickedAvatar.Rejected(unreadableMessage())
        val sample = AvatarEncoding.sampleSize(
            longEdge = maxOf(bounds.first, bounds.second),
            maxEdge = AvatarUploadRules.MAX_EDGE
        )
        val decoded = attempt { pipeline.decode(raw, sample) }
            ?: return PickedAvatar.Rejected("这张图片过大，无法处理")

        var scaled: B = decoded
        try {
            val (width, height) = pipeline.size(decoded)
            val (targetWidth, targetHeight) = AvatarEncoding.scaledSize(
                width = width,
                height = height,
                maxEdge = AvatarUploadRules.MAX_EDGE
            )
            if (targetWidth != width || targetHeight != height) {
                scaled = attempt { pipeline.scale(decoded, targetWidth, targetHeight) }
                    ?: return PickedAvatar.Rejected("这张图片过大，无法处理")
            }

            val encoded = attempt { encodeWithinLimit(scaled, pipeline, maxAttempts) }
                ?: return PickedAvatar.Rejected(
                    "缩小后仍然超过 ${AvatarUploadRules.MAX_BYTES / (1024 * 1024)} MB，请换一张"
                )
            val reason = AvatarUploadRules.rejection(encoded)
            return if (reason != null) PickedAvatar.Rejected(reason) else PickedAvatar.Ready(encoded)
        } finally {
            if (scaled !== decoded) pipeline.recycle(decoded)
            pipeline.recycle(scaled)
        }
    }

    /**
     * Encodes [bitmap] into something the server will accept.
     *
     * PNG is tried first and kept when it fits, which preserves transparency and avoids
     * recompression artefacts on screenshots and flat art. A photograph usually exceeds
     * the limit as PNG, so the ladder falls back to JPEG at falling quality; the first
     * result within the limit wins. A GIF or WebP source is re-encoded too — the display
     * side crops and scales anyway, and a still frame is what an avatar needs.
     */
    private fun <B> encodeWithinLimit(
        bitmap: B,
        pipeline: AvatarImagePipeline<B>,
        maxAttempts: Int
    ): ByteArray? {
        for (quality in AvatarEncoding.qualityLadder().take(maxAttempts)) {
            val lossless = quality == AvatarEncoding.LOSSLESS
            val bytes = pipeline.encode(bitmap, lossless, quality) ?: continue
            if (bytes.isNotEmpty() && bytes.size <= AvatarUploadRules.MAX_BYTES) return bytes
        }
        return null
    }

    private fun tooLargeMessage(): String =
        "图片不能超过 ${AvatarUploadRules.MAX_BYTES / (1024 * 1024)} MB"

    private fun unreadableMessage(): String = "无法读取这张图片，请换一张试试"
}

/**
 * Runs [block], turning an allocation failure into `null` so the caller can explain it.
 *
 * Deliberately narrow: this is the boundary of the image pipeline, where the only
 * alternative is a crash.
 */
internal inline fun <T> attempt(block: () -> T): T? =
    try {
        block()
    } catch (e: OutOfMemoryError) {
        null
    } catch (e: Exception) {
        null
    }
