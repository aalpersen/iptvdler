package io.github.aalpersen.iptvdler.core.download

import io.github.aalpersen.iptvdler.core.error.UserMessages
import io.github.aalpersen.iptvdler.core.hls.ByteRange
import io.github.aalpersen.iptvdler.data.network.streamHeaders
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readBuffer
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.readByteArray
import okio.IOException

/** HTTP error worth retrying (5xx, 408, 429) vs. a permanent one (other 4xx). */
class HttpStatusException(val status: Int) : IOException(UserMessages.httpStatus(status)) {
    val retryable: Boolean get() = status >= 500 || status == 408 || status == 429
}

data class RetryPolicy(
    val maxAttempts: Int = 5,
    val initialDelayMillis: Long = 1_000,
    val maxDelayMillis: Long = 30_000,
    val requestTimeoutMillis: Long = 60_000,
) {
    fun delayBefore(attempt: Int): Long =
        (initialDelayMillis shl (attempt - 1).coerceIn(0, 20)).coerceAtMost(maxDelayMillis)
}

/** Fetches whole segments and keys into memory, with retries and exponential backoff. */
class SegmentFetcher(
    private val httpClient: HttpClient,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
) {
    private val keyCache = HashMap<String, ByteArray>()
    private val keyMutex = Mutex()

    suspend fun fetch(url: String, headers: Map<String, String>, range: ByteRange? = null): ByteArray =
        withRetry { fetchOnce(url, headers, range) }

    /** AES-128 key bytes, fetched once per URI. Supports `data:` URIs. */
    suspend fun key(uri: String, headers: Map<String, String>): ByteArray = keyMutex.withLock {
        keyCache[uri] ?: run {
            val bytes = if (uri.startsWith("data:")) decodeDataUri(uri) else withRetry { fetchOnce(uri, headers, null) }
            if (bytes.size != 16) throw IOException("Key at $uri is ${bytes.size} bytes, expected 16")
            bytes.also { keyCache[uri] = it }
        }
    }

    private suspend fun fetchOnce(url: String, headers: Map<String, String>, range: ByteRange?): ByteArray =
        httpClient.prepareGet(url) {
            streamHeaders(headers)
            range?.let { header(HttpHeaders.Range, "bytes=${it.offset}-${it.endExclusive - 1}") }
            timeout { requestTimeoutMillis = retryPolicy.requestTimeoutMillis }
        }.execute { response ->
            val status = response.status.value
            if (status !in 200..299) throw HttpStatusException(status)
            val bytes = response.bodyAsChannel().readBuffer().readByteArray()
            if (range != null) {
                when {
                    // Server ignored the Range header and sent the whole resource: cut our part out.
                    response.status == HttpStatusCode.OK && bytes.size.toLong() >= range.endExclusive ->
                        bytes.copyOfRange(range.offset.toInt(), range.endExclusive.toInt())
                    bytes.size.toLong() != range.length -> throw IOException("Expected ${range.length} bytes, got ${bytes.size}")
                    else -> bytes
                }
            } else {
                bytes
            }
        }

    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val retryable = when (e) {
                    is HttpStatusException -> e.retryable
                    is HttpRequestTimeoutException, is IOException -> true
                    else -> false
                }
                if (!retryable || attempt >= retryPolicy.maxAttempts) throw e
                delay(retryPolicy.delayBefore(attempt))
                attempt++
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeDataUri(uri: String): ByteArray {
        val meta = uri.substringAfter("data:").substringBefore(',')
        val payload = uri.substringAfter(',')
        return if (meta.endsWith(";base64")) Base64.decode(payload) else payload.encodeToByteArray()
    }
}
