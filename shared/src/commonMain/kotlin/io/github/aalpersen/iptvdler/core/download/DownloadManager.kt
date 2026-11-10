package io.github.aalpersen.iptvdler.core.download

import io.github.aalpersen.iptvdler.core.crypto.Aes128CbcDecryptor
import io.github.aalpersen.iptvdler.core.error.UserMessages
import io.github.aalpersen.iptvdler.core.model.EpochClock
import io.github.aalpersen.iptvdler.core.storage.DownloadStorage
import io.github.aalpersen.iptvdler.core.storage.NetworkMonitor
import io.github.aalpersen.iptvdler.core.subtitle.SubtitleFiles
import io.github.aalpersen.iptvdler.core.subtitle.SubtitleSidecars
import io.github.aalpersen.iptvdler.core.text.SubtitleText
import io.github.aalpersen.iptvdler.data.download.DownloadRepository
import io.github.aalpersen.iptvdler.data.download.NewDownload
import io.github.aalpersen.iptvdler.data.hls.HlsClient
import io.github.aalpersen.iptvdler.data.settings.AppSettings
import io.github.aalpersen.iptvdler.i18n.strings
import io.github.aalpersen.iptvdler.platform.AppLog
import io.ktor.client.HttpClient
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.IOException
import okio.Path.Companion.toPath

/** Tells the platform whether downloads are active (Android: start/stop the foreground service). */
fun interface DownloadForegroundController {
    fun onActiveDownloadsChanged(active: Boolean)
}

/** A task together with its live progress and subtitles, for UI. */
data class DownloadItem(
    val task: DownloadTask,
    val progress: DownloadProgress?,
    val subtitles: List<DownloadSubtitle> = emptyList(),
)

/**
 * Download queue: runs up to [AppSettings.maxConcurrentDownloads] tasks, resumes interrupted ones on
 * start, retries network failures automatically, and exposes progress as flows.
 */
class DownloadManager(
    private val repository: DownloadRepository,
    private val httpClient: HttpClient,
    private val storage: DownloadStorage,
    private val settings: AppSettings,
    private val networkMonitor: NetworkMonitor,
    private val foreground: DownloadForegroundController,
    private val decryptor: Aes128CbcDecryptor,
    private val scope: CoroutineScope,
    private val clock: EpochClock,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val networkRetryDelayMillis: Long = 30_000,
    private val maxNetworkRetries: Int = 10,
    /** Waits between live playlist polls; injectable so tests can drive time. */
    private val recordingSleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val _progress = MutableStateFlow<Map<Long, DownloadProgress>>(emptyMap())
    val progress: StateFlow<Map<Long, DownloadProgress>> = _progress.asStateFlow()

    val items: Flow<List<DownloadItem>> =
        combine(repository.observeTasks(), progress, repository.observeSubtitles()) { tasks, live, subtitles ->
            tasks.map { DownloadItem(it, live[it.id], subtitles[it.id].orEmpty()) }
        }

    private val mutex = Mutex()
    private val running = HashMap<Long, Job>()
    private val networkRetries = HashMap<Long, Int>()
    private val recordingIds = HashSet<Long>()
    /** Earliest time a network-failed task may run again (monotonic: immune to clock changes). */
    private val notBefore = HashMap<Long, TimeMark>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var started = false

    /** Resumes interrupted tasks and starts the scheduler. Safe to call more than once. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            repository.moveAll(DownloadStatus.RUNNING, DownloadStatus.QUEUED)
            while (isActive) {
                schedule()
                // Also tick periodically: Wi-Fi may come back, retry delays expire.
                kotlinx.coroutines.withTimeoutOrNull(TICK_MILLIS) { wake.receive() }
            }
        }
        scope.launch { settings.maxConcurrentDownloads.drop(1).collect { wake.trySend(Unit) } }
    }

    suspend fun enqueue(download: NewDownload): Long {
        val id = repository.create(download)
        wake.trySend(Unit)
        return id
    }

    suspend fun pause(id: Long) {
        if (repository.task(id)?.isRecording == true) return stopRecording(id)
        repository.updateStatus(id, DownloadStatus.PAUSED)
        stopJob(id)
    }

    /** Ends a recording now and keeps what was recorded. */
    suspend fun stopRecording(id: Long) {
        stopJob(id)
        val task = repository.task(id) ?: return
        if (task.status == DownloadStatus.COMPLETED) return
        finishRecording(task)
    }

    private suspend fun finishRecording(task: DownloadTask) {
        val fs = storage.fileSystem
        val size = listOfNotNull(task.outputPath, task.audioOutputPath).sumOf { fs.metadataOrNull(it.toPath())?.size ?: 0 }
        if (size > 0) {
            repository.markCompleted(task.id, size)
        } else {
            repository.updateStatus(task.id, DownloadStatus.FAILED, strings.nothingRecorded)
        }
        _progress.update { it - task.id }
    }

    /** Resume a paused task or retry a failed one. */
    suspend fun resume(id: Long) {
        mutex.withLock {
            networkRetries.remove(id)
            notBefore.remove(id)
        }
        repository.updateStatus(id, DownloadStatus.QUEUED)
        wake.trySend(Unit)
    }

    /** Cancels (if running) and removes a task. [deleteFiles] also removes finished output files. */
    suspend fun delete(id: Long, deleteFiles: Boolean) {
        stopJob(id)
        val task = repository.task(id) ?: return
        val fs = storage.fileSystem
        runCatching { fs.deleteRecursively(storage.tempDirectory(id)) }
        if (deleteFiles || task.status != DownloadStatus.COMPLETED) {
            repository.subtitles(id).flatMap { listOfNotNull(it.vttPath, it.srtPath) }.forEach { runCatching { fs.delete(it.toPath()) } }
        }
        listOfNotNull(task.outputPath, task.audioOutputPath).forEach { path ->
            val file = path.toPath()
            runCatching { fs.delete(file.parent!! / (file.name + ".part")) }
            if (deleteFiles || task.status != DownloadStatus.COMPLETED) runCatching { fs.delete(file) }
        }
        repository.delete(id)
    }

    /**
     * Downloads a standalone subtitle file (.vtt or .srt, from any URL) for a finished download and
     * saves it next to the video in the configured format(s).
     */
    suspend fun addSubtitle(taskId: Long, url: String, language: String): DownloadSubtitle {
        val task = repository.task(taskId) ?: throw IOException("Download not found")
        val lang = language.trim().ifEmpty { "und" }
        val bytes = SegmentFetcher(httpClient, retryPolicy).fetch(url, task.headers)
        val cues = SubtitleFiles.parse(SubtitleText.decode(bytes, lang))
        val written = SubtitleSidecars.write(storage.fileSystem, task.outputPath.toPath(), lang, cues, settings.subtitleOutput.value)
        repository.addStandaloneSubtitle(taskId, lang, lang, url, written)
        return repository.subtitles(taskId).last()
    }

    private suspend fun stopJob(id: Long) {
        val job = mutex.withLock { running[id] }
        job?.cancelAndJoin()
        _progress.update { it - id }
    }

    private suspend fun schedule() {
        mutex.withLock {
            val max = settings.maxConcurrentDownloads.value
            for (task in repository.tasksWithStatus(DownloadStatus.QUEUED)) {
                if (task.id in running || notBefore[task.id]?.hasPassedNow() == false) continue
                // Live recordings can't wait in the queue: they don't count against the limit.
                val downloadsRunning = running.keys.count { it !in recordingIds }
                if (!task.isRecording && downloadsRunning >= max) continue
                if (settings.wifiOnly.value && !networkMonitor.isOnUnmeteredNetwork()) {
                    publish(task.id, DownloadProgress(task.downloadedBytes, task.totalBytes ?: task.estimatedBytes, note = strings.noteWaitingForWifi))
                    continue
                }
                repository.updateStatus(task.id, DownloadStatus.RUNNING)
                val job = scope.launch { execute(task) }
                running[task.id] = job
                if (task.isRecording) recordingIds += task.id
                job.invokeOnCompletion {
                    scope.launch {
                        mutex.withLock {
                            if (running[task.id] === job) {
                                running.remove(task.id)
                                recordingIds.remove(task.id)
                            }
                            foreground.onActiveDownloadsChanged(running.isNotEmpty())
                        }
                        wake.trySend(Unit)
                    }
                }
            }
            foreground.onActiveDownloadsChanged(running.isNotEmpty())
        }
    }

    private suspend fun execute(task: DownloadTask) {
        try {
            when (task.kind) {
                DownloadKind.HLS -> HlsDownloadJob(
                    task = task,
                    repository = repository,
                    fetcher = SegmentFetcher(httpClient, retryPolicy),
                    decryptor = decryptor,
                    storage = storage,
                    parallelism = settings.parallelSegments.value,
                    subtitleOutput = settings.subtitleOutput.value,
                    reporterFactory = { bytes, segments ->
                        ProgressReporter(clock, task.totalSegments, task.estimatedBytes, bytes, segments) { publish(task.id, it) }
                    },
                ).run()
                DownloadKind.DIRECT -> DirectDownloadJob(
                    task = task,
                    repository = repository,
                    httpClient = httpClient,
                    storage = storage,
                    retryPolicy = retryPolicy,
                    reporterFactory = { bytes, total -> ProgressReporter(clock, 0, total, bytes, 0) { publish(task.id, it) } },
                ).run()
                DownloadKind.HLS_LIVE -> {
                    HlsLiveRecordingJob(
                        task = task,
                        repository = repository,
                        hlsClient = HlsClient(httpClient),
                        fetcher = SegmentFetcher(httpClient, retryPolicy),
                        decryptor = decryptor,
                        storage = storage,
                        clock = clock,
                        reporter = ProgressReporter(clock, 0, null, 0, 0) { publish(task.id, it) },
                        sleep = recordingSleep,
                    ).run()
                    finishRecording(task)
                }
                DownloadKind.DIRECT_LIVE -> {
                    DirectLiveRecordingJob(
                        task = task,
                        repository = repository,
                        httpClient = httpClient,
                        storage = storage,
                        clock = clock,
                        reporter = ProgressReporter(clock, 0, null, 0, 0) { publish(task.id, it) },
                        sleep = recordingSleep,
                    ).run()
                    finishRecording(task)
                }
            }
            mutex.withLock { networkRetries.remove(task.id) }
            _progress.update { it - task.id }
        } catch (e: CancellationException) {
            throw e // paused, stopped or deleted; the caller sets the status
        } catch (e: Exception) {
            AppLog.warn("Download ${task.id} (${task.kind}) failed", e)
            val message = UserMessages.describe(e, strings.downloadFailed)
            val isNetwork = e is IOException && (e !is HttpStatusException || e.retryable) && !UserMessages.isStorageError(e)
            val attempt = mutex.withLock { (networkRetries[task.id] ?: 0) + 1 }
            if (isNetwork && attempt <= maxNetworkRetries && !task.isRecording) {
                // Keep what we have; try again later (e.g. network dropped).
                mutex.withLock {
                    networkRetries[task.id] = attempt
                    notBefore[task.id] = TimeSource.Monotonic.markNow() + networkRetryDelayMillis.milliseconds
                }
                repository.updateStatus(task.id, DownloadStatus.QUEUED, strings.networkProblemRetrying(message))
                publish(task.id, (progress.value[task.id] ?: DownloadProgress(task.downloadedBytes, task.estimatedBytes)).copy(note = strings.noteRetryingSoon, bytesPerSecond = 0))
                scope.launch {
                    delay(networkRetryDelayMillis)
                    wake.trySend(Unit)
                }
            } else {
                repository.updateStatus(task.id, DownloadStatus.FAILED, message)
                _progress.update { it - task.id }
            }
        }
    }

    private fun publish(id: Long, value: DownloadProgress) = _progress.update { it + (id to value) }

    private companion object {
        const val TICK_MILLIS = 5_000L
    }
}
