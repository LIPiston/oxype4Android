package dev.oxype.client.data.oxype

import org.json.JSONArray
import org.json.JSONObject

/**
 * Defensive readers for Kalmia/Oxype JSON.
 *
 * Routes in the experimental Oxype server have been seen to spell the same field in
 * both snake_case and camelCase (`userid`/`userId`, `session_id`/`sessionId`,
 * `is_alive`/`isAlive`), and to wrap a payload either in `data` or return it bare.
 * Everything that reads a value goes through this file so a spelling change only has
 * to be fixed in one place.
 */
internal object OxypeJson {

    /** True when [key] holds a real value; `JSONObject.NULL` and absent keys do not. */
    fun hasValue(json: JSONObject, key: String): Boolean {
        if (!json.has(key)) return false
        val value = json.opt(key) ?: return false
        return value !== JSONObject.NULL
    }

    /** First present, non-null value among [keys]. */
    fun firstValue(json: JSONObject, vararg keys: String): Any? {
        for (key in keys) {
            if (hasValue(json, key)) {
                return json.opt(key)
            }
        }
        return null
    }

    fun long(json: JSONObject, vararg keys: String): Long? = toLong(firstValue(json, *keys))

    fun longOr(json: JSONObject, fallback: Long, vararg keys: String): Long =
        long(json, *keys) ?: fallback

    fun string(json: JSONObject, vararg keys: String): String? {
        return when (val value = firstValue(json, *keys)) {
            is String -> value
            is Number, is Boolean -> value.toString()
            else -> null
        }
    }

    fun stringOr(json: JSONObject, fallback: String, vararg keys: String): String =
        string(json, *keys) ?: fallback

    fun boolean(json: JSONObject, vararg keys: String): Boolean? {
        return when (val value = firstValue(json, *keys)) {
            is Boolean -> value
            is String -> when (value.trim().lowercase()) {
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> null
            }
            is Number -> value.toDouble() != 0.0
            else -> null
        }
    }

    /**
     * Accepts every shape a list has appeared in: a bare array, or an object holding
     * it under one of [keys]. Anything else is treated as "no list", never as an error.
     */
    fun arrayOf(value: Any?, vararg keys: String): JSONArray? {
        if (value is JSONArray) return value
        if (value is JSONObject) {
            for (key in keys) {
                val candidate = firstValue(value, key)
                if (candidate is JSONArray) return candidate
            }
        }
        return null
    }

    fun objects(array: JSONArray): List<JSONObject> {
        val result = ArrayList<JSONObject>(array.length())
        for (index in 0 until array.length()) {
            val item = array.opt(index)
            if (item is JSONObject) result.add(item)
        }
        return result
    }

    fun longList(array: JSONArray?): List<Long> {
        if (array == null) return emptyList()
        val result = ArrayList<Long>(array.length())
        for (index in 0 until array.length()) {
            toLong(array.opt(index))?.let(result::add)
        }
        return result
    }

    fun toLong(value: Any?): Long? = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Short -> value.toLong()
        is Byte -> value.toLong()
        is Double -> if (value % 1.0 == 0.0) value.toLong() else null
        is Float -> if (value % 1.0f == 0.0f) value.toLong() else null
        // Ids read back from storage are strings; the API parses them as numbers.
        is String -> value.trim().toLongOrNull()
        else -> null
    }

    /**
     * Extracts the useful message from a failed Kalmia response.
     *
     * `error` only carries the generic protocol banner ("Server protocol error
     * (Kalmia/..., HTTP/1.1): Bad Request"), which is useless in a UI, so the specific
     * fields are preferred and that banner is only a last resort.
     */
    fun errorMessage(json: JSONObject?): String? {
        if (json == null) return null
        val specific = listOf("error_message", "error_details_message", "message")
            .mapNotNull { string(json, it) }
            .firstOrNull { it.isNotBlank() && !isProtocolBanner(it) }
        if (specific != null) return specific

        return listOf("error_message", "error_details_message", "message", "error")
            .mapNotNull { string(json, it) }
            .firstOrNull { it.isNotBlank() }
    }

    private fun isProtocolBanner(text: String): Boolean =
        text.trim().startsWith("Server protocol error", ignoreCase = true)

    /**
     * Unwraps the Kalmia envelope.
     *
     * Returns the `data` node when present (which may itself be an array), otherwise
     * the document unchanged so routes that answer bare keep working.
     */
    fun unwrap(root: Any?): Any? {
        if (root is JSONObject && hasValue(root, "data")) {
            return root.opt("data")
        }
        return root
    }
}
