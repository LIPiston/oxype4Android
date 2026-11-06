package dev.oxype.client.data.oxype

import java.net.URI

/**
 * Turns the avatar field of a member record into a URL this client may load.
 *
 * Oxype does not specify the field's shape: deployments have been seen to answer an
 * absolute `https://…` URL, a server-relative `/static/avatars/7.png`, and a bare
 * `avatars/7.png`. Only HTTP(S) is loadable — a `file:`, `data:` or `javascript:` value
 * must not turn into a local read or a script — so everything else resolves to `null`
 * and the UI keeps its coloured initial.
 *
 * The function is pure on purpose: the rules are the interesting part and they are
 * checked without Android or a network.
 */
internal object AvatarUrls {

    /**
     * Resolves [raw] against the server [baseUrl].
     *
     * - absolute HTTP(S) URLs are kept as they are (they may point at a separate CDN),
     * - a protocol-relative `//host/path` takes the scheme of [baseUrl],
     * - a root-relative `/path` is joined to [baseUrl]'s scheme and authority,
     * - anything else is treated as a path under [baseUrl].
     *
     * `null` means "no usable source": blank, malformed, or a scheme other than HTTP(S).
     */
    fun resolve(raw: String?, baseUrl: String): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val base = baseUrl.trim().trimEnd('/')
        val candidate = when {
            trimmed.startsWith("//") -> {
                val scheme = schemeOf(base) ?: return null
                "$scheme:$trimmed"
            }

            hasScheme(trimmed) -> {
                // Only HTTP(S) is fetchable; everything else is deliberately dropped.
                val scheme = schemeOf(trimmed) ?: return null
                if (!isHttpScheme(scheme)) return null
                trimmed
            }

            trimmed.startsWith("/") -> {
                val origin = originOf(base) ?: return null
                origin + trimmed
            }

            else -> "$base/$trimmed"
        }
        return if (isLoadable(candidate)) candidate else null
    }

    /** True when [url] is an absolute HTTP(S) URL with a host. */
    fun isLoadable(url: String?): Boolean {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        val scheme = schemeOf(trimmed) ?: return false
        if (!isHttpScheme(scheme)) return false
        return try {
            val uri = URI(trimmed)
            !uri.host.isNullOrBlank()
        } catch (e: Exception) {
            false
        }
    }

    private fun isHttpScheme(scheme: String): Boolean =
        scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)

    private fun hasScheme(value: String): Boolean = SCHEME.containsMatchIn(value)

    private fun schemeOf(value: String): String? =
        SCHEME.find(value)?.groupValues?.getOrNull(1)

    /** `scheme://authority` of [baseUrl], or `null` when it is not an absolute URL. */
    private fun originOf(baseUrl: String): String? {
        if (!isLoadable(baseUrl)) return null
        val uri = try {
            URI(baseUrl)
        } catch (e: Exception) {
            return null
        }
        val authority = uri.rawAuthority ?: return null
        return "${uri.scheme}://$authority"
    }

    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*):")
}
