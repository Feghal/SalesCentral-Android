package com.salescentral.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal HTTP abstraction so [SalesClient] can be unit-tested with a fake
 * transport (the analog of the injectable `URLSession` on iOS).
 *
 * Implementations throw [IOException] for transport failures (DNS, TLS,
 * offline); non-2xx statuses are returned, not thrown — the client maps
 * them to [SalesError.Http].
 */
interface HttpTransport {
    suspend fun execute(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): Response

    class Response(val status: Int, val body: ByteArray)
}

/** Production transport backed by [HttpURLConnection]. Zero dependencies. */
class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : HttpTransport {

    override suspend fun execute(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): HttpTransport.Response = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = true
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            HttpTransport.Response(status, bytes)
        } finally {
            conn.disconnect()
        }
    }
}
