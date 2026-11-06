package dev.oxype.client.data.oxype

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * [HttpTransport] over [HttpURLConnection], with no third-party HTTP stack.
 *
 * Kalmia answers and then drops the socket without advertising `Connection: close`.
 * A pooling client therefore reuses a dead connection and intermittently sees
 * `ECONNRESET`, so this transport asks for one connection per request instead.
 */
class HttpUrlConnectionTransport(
    private val connectTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS
) : HttpTransport {

    override suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
                requestMethod = request.method
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMillis
                readTimeout = readTimeoutMillis
                // One exchange per socket: see the class comment.
                setRequestProperty("Connection", "close")
                setRequestProperty("Accept", "application/json")
                request.headers.forEach { (name, value) ->
                    setRequestProperty(name, value)
                }
                val payload = request.body
                if (payload != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    val bytes = payload.toByteArray(Charsets.UTF_8)
                    setFixedLengthStreamingMode(bytes.size)
                    outputStream.use { it.write(bytes) }
                }
            }

            val status = connection.responseCode
            val stream: InputStream? = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.use { readFully(it) } ?: ""
            HttpResponse(status = status, body = body)
        } catch (e: SocketTimeoutException) {
            throw OxypeException(OxypeErrorKind.NETWORK, "请求超时，请检查服务器地址", e)
        } catch (e: IOException) {
            throw OxypeException(OxypeErrorKind.NETWORK, "无法连接服务器", e)
        } finally {
            connection?.disconnect()
        }
    }

    private fun readFully(stream: InputStream): String {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS: Int = 15_000
    }
}
