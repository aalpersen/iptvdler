package io.github.aalpersen.iptvdler.core.download

import io.github.aalpersen.iptvdler.core.storage.DownloadStorage
import io.github.aalpersen.iptvdler.data.download.DownloadRepository
import io.github.aalpersen.iptvdler.data.network.streamHeaders
import io.github.aalpersen.iptvdler.i18n.strings
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okio.IOException
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/**
 * Downloads a single file (typical for IPTV movies: .mp4/.mkv) into `<output>.part`, resuming with an
 * HTTP Range request after interruptions, then renames it once the size is verified.
 */
class DirectDownloadJob(
    private val task: DownloadTask,
    private val repository: DownloadRepository,
    private val httpClient: HttpClient,
    private val storage: DownloadStorage,
    private val retryPolicy: RetryPolicy,
    private val reporterFactory: (initialBytes: Long, totalBytes: Long?) -> ProgressReporter,
) {
    private val fs = storage.fileSystem
    private val output = task.outputPath.toPath()
    private val part = output.parent!! / (output.name + ".part")

    suspend fun run() {
        output.parent?.let(fs::createDirectories)
        var total: Long? = task.totalBytes
        val reporter = reporterFactory(existingBytes(), total)

        var attempt = 1
        while (true) {
            try {
                total = transfer(total, reporter)
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val retryable = (e is HttpStatusException && e.retryable) || (e is IOException && e !is HttpStatusException)
                if (!retryable || attempt >= retryPolicy.maxAttempts) throw e
                reporter.setNote(strings.noteConnectionLost)
                delay(retryPolicy.delayBefore(attempt))
                attempt++
            }
        }

        val size = existingBytes()
        if (size <= 0 || (total != null && size != total)) {
            throw IOException("Verification failed: expected ${total ?: "?"} bytes, got $size")
        }
        if (fs.exists(output)) fs.delete(output)
        fs.atomicMove(part, output)
        repository.markCompleted(task.id, size)
    }

    /** One HTTP request, resuming from the current .part size. Returns the total size if known. */
    private suspend fun transfer(knownTotal: Long?, reporter: ProgressReporter): Long? {
        val existing = existingBytes()
        if (knownTotal != null && existing == knownTotal) return knownTotal

        return httpClient.prepareGet(task.sourceUrl) {
            streamHeaders(task.headers)
            if (existing > 0) header(HttpHeaders.Range, "bytes=$existing-")
            timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
        }.execute { response ->
            val resuming = response.status == HttpStatusCode.PartialContent
            if (response.status.value !in 200..299) throw HttpStatusException(response.status.value)

            val total = when {
                resuming -> response.headers[HttpHeaders.ContentRange]?.substringAfterLast('/')?.toLongOrNull()
                else -> response.contentLength()
            } ?: knownTotal
            if (total != knownTotal) {
                repository.updateTotalBytes(task.id, total)
                reporter.setTotalBytes(total)
            }
            if (!resuming && existing > 0) {
                // Server ignored the Range request: start over.
                reporter.add(-existing)
            }
            checkFreeSpace((total ?: 0) - (if (resuming) existing else 0))

            val sink = if (resuming) fs.appendingSink(part) else fs.sink(part)
            sink.buffer().use { out ->
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(CHUNK_BYTES)
                var sinceUpdate = 0L
                while (true) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read == -1) break
                    if (read == 0) continue
                    out.write(buffer, 0, read)
                    reporter.add(read.toLong())
                    sinceUpdate += read
                    if (sinceUpdate >= DB_UPDATE_BYTES) {
                        out.flush()
                        repository.updateProgress(task.id, reporter.downloadedBytes, 0)
                        sinceUpdate = 0
                    }
                }
            }
            repository.updateProgress(task.id, existingBytes(), 0)
            if (total != null && existingBytes() < total) throw IOException("Connection closed early")
            total
        }
    }

    private fun existingBytes(): Long = fs.metadataOrNull(part)?.size ?: 0

    private fun checkFreeSpace(neededBytes: Long) {
        if (neededBytes <= 0) return
        val free = storage.freeBytes(storage.downloadsDirectory()) ?: return
        if (free < neededBytes + 50L * 1024 * 1024) {
            throw DownloadRefusedException(strings.notEnoughSpace(neededBytes / (1024 * 1024), null))
        }
    }

    private companion object {
        const val CHUNK_BYTES = 256 * 1024
        const val DB_UPDATE_BYTES = 4L * 1024 * 1024
    }
}
