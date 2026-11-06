package dev.oxype.client.ui.components

/**
 * Limits applied to an avatar the server pointed at.
 *
 * An avatar URL may name any host (a CDN, or something the account owner controls), so
 * it is treated as untrusted input: the body is bounded while it is read, a large photo
 * is decoded sampled, and only a handful of decoded images are kept. The rules live
 * here, free of Android types, so they are checked without a device.
 */
internal object AvatarImageLimits {

    /**
     * Largest avatar body this client will download.
     *
     * Matches the server's own upload rule, which accepts a decoded image of at most
     * `3145728` bytes (3 MB). A smaller cap here would refuse avatars the server
     * legitimately stores and serves; a larger one would let this client buffer more
     * than the server can ever produce.
     */
    const val MAX_BYTES: Int = 3 * 1024 * 1024

    /** Decoded avatars target this many pixels on the long edge (28dp at xxhdpi ~ 84px). */
    const val TARGET_PX: Int = 96

    /**
     * True when the declared length alone proves the body is too large.
     *
     * `-1` means the server did not declare a length (chunked answers), which says
     * nothing about the size and must not block the read.
     */
    fun exceedsLimit(declaredLength: Long): Boolean = declaredLength > MAX_BYTES.toLong()

    /**
     * Reads at most [limit] bytes, or `null` when the body is larger.
     *
     * The connection's declared length is not always present, so the check has to happen
     * while reading as well. Returning `null` instead of a truncated prefix keeps a
     * partial image from being decoded into a corrupt bitmap.
     */
    fun readAtMost(stream: java.io.InputStream, limit: Int): ByteArray? {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(chunk)
            if (read < 0) break
            total += read
            if (total > limit) return null
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    /**
     * Power-of-two sample factor that brings the decoded image close to [TARGET_PX].
     *
     * `inSampleSize` must be a power of two on older decoders, so the value is rounded
     * down; the result is at worst twice the target, never below it.
     */
    fun sampleSizeFor(width: Int, height: Int, targetPx: Int = TARGET_PX): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= targetPx && height / (sample * 2) >= targetPx) {
            sample *= 2
        }
        return sample
    }
}

/**
 * A tiny least-recently-used cache for decoded avatars.
 *
 * Entry count is deliberately small: a screenful of avatars is all a transcript needs,
 * and keeping every image would let a long scroll hold onto every decoded bitmap.
 * Blank keys are never stored, so an avatar-less member does not occupy a slot.
 */
internal class AvatarImageCache<T>(private val maxEntries: Int) {

    private val entries = object : LinkedHashMap<String, T>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>?): Boolean =
            size > maxEntries
    }

    val size: Int
        @Synchronized get() = entries.size

    @Synchronized
    fun get(key: String?): T? =
        if (key.isNullOrBlank()) null else entries[key]

    @Synchronized
    fun put(key: String, value: T) {
        if (key.isBlank()) return
        entries[key] = value
    }
}
