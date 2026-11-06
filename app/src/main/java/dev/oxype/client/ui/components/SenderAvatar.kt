package dev.oxype.client.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.oxype.client.data.oxype.AvatarUrls
import dev.oxype.client.ui.chat.avatarColorIndex
import dev.oxype.client.ui.chat.avatarInitial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Colours used for the initial disc.
 *
 * A fixed palette keeps one sender visually recognisable across recompositions; the
 * slot itself is chosen by [avatarColorIndex]. Nothing here depends on the server.
 */
private val AvatarPalette = listOf(
    Color(0xFF4A6FA5),
    Color(0xFF7A5C9E),
    Color(0xFF2F7D6B),
    Color(0xFFA05F3C),
    Color(0xFF8A5A6E),
    Color(0xFF4C6B45)
)

/**
 * A sender avatar: the remote image when one loads, otherwise a coloured initial.
 *
 * The server may not provide an avatar at all, and a URL may fail (offline, 404, a
 * non-image body), so the initial is not a placeholder that is replaced later — it is
 * the normal fallback and is shown immediately while the image is fetched.
 */
@Composable
fun SenderAvatar(
    name: String?,
    avatarUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp
) {
    val initial = avatarInitial(name)
    val color = AvatarPalette[avatarColorIndex(name, AvatarPalette.size)]
    val image = rememberAvatarImage(avatarUrl)

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape)
            )
        } else {
            Text(
                text = initial,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }
    }
}

/**
 * Fetches an avatar and remembers it for as long as [avatarUrl] stays the same.
 *
 * Decoding happens off the main thread; any failure (bad URL, timeout, a body that is
 * not an image) resolves to `null` so the caller keeps its initial fallback.
 */
@Composable
private fun rememberAvatarImage(avatarUrl: String?): ImageBitmap? {
    var image by remember(avatarUrl) { mutableStateOf(AvatarCache.get(avatarUrl)) }
    LaunchedEffect(avatarUrl) {
        if (avatarUrl.isNullOrBlank()) {
            image = null
            return@LaunchedEffect
        }
        val cached = AvatarCache.get(avatarUrl)
        if (cached != null) {
            image = cached
            return@LaunchedEffect
        }
        val loaded = loadAvatar(avatarUrl)
        if (loaded != null) AvatarCache.put(avatarUrl, loaded)
        image = loaded
    }
    return image
}

/**
 * Small in-memory cache so scrolling a long transcript does not re-download avatars.
 *
 * Entry count is deliberately small and every image is decoded down to the display size
 * (see [loadAvatar]): a large cache of full-resolution bitmaps could hold tens of
 * megabytes for a group conversation with large photos. A handful of already-small
 * avatars is all a screenful needs.
 */
private object AvatarCache {
    private const val MAX_ENTRIES = 16
    private val cache = AvatarImageCache<ImageBitmap>(MAX_ENTRIES)

    fun get(url: String?): ImageBitmap? = cache.get(url)

    fun put(url: String, image: ImageBitmap) {
        cache.put(url, image)
    }
}

/**
 * Fetches and decodes one avatar.
 *
 * Three limits keep an untrusted URL from becoming a memory or bandwidth problem:
 *
 * - only HTTP(S) is fetched at all ([AvatarUrls.isLoadable]) — no `file:` read,
 * - the body is read through a bounded stream, so a huge (or endless) response stops at
 *   [AvatarImageLimits.MAX_BYTES] instead of filling memory,
 * - the image is decoded in two passes, measuring first and sampling down to about the
 *   avatar's display size, so a 4000px photo never allocates a 4000px bitmap.
 *
 * No credentials are attached: the URL may point at a third-party host, and an Oxype
 * token must not leak there.
 */
private suspend fun loadAvatar(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    if (!AvatarUrls.isLoadable(url)) return@withContext null
    var connection: HttpURLConnection? = null
    try {
        connection = (URL(url).openConnection() as? HttpURLConnection) ?: return@withContext null
        connection.connectTimeout = AVATAR_TIMEOUT_MILLIS
        connection.readTimeout = AVATAR_TIMEOUT_MILLIS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "image/*")
        // Explicitly no Authorization header: see the note above.

        val declaredLength = connection.contentLengthLong
        if (AvatarImageLimits.exceedsLimit(declaredLength)) return@withContext null

        val bytes = connection.inputStream.use { stream ->
            AvatarImageLimits.readAtMost(stream, AvatarImageLimits.MAX_BYTES)
                ?: return@withContext null
        }
        if (bytes.isEmpty()) return@withContext null

        // Pass 1: bounds only, nothing allocated.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

        // Pass 2: decode already sampled to roughly the display size.
        val options = BitmapFactory.Options().apply {
            inSampleSize = AvatarImageLimits.sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    } catch (e: Exception) {
        null
    } finally {
        connection?.disconnect()
    }
}

private const val AVATAR_TIMEOUT_MILLIS = 5_000
