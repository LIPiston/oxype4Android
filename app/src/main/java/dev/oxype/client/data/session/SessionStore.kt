package dev.oxype.client.data.session

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.oxype.client.data.oxype.AuthCredentials
import dev.oxype.client.data.oxype.OxypeHttpClient

/** Light/dark/system choice persisted across launches. */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    companion object {
        fun fromName(name: String?): ThemeMode =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/**
 * The slice of local storage [dev.oxype.client.data.OxypeRepository] needs.
 *
 * Extracted so repository behaviour can be tested on the JVM with an in-memory
 * implementation instead of an Android [Context]. The concrete class is
 * [SessionStore].
 */
interface SessionStorage {
    var baseUrl: String
    var themeMode: ThemeMode
    fun credentials(): AuthCredentials?
    fun username(): String?
    fun saveAuth(credentials: AuthCredentials, username: String?)
    fun clearAuth()
    fun hasStoredAuth(): Boolean
}

/**
 * Local, non-authoritative state: the auth token and the server the user pointed at.
 *
 * Everything about sessions and messages belongs to the Oxype server, so nothing else
 * is cached here. Plain [SharedPreferences] is deliberate — there is one small record
 * and no need to pull in a database for it.
 */
class SessionStore(context: Context) : SessionStorage {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, null)
            ?.takeIf { it.isNotBlank() }
            ?: OxypeHttpClient.DEFAULT_BASE_URL
        set(value) {
            prefs.edit { putString(KEY_BASE_URL, value.trim()) }
        }

    override var themeMode: ThemeMode
        get() = ThemeMode.fromName(prefs.getString(KEY_THEME_MODE, null))
        set(value) {
            prefs.edit { putString(KEY_THEME_MODE, value.name) }
        }

    /** `null` when nobody is signed in, or when the stored pair is incomplete. */
    override fun credentials(): AuthCredentials? {
        val userId = prefs.getLong(KEY_USER_ID, NO_USER)
        if (userId <= 0L) return null
        val token = prefs.getString(KEY_TOKEN, null)
        if (token.isNullOrBlank()) return null
        return AuthCredentials(userId, token)
    }

    override fun username(): String? = prefs.getString(KEY_USERNAME, null)

    override fun saveAuth(credentials: AuthCredentials, username: String?) {
        prefs.edit {
            putLong(KEY_USER_ID, credentials.userId)
            putString(KEY_TOKEN, credentials.token)
            putString(KEY_USERNAME, username)
        }
    }

    override fun clearAuth() {
        prefs.edit {
            remove(KEY_USER_ID)
            remove(KEY_TOKEN)
            remove(KEY_USERNAME)
        }
    }

    override fun hasStoredAuth(): Boolean = credentials() != null

    private companion object {
        const val PREFS_NAME = "oxype_client_session"
        const val KEY_USER_ID = "user_id"
        const val KEY_TOKEN = "token"
        const val KEY_USERNAME = "username"
        const val KEY_BASE_URL = "base_url"
        const val KEY_THEME_MODE = "theme_mode"
        const val NO_USER = -1L
    }
}
