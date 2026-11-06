package dev.oxype.client.testsupport

import dev.oxype.client.data.OxypeRepository
import dev.oxype.client.data.oxype.AuthCredentials
import dev.oxype.client.data.oxype.HttpRequest
import dev.oxype.client.data.oxype.HttpResponse
import dev.oxype.client.data.oxype.HttpTransport
import dev.oxype.client.data.oxype.OxypeApi
import dev.oxype.client.data.oxype.OxypeHttpClient
import dev.oxype.client.data.session.SessionStorage
import dev.oxype.client.data.session.ThemeMode
import kotlinx.coroutines.CoroutineScope

/** In-memory [SessionStorage] shared by the UI-layer tests. */
internal class FakeSessionStorage : SessionStorage {
    override var baseUrl: String = "http://example.test:12345"
    override var themeMode: ThemeMode = ThemeMode.SYSTEM
    private var stored: AuthCredentials? = null
    private var storedName: String? = null

    override fun credentials(): AuthCredentials? = stored
    override fun username(): String? = storedName

    override fun saveAuth(credentials: AuthCredentials, username: String?) {
        stored = credentials
        storedName = username
    }

    override fun clearAuth() {
        stored = null
        storedName = null
    }

    override fun hasStoredAuth(): Boolean = stored != null
}

/**
 * Route-aware fake transport: `route` is the path without the base URL and query.
 *
 * A handler returning `null` accepts the default answer for the route, so a test only
 * declares the routes its scenario is about.
 */
internal class ScriptedTransport(
    private val handler: suspend (HttpRequest, String) -> HttpResponse?
) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests.add(request)
        val path = request.url.substringAfter("://").substringAfter('/')
        val route = "/" + path.substringBefore('?')
        return handler(request, route) ?: when {
            route.startsWith("/login/") ->
                HttpResponse(200, """{"data":{"token":"tok"}}""")

            route.startsWith("/isAlive/") ->
                HttpResponse(200, """{"data":{"is_alive":true}}""")

            else -> HttpResponse(404, """{"error_message":"unscripted route"}""")
        }
    }
}

internal fun repositoryWith(
    storage: SessionStorage,
    transport: HttpTransport,
    scope: CoroutineScope
): OxypeRepository {
    val http = OxypeHttpClient(transport = transport, baseUrl = storage.baseUrl)
    return OxypeRepository(
        api = OxypeApi(http),
        http = http,
        sessionStore = storage,
        scope = scope
    )
}
