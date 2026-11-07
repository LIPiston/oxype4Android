package dev.oxype.client.ui.profile

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** What a capped stream read produced. */
internal sealed interface BoundedRead {
    /** Everything the stream had, at most `limit` bytes. */
    data class Complete(val bytes: ByteArray) : BoundedRead

    /** The stream went past the cap; it was abandoned at the first byte over it. */
    data object TooLong : BoundedRead
}

/** How much is read per call. Small enough that the cap is honoured promptly. */
private const val READ_CHUNK = 8 * 1024

/** The initial buffer never preallocates the whole cap for a tiny file. */
private const val READ_INITIAL = 16 * 1024

/**
 * Reads [input] until EOF, or until more than [limit] bytes would be taken.
 *
 * `InputStream.readBytes()` buffers the whole stream before the caller can look at the
 * length, which is the wrong order for a third-party provider: a broken or hostile picker
 * can hand over a stream far larger than the advertised picture and the heap pays for it
 * before the size check ever runs. This stops at the first byte past the cap.
 *
 * The caller owns the stream and is expected to close it (`use`).
 */
internal fun InputStream.readAtMost(limit: Int): BoundedRead {
    require(limit >= 0) { "limit must not be negative" }
    val out = ByteArrayOutputStream(minOf(limit, READ_INITIAL))
    val chunk = ByteArray(READ_CHUNK)
    while (true) {
        val read = read(chunk)
        if (read == -1) return BoundedRead.Complete(out.toByteArray())
        if (out.size().toLong() + read > limit) return BoundedRead.TooLong
        out.write(chunk, 0, read)
    }
}
