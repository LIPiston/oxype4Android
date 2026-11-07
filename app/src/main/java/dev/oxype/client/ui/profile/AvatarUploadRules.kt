package dev.oxype.client.ui.profile

/** The four image formats Oxype accepts, identified by their leading bytes. */
enum class AvatarFormat(val mimeType: String, val extension: String) {
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg"),
    WEBP("image/webp", "webp"),
    GIF("image/gif", "gif")
}

/**
 * The local gate in front of an avatar upload.
 *
 * The server checks the same rules (`AvatarStorage.decode` upstream), but a round trip
 * that ends in a 400 is a worse answer than an immediate one, and the bytes are already
 * in hand. The format is decided by magic bytes, never by the claimed MIME type or the
 * file name: both are attacker-controlled, and a `.png` that is really an HTML document
 * would otherwise be sent on and served back from the server's own origin.
 *
 * Kept free of Android types so the rules are checked on the JVM without a device. The
 * header walkers mirror the server's, including the part that matters most: a layout
 * this client cannot measure is *not* a rejection, because the server treats it as
 * acceptable too.
 */
object AvatarUploadRules {

    /** Largest accepted image after decoding; matches the server's limit. */
    const val MAX_BYTES: Int = 3 * 1024 * 1024

    /** Longest accepted edge in pixels; matches the server's limit. */
    const val MAX_EDGE: Int = 4096

    /** The first byte offset of each format is checked without allocating anything. */
    fun detectFormat(bytes: ByteArray): AvatarFormat? {
        if (bytes.isEmpty()) return null
        fun at(index: Int): Int = if (index < bytes.size) bytes[index].toInt() and 0xFF else -1

        if (at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47) {
            return AvatarFormat.PNG
        }
        if (at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF) {
            return AvatarFormat.JPEG
        }
        if (at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x38) {
            return AvatarFormat.GIF
        }
        // RIFF....WEBP: four bytes of container tag, a size, then the WEBP signature.
        if (at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
            at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50
        ) {
            return AvatarFormat.WEBP
        }
        return null
    }

    /**
     * Pixel dimensions from the header, or `null` when this client cannot read them.
     *
     * Only used for the edge limit, so an unreadable header is "unknown", not "invalid".
     */
    fun dimensions(bytes: ByteArray, format: AvatarFormat): Pair<Int, Int>? {
        fun at(index: Int): Int = if (index < bytes.size) bytes[index].toInt() and 0xFF else -1
        fun be32(index: Int): Int? {
            if (index + 3 >= bytes.size) return null
            return (at(index) shl 24) or (at(index + 1) shl 16) or (at(index + 2) shl 8) or at(index + 3)
        }
        fun le16(index: Int): Int? {
            if (index + 1 >= bytes.size) return null
            return at(index) or (at(index + 1) shl 8)
        }

        return when (format) {
            AvatarFormat.PNG ->
                if (at(12) == 0x49 && at(13) == 0x48 && at(14) == 0x44 && at(15) == 0x52) {
                    val width = be32(16)
                    val height = be32(20)
                    if (width != null && height != null) width to height else null
                } else {
                    null
                }

            AvatarFormat.GIF -> {
                val width = le16(6)
                val height = le16(8)
                if (width != null && height != null) width to height else null
            }

            AvatarFormat.WEBP -> webpDimensions(bytes)
            AvatarFormat.JPEG -> jpegDimensions(bytes)
        }
    }

    /**
     * The reason this payload cannot be uploaded, or `null` when it can.
     *
     * Checked in the order the server checks: there must be bytes, a known format, a
     * size within the limit, and — when the header can be measured — edges within the
     * limit.
     */
    fun rejection(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return "没有可用的图片数据"
        val format = detectFormat(bytes)
            ?: return "图片必须是 PNG、JPEG、WebP 或 GIF"
        if (bytes.size > MAX_BYTES) return "图片不能超过 ${MAX_BYTES / (1024 * 1024)} MB"
        val edges = dimensions(bytes, format)
        if (edges != null && (edges.first > MAX_EDGE || edges.second > MAX_EDGE)) {
            return "图片每条边不能超过 $MAX_EDGE 像素"
        }
        return null
    }

    /** WebP stores its size in one of three sub-formats after the `WEBP` tag. */
    private fun webpDimensions(bytes: ByteArray): Pair<Int, Int>? {
        fun at(index: Int): Int = if (index < bytes.size) bytes[index].toInt() and 0xFF else -1
        fun le16(index: Int): Int? {
            if (index + 1 >= bytes.size) return null
            return at(index) or (at(index + 1) shl 8)
        }
        fun le24(index: Int): Int? {
            if (index + 2 >= bytes.size) return null
            return at(index) or (at(index + 1) shl 8) or (at(index + 2) shl 16)
        }

        return when {
            // 'VP8 ' lossy: a three-byte frame tag, then the start code, then the size.
            at(12) == 0x56 && at(13) == 0x50 && at(14) == 0x38 && at(15) == 0x20 -> {
                val width = le16(26)
                val height = le16(28)
                if (width != null && height != null) {
                    (width and 0x3FFF) to (height and 0x3FFF)
                } else {
                    null
                }
            }
            // 'VP8L' lossless: 14 bits of width-1 then 14 bits of height-1.
            at(12) == 0x56 && at(13) == 0x50 && at(14) == 0x38 && at(15) == 0x4C -> {
                var bits = 0L
                for (index in 0 until 4) {
                    bits = bits or ((at(21 + index).toLong() and 0xFF) shl (8 * index))
                }
                (((bits shr 1) and 0x3FFF).toInt() + 1) to
                    (((bits shr 15) and 0x3FFF).toInt() + 1)
            }
            // 'VP8X' extended: canvas width-1 and height-1 as 24-bit values.
            at(12) == 0x56 && at(13) == 0x50 && at(14) == 0x38 && at(15) == 0x58 -> {
                val width = le24(24)
                val height = le24(27)
                if (width != null && height != null) (width + 1) to (height + 1) else null
            }
            else -> null
        }
    }

    /** JPEG walks its segment chain to the frame header that carries the size. */
    private fun jpegDimensions(bytes: ByteArray): Pair<Int, Int>? {
        fun at(index: Int): Int = if (index < bytes.size) bytes[index].toInt() and 0xFF else -1

        var offset = 2 // skip the leading FF D8
        while (offset + 9 < bytes.size) {
            if (at(offset) != 0xFF) {
                offset++
                continue
            }
            val marker = at(offset + 1)
            // Standalone markers carry no length field.
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
                offset += 2
                continue
            }
            val length = (at(offset + 2) shl 8) or at(offset + 3)
            if (length < 2) return null
            // SOF0..SOF15, excluding the four markers that are not frame headers.
            val isFrameHeader = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 &&
                marker != 0xCC
            if (isFrameHeader) {
                val height = (at(offset + 5) shl 8) or at(offset + 6)
                val width = (at(offset + 7) shl 8) or at(offset + 8)
                return width to height
            }
            offset += 2 + length
        }
        return null
    }
    /**
     * Removes an optional `data:<mime>;base64,` prefix from an encoded image.
     *
     * A prefix without a comma cannot be split, so the value is returned unchanged and the
     * server's own "not valid base64" answer reaches the user.
     */
    fun stripDataUrlPrefix(value: String): String {
        val trimmed = value.trim()
        if (!trimmed.startsWith("data:")) return trimmed
        val comma = trimmed.indexOf(',')
        return if (comma == -1) trimmed else trimmed.substring(comma + 1)
    }
}
