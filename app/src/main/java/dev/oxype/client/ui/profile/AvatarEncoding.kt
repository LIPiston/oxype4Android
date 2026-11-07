package dev.oxype.client.ui.profile

/**
 * The arithmetic behind shrinking a picked picture before upload.
 *
 * Kept out of the screen because it is the part that decides whether the server will
 * accept the result: a picture that ends up one pixel over an edge, or re-encoded to
 * something larger than 3 MB, fails only after the upload. The rules are pure so they are
 * checked on the JVM.
 */
internal object AvatarEncoding {

    /**
     * Sentinel for "try a lossless PNG first".
     *
     * The rest of [qualityLadder] is JPEG quality, so a value above 100 could never be a
     * JPEG quality and reads unambiguously as the PNG attempt.
     */
    const val LOSSLESS: Int = 101

    /** The smallest quality the ladder will send. Below this the picture is too poor. */
    const val MIN_QUALITY: Int = 70

    /**
     * The power-of-two sample used by the first decode pass.
     *
     * `BitmapFactory` only honours powers of two, so this picks the smallest one that
     * brings the longer edge within [maxEdge]: the bitmap never allocates larger than the
     * server will store, and the exact-scale pass afterwards has little left to do.
     */
    fun sampleSize(longEdge: Int, maxEdge: Int): Int {
        if (longEdge <= 0 || maxEdge <= 0 || longEdge <= maxEdge) return 1
        var sample = 1
        while (longEdge / sample > maxEdge) {
            sample *= 2
        }
        return sample
    }

    /**
     * The exact scale applied after the sampled decode, in `0..1`.
     *
     * One pass of `createScaledBitmap` lands on the limit exactly; relying on the sample
     * alone can leave the longer edge just over it.
     */
    fun scaleFor(width: Int, height: Int, maxEdge: Int): Float {
        val longEdge = maxOf(width, height)
        if (longEdge <= 0 || maxEdge <= 0 || longEdge <= maxEdge) return 1f
        return maxEdge.toFloat() / longEdge.toFloat()
    }

    /** The size to scale [width]x[height] to, keeping the aspect ratio. */
    fun scaledSize(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0 || maxEdge <= 0) return width to height
        val scale = scaleFor(width, height, maxEdge)
        if (scale >= 1f) return width to height
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        return targetWidth to targetHeight
    }

    /**
     * What to try, in order: lossless PNG, then JPEG at 90, 80 and 70.
     *
     * A photograph compresses far smaller as JPEG, while a screenshot or a logo is
     * usually smaller and sharper as PNG, so the lossless attempt comes first and only a
     * result that is still over the server's limit falls back to lossy.
     */
    fun qualityLadder(): List<Int> = listOf(LOSSLESS, 90, 80, MIN_QUALITY)
}
