package io.github.aalpersen.iptvdler.core.download

import io.github.aalpersen.iptvdler.core.crypto.Aes128CbcDecryptor
import io.github.aalpersen.iptvdler.core.hls.HlsIv
import io.github.aalpersen.iptvdler.core.hls.InitSegment
import io.github.aalpersen.iptvdler.core.hls.KeyMethod
import io.github.aalpersen.iptvdler.core.hls.MediaPlaylist
import io.github.aalpersen.iptvdler.core.hls.Segment
import io.github.aalpersen.iptvdler.core.model.EpochClock
import io.github.aalpersen.iptvdler.core.storage.DownloadStorage
import io.github.aalpersen.iptvdler.data.download.DownloadRepository
import io.github.aalpersen.iptvdler.data.hls.HlsClient
import io.github.aalpersen.iptvdler.data.network.streamHeaders
import io.github.aalpersen.iptvdler.i18n.strings
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/** What happened during a recording, for the task's status line. */
data class RecordingStats(val bytes: Long, val seconds: Double, val missedSegments: Int, val playlistResets: Int)

/**
 * Records a live HLS stream: polls the media playlist about once per target duration (half that when
 * nothing new appeared), downloads segments it hasn't seen (deduplicated by media sequence number),
 * decrypts AES-128 and appends them to the output file, so an interrupted recording stays playable.
 *
 * Starts [liveEdgeSegments] segments behind the live edge, like players do. Handles playlist resets
 * (sequence numbers going backwards: the stream restarted), gaps (segments that left the window before
 * we fetched them are counted as missed), discontinuities and changing init sections, key rotation,
 * and `#EXT-X-ENDLIST` (the event ended). Stops at [DownloadTask.stopAt] or when cancelled.
 */
class HlsLiveRecordingJob(
    private val task: DownloadTask,
    private val repository: DownloadRepository,
    private val hlsClient: HlsClient,
    private val fetcher: SegmentFetcher,
    private val decryptor: Aes128CbcDecryptor,
    private val storage: DownloadStorage,
    private val clock: EpochClock,
    private val reporter: ProgressReporter,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val liveEdgeSegments: Int = 3,
    private val maxConsecutivePlaylistFailures: Int = 10,
) {
    private val fs = storage.fileSystem
    private val mutex = Mutex()
    private var totalBytes = 0L
    private var seconds = task.durationSeconds ?: 0.0
    private var missed = 0
    private var resets = 0

    private class Track(val uri: String, val output: Path, val isMain: Boolean) {
        var lastSequence = -1L
        var lastInit: InitSegment? = null
    }

    suspend fun run(): RecordingStats {
        val tracks = listOfNotNull(
            Track(task.variantUri ?: task.sourceUrl, task.outputPath.toPath(), isMain = true),
            task.audioUri?.let { uri -> task.audioOutputPath?.let { Track(uri, it.toPath(), isMain = false) } },
        )
        tracks.forEach { track ->
            track.output.parent?.let(fs::createDirectories)
            totalBytes += fs.metadataOrNull(track.output)?.size ?: 0 // resuming after a restart: append
        }
        reporter.setNote(strings.noteRecording)
        coroutineScope { tracks.forEach { track -> launch { record(track) } } }
        return RecordingStats(totalBytes, seconds, missed, resets)
    }

    private fun stopReached(): Boolean = task.stopAt?.let { clock.nowMillis() >= it } == true

    private suspend fun record(track: Track) {
        var failures = 0
        while (!stopReached()) {
            val playlist = try {
                hlsClient.fetchMedia(track.uri, task.headers).also { failures = 0 }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (++failures >= maxConsecutivePlaylistFailures) throw e
                sleep(RETRY_DELAY_MILLIS)
                continue
            }

            val fresh = newSegments(track, playlist)
            for (segment in fresh) {
                if (stopReached()) return
                if (!segment.gap) append(track, segment)
                track.lastSequence = segment.mediaSequence
            }
            if (playlist.hasEndList && (playlist.segments.isEmpty() || track.lastSequence >= playlist.segments.last().mediaSequence)) {
                return // the event is over
            }

            val targetMillis = (playlist.targetDurationSeconds.coerceAtLeast(1.0) * 1000).toLong()
            var wait = if (fresh.isEmpty()) targetMillis / 2 else targetMillis
            task.stopAt?.let { wait = wait.coerceAtMost((it - clock.nowMillis()).coerceAtLeast(0)) }
            sleep(wait)
        }
    }

    private suspend fun newSegments(track: Track, playlist: MediaPlaylist): List<Segment> {
        val segments = playlist.segments
        if (segments.isEmpty()) return emptyList()
        val newest = segments.last().mediaSequence
        return when {
            track.lastSequence < 0 -> segments.takeLast(liveEdgeSegments)
            newest < track.lastSequence -> {
                // Sequence numbers went backwards: the stream restarted. Pick up at its live edge.
                if (track.isMain) mutex.withLock { resets++ }
                track.lastInit = null
                segments.takeLast(liveEdgeSegments)
            }
            else -> {
                val fresh = segments.filter { it.mediaSequence > track.lastSequence }
                val skipped = (fresh.firstOrNull()?.mediaSequence ?: (track.lastSequence + 1)) - track.lastSequence - 1
                if (skipped > 0 && track.isMain) mutex.withLock { missed += skipped.toInt() }
                fresh
            }
        }
    }

    private suspend fun append(track: Track, segment: Segment) {
        val data = try {
            val init = segment.initSegment
            val initBytes = if (init != null && init != track.lastInit) fetchInit(init, segment.mediaSequence) else null
            val media = fetcher.fetch(segment.uri, task.headers, segment.byteRange).let { raw ->
                segment.aes128Key?.let { key ->
                    decryptor.decrypt(raw, fetcher.key(key.uri!!, task.headers), HlsIv.forSegment(key, segment.mediaSequence))
                } ?: raw
            }
            if (initBytes != null) track.lastInit = init
            (initBytes ?: ByteArray(0)) + media
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // One lost segment shouldn't end a live recording: note it and keep going.
            if (track.isMain) mutex.withLock { missed++ }
            return
        }

        fs.appendingSink(track.output).buffer().use { it.write(data) }
        mutex.withLock {
            totalBytes += data.size
            if (track.isMain) seconds += segment.durationSeconds
            reporter.add(data.size.toLong())
            reporter.setNote(recordingNote())
            repository.updateRecordingProgress(task.id, totalBytes, seconds)
        }
    }

    private suspend fun fetchInit(init: InitSegment, firstSequence: Long): ByteArray {
        val raw = fetcher.fetch(init.uri, task.headers, init.byteRange)
        val key = init.keys.firstOrNull { it.isIdentity && it.method == KeyMethod.AES_128 } ?: return raw
        return decryptor.decrypt(raw, fetcher.key(key.uri!!, task.headers), HlsIv.forSegment(key, firstSequence))
    }

    private fun recordingNote(): String = if (missed > 0) strings.segmentsMissed(missed) else strings.noteRecording

    private companion object {
        const val RETRY_DELAY_MILLIS = 2_000L
    }
}

/**
 * Records a continuous stream (typical IPTV `.ts` channel URL) by appending its bytes to the output
 * file, reconnecting when the server drops the connection, until [DownloadTask.stopAt] or cancelled.
 */
class DirectLiveRecordingJob(
    private val task: DownloadTask,
    private val repository: DownloadRepository,
    private val httpClient: HttpClient,
    private val storage: DownloadStorage,
    private val clock: EpochClock,
    private val reporter: ProgressReporter,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val maxConsecutiveFailures: Int = 10,
) {
    private val fs = storage.fileSystem
    private val output = task.outputPath.toPath()
    private var bytes = 0L
    private var seconds = task.durationSeconds ?: 0.0

    suspend fun run(): RecordingStats {
        output.parent?.let(fs::createDirectories)
        bytes = fs.metadataOrNull(output)?.size ?: 0
        reporter.setNote(strings.noteRecording)
        var failures = 0
        var lastError: Exception? = null
        while (true) {
            val remaining = task.stopAt?.let { it - clock.nowMillis() }
            if (remaining != null && remaining <= 0) break
            val received = try {
                // Returns bytes received in this connection, or null when the stop time cut it off.
                withTimeoutOrNull(remaining ?: Long.MAX_VALUE) { streamOnce() } ?: break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                0L
            }
            failures = if (received > 0) 0 else failures + 1
            if (failures >= maxConsecutiveFailures) throw lastError ?: okio.IOException(strings.streamKeepsEnding)
            reporter.setNote(strings.noteReconnecting)
            sleep(RECONNECT_DELAY_MILLIS)
        }
        return RecordingStats(bytes, seconds, 0, 0)
    }

    private suspend fun streamOnce(): Long = httpClient.prepareGet(task.sourceUrl) {
        streamHeaders(task.headers)
        timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
    }.execute { response ->
        if (response.status.value !in 200..299) throw HttpStatusException(response.status.value)
        reporter.setNote(strings.noteRecording)
        var received = 0L
        var mark = TimeSource.Monotonic.markNow()
        fs.appendingSink(output).buffer().use { sink ->
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(CHUNK_BYTES)
            while (true) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read == -1) break
                if (read == 0) continue
                sink.write(buffer, 0, read)
                received += read
                bytes += read
                reporter.add(read.toLong())
                if (mark.elapsedNow().inWholeMilliseconds >= UPDATE_MILLIS) {
                    seconds += mark.elapsedNow().inWholeMilliseconds / 1000.0
                    mark = TimeSource.Monotonic.markNow()
                    sink.flush()
                    repository.updateRecordingProgress(task.id, bytes, seconds)
                }
            }
            seconds += mark.elapsedNow().inWholeMilliseconds / 1000.0
        }
        repository.updateRecordingProgress(task.id, bytes, seconds)
        received
    }

    private companion object {
        const val CHUNK_BYTES = 64 * 1024
        const val UPDATE_MILLIS = 1_000L
        const val RECONNECT_DELAY_MILLIS = 2_000L
    }
}
