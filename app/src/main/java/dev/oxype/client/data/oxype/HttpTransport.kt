package dev.oxype.client.data.oxype

/**
 * One HTTP exchange, kept free of Android or java.net types so a JVM test can fake it.
 */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null
)

data class HttpResponse(
    val status: Int,
    val body: String
)

/** Transport seam: the real implementation is [HttpUrlConnectionTransport]. */
interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}
