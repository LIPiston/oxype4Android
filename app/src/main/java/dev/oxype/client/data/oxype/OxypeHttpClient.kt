package dev.oxype.client.data.oxype

import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLEncoder

/**
 * The single place that knows how to talk HTTP to an Oxype server.
 *
 * Responsibilities kept here, and nowhere else:
 * - base URL normalisation and path joining,
 * - credentials as a JSON body field for POST and as query parameters for GET,
 * - Kalmia `data` envelope unwrapping (including bare arrays),
 * - mapping transport failures and HTTP status codes onto [OxypeException].
 *
 * It deliberately knows nothing about screens, caching, or message ordering.
 */
class OxypeHttpClient(
    private val transport: HttpTransport = HttpUrlConnectionTransport(),
    baseUrl: String = DEFAULT_BASE_URL
) {
    @Volatile
    var baseUrl: String = normalizeBaseUrl(baseUrl)
        private set

    fun updateBaseUrl(url: String) {
        baseUrl = normalizeBaseUrl(url)
    }

    /** POSTs a JSON body; Oxype authenticates these with `userid` + `token` in the body. */
    suspend fun postJson(
        path: String,
        body: JSONObject,
        credentials: AuthCredentials? = null
    ): Any? {
        val payload = JSONObject()
        body.keys().forEach { key -> payload.put(key, body.opt(key)) }
        if (credentials != null) {
            // The server accepts `userId` as an alias, and has historically been sent
            // both; `userid` is what the canonical route reads first.
            if (!OxypeJson.hasValue(payload, "userid") && !OxypeJson.hasValue(payload, "userId")) {
                payload.put("userid", credentials.userId)
            }
            if (!OxypeJson.hasValue(payload, "token")) {
                payload.put("token", credentials.token)
            }
        }
        val response = transport.execute(
            HttpRequest(
                method = "POST",
                url = joinUrl(path),
                body = payload.toString()
            )
        )
        return decode(response)
    }

    /** GETs a route; Oxype reads this family's credentials from the query string. */
    suspend fun get(
        path: String,
        query: Map<String, String> = emptyMap(),
        credentials: AuthCredentials? = null
    ): Any? {
        val params = LinkedHashMap<String, String>()
        if (credentials != null) {
            params["userid"] = credentials.userId.toString()
            params["token"] = credentials.token
        }
        params.putAll(query)
        val response = transport.execute(
            HttpRequest(
                method = "GET",
                url = joinUrl(path, params)
            )
        )
        return decode(response)
    }

    fun normalizeBaseUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return DEFAULT_BASE_URL
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
        return withScheme.trimEnd('/')
    }

    private fun joinUrl(path: String, query: Map<String, String> = emptyMap()): String {
        val suffix = if (path.startsWith("/")) path else "/$path"
        if (query.isEmpty()) return baseUrl + suffix
        val encoded = query.entries.joinToString("&") { (name, value) ->
            "${encode(name)}=${encode(value)}"
        }
        return "${baseUrl}${suffix}?$encoded"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /**
     * Turns an HTTP exchange into either the unwrapped payload or a typed error.
     *
     * A non-2xx answer is classified by its status *first*, and the body is only used
     * to enrich the message. Proxies and misconfigured servers answer errors with an
     * HTML page or an empty body, and letting the JSON parser throw first would replace
     * "HTTP 500" or "redirect to https" with a generic parse error — and would also drop
     * the status code. An empty body is legitimately possible on success too; it decodes
     * to `null` rather than failing.
     */
    private fun decode(response: HttpResponse): Any? {
        if (response.status !in 200..299) {
            // Lenient on purpose: a non-JSON error body must not mask the status.
            val parsed = parseLenient(response.body)
            val message = OxypeJson.errorMessage(parsed as? JSONObject)
                ?: defaultMessageFor(response.status)
            throw OxypeException(
                kind = kindFor(response.status),
                message = message,
                httpStatus = response.status
            )
        }
        val parsed = try {
            parseOrNull(response.body)
        } catch (e: OxypeException) {
            // Keep the protocol failure but attach the status that produced it, so the
            // caller can tell "a 200 we cannot read" from any other parse problem.
            throw OxypeException(
                kind = e.kind,
                message = e.message ?: "服务器返回了无法解析的响应",
                cause = e,
                httpStatus = response.status
            )
        }
        if (parsed == null && response.body.isNotBlank()) {
            throw OxypeException(
                OxypeErrorKind.PROTOCOL,
                "服务器返回了无法解析的响应",
                httpStatus = response.status
            )
        }
        return OxypeJson.unwrap(parsed)
    }

    private fun parseOrNull(body: String): Any? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        // `JSONTokener` is lenient and would hand back `"<html>..."` as a bare string,
        // silently turning a misconfigured proxy's error page into a "valid" payload.
        // Only the characters a JSON value can actually start with are accepted.
        if (trimmed[0] !in JSON_VALUE_STARTS) {
            throw OxypeException(OxypeErrorKind.PROTOCOL, "服务器返回了无法解析的响应")
        }
        return try {
            // A JSON tokenizer also accepts the scalar answers the protocol occasionally
            // produces: a bare `true` from an extension, a number, or a quoted invite id.
            JSONTokener(trimmed).nextValue().takeUnless { it === JSONObject.NULL }
        } catch (e: Exception) {
            throw OxypeException(OxypeErrorKind.PROTOCOL, "服务器返回了无法解析的响应", e)
        }
    }

    /** Best effort for an error body: anything unparseable simply becomes `null`. */
    private fun parseLenient(body: String): Any? = try {
        parseOrNull(body)
    } catch (e: OxypeException) {
        null
    }

    private fun kindFor(status: Int): OxypeErrorKind = when (status) {
        400, 422 -> OxypeErrorKind.BAD_REQUEST
        401 -> OxypeErrorKind.UNAUTHORIZED
        403 -> OxypeErrorKind.FORBIDDEN
        404 -> OxypeErrorKind.NOT_FOUND
        in 300..399 -> OxypeErrorKind.PROTOCOL
        in 500..599 -> OxypeErrorKind.SERVER
        else -> OxypeErrorKind.PROTOCOL
    }

    private fun defaultMessageFor(status: Int): String = when (status) {
        400 -> "请求参数不正确"
        401 -> "登录已失效，请重新登录"
        403 -> "没有权限执行此操作"
        404 -> "请求的内容不存在"
        in 300..399 ->
            "服务器返回了重定向 (HTTP $status)。请确认服务器地址使用 https，且该地址没有再次跳转"

        in 500..599 -> "服务器内部错误"
        else -> "服务器返回了 HTTP $status"
    }

    companion object {
        const val DEFAULT_BASE_URL: String = "http://10.0.2.2:12345"

        /** Characters a JSON document may legitimately begin with. */
        private const val JSON_VALUE_STARTS = "{[\"-0123456789tfn"
    }
}
