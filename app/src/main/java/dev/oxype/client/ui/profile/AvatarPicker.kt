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

    suspend fun read(resolver: ContentResolver, uri: Uri): PickedAvatar =
        withContext(Dispatchers.Default) {
            val raw = try {
                resolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Exception) {
                null
            } ?: return@withContext PickedAvatar.Rejected("无法读取这张图片，请换一张试试")
            if (raw.isEmpty()) return@withContext PickedAvatar.Rejected("无法读取这张图片，请换一张试试")
            if (raw.size > AvatarUploadRules.MAX_BYTES) {
                return@withContext PickedAvatar.Rejected(
                    "图片不能超过 ${AvatarUploadRules.MAX_BYTES / (1024 * 1024)} MB"
                )
            }

            AvatarUploadRules.detectFormat(raw)
                ?: return@withContext PickedAvatar.Rejected("图片必须是 PNG、JPEG、WebP 或 GIF")

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return@withContext PickedAvatar.Rejected("无法读取这张图片，请换一张试试")
            }

            val sample = AvatarEncoding.sampleSize(
                longEdge = maxOf(bounds.outWidth, bounds.outHeight),
                maxEdge = AvatarUploadRules.MAX_EDGE
            )
            val decoded = try {
                BitmapFactory.decodeByteArray(
                    raw,
                    0,
                    raw.size,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )
            } catch (e: Exception) {
                // A very large allocation can fail on a low-memory device; that is a
                // rejection with an explanation, not a crash.
                null
            } catch (e: OutOfMemoryError) {
                null
            } ?: return@withContext PickedAvatar.Rejected("这张图片过大，无法处理")

            val scaled = scaleToLimit(decoded)
            val encoded = encodeWithinLimit(scaled)
            if (scaled !== decoded) decoded.recycle()
            scaled.recycle()
            encoded ?: return@withContext PickedAvatar.Rejected(
                "缩小后仍然超过 ${AvatarUploadRules.MAX_BYTES / (1024 * 1024)} MB，请换一张"
            )

            val reason = AvatarUploadRules.rejection(encoded)
            if (reason != null) PickedAvatar.Rejected(reason) else PickedAvatar.Ready(encoded)
        }

    /** Scales down to the edge limit, returning the original when it already fits. */
    private fun scaleToLimit(source: Bitmap): Bitmap {
        val (width, height) = AvatarEncoding.scaledSize(
            width = source.width,
            height = source.height,
            maxEdge = AvatarUploadRules.MAX_EDGE
        )
        if (width == source.width && height == source.height) return source
        return Bitmap.createScaledBitmap(source, width, height, true)
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
    private fun encodeWithinLimit(bitmap: Bitmap): ByteArray? {
        for (quality in AvatarEncoding.qualityLadder().take(MAX_ATTEMPTS)) {
            val buffer = ByteArrayOutputStream()
            val compressed = if (quality == AvatarEncoding.LOSSLESS) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, buffer)
            } else {
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, buffer)
            }
            if (!compressed) continue
            val bytes = buffer.toByteArray()
            if (bytes.isNotEmpty() && bytes.size <= AvatarUploadRules.MAX_BYTES) return bytes
        }
        return null
    }
}
