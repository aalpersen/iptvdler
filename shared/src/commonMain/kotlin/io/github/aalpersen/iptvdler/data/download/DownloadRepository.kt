package io.github.aalpersen.iptvdler.data.download

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import io.github.aalpersen.iptvdler.core.download.DownloadKind
import io.github.aalpersen.iptvdler.core.download.DownloadStatus
import io.github.aalpersen.iptvdler.core.download.DownloadSubtitle
import io.github.aalpersen.iptvdler.core.download.SubtitleKind
import io.github.aalpersen.iptvdler.core.subtitle.WrittenSidecars
import io.github.aalpersen.iptvdler.core.download.DownloadTask
import io.github.aalpersen.iptvdler.core.model.EpochClock
import io.github.aalpersen.iptvdler.data.db.Download_segment
import io.github.aalpersen.iptvdler.data.db.Download_subtitle
import io.github.aalpersen.iptvdler.data.db.Download_task
import io.github.aalpersen.iptvdler.data.db.IptvdlerDatabase
import io.github.aalpersen.iptvdler.data.playlist.HeaderCodec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** A new task to persist; for HLS, [segments] is the full segment list snapshot. */
data class NewDownload(
    val title: String,
    val sourceUrl: String,
    val headers: Map<String, String>,
    val kind: DownloadKind,
    val outputPath: String,
    val audioOutputPath: String? = null,
    val qualityLabel: String? = null,
    val durationSeconds: Double? = null,
    val totalBytes: Long? = null,
    val estimatedBytes: Long? = null,
    val segments: List<SegmentRecord> = emptyList(),
    val subtitles: List<NewSubtitle> = emptyList(),
    val stopAt: Long? = null,
    val variantUri: String? = null,
    val audioUri: String? = null,
)

/** A subtitle rendition to download with the task; its WebVTT segments are in [NewDownload.segments] under [track]. */
data class NewSubtitle(val track: Int, val language: String, val name: String, val sourceUri: String)

data class SegmentRecord(
    val track: Int,
    val position: Int,
    val uri: String,
    val rangeOffset: Long? = null,
    val rangeLength: Long? = null,
    val keyUri: String? = null,
    val ivHex: String? = null,
    val isInit: Boolean = false,
    val durationSeconds: Double = 0.0,
    val done: Boolean = false,
    val size: Long? = null,
)

class DownloadRepository(
    private val database: IptvdlerDatabase,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: EpochClock,
) {
    private val queries get() = database.downloadQueries

    fun observeTasks(): Flow<List<DownloadTask>> =
        queries.selectAll().asFlow().mapToList(ioDispatcher).map { rows -> rows.map { it.toModel() } }

    fun observeTask(id: Long): Flow<DownloadTask?> =
        queries.selectById(id).asFlow().mapToOneOrNull(ioDispatcher).map { it?.toModel() }

    suspend fun task(id: Long): DownloadTask? = io { queries.selectById(id).executeAsOneOrNull()?.toModel() }

    suspend fun tasksWithStatus(status: DownloadStatus): List<DownloadTask> =
        io { queries.selectByStatus(status.name).executeAsList().map { it.toModel() } }

    /** Paths already claimed by tasks (so new downloads don't pick the same file name). */
    suspend fun claimedPaths(): Set<String> = io { queries.selectOutputPaths().executeAsList().filterNotNull().toSet() }

    suspend fun create(download: NewDownload): Long = io {
        val now = clock.nowMillis()
        database.transactionWithResult {
            queries.insertTask(
                title = download.title,
                source_url = download.sourceUrl,
                http_headers = HeaderCodec.encode(download.headers),
                kind = download.kind.name,
                status = DownloadStatus.QUEUED.name,
                output_path = download.outputPath,
                audio_output_path = download.audioOutputPath,
                quality_label = download.qualityLabel,
                duration_seconds = download.durationSeconds,
                total_bytes = download.totalBytes,
                estimated_bytes = download.estimatedBytes,
                total_segments = download.segments.count { !it.isInit }.toLong(),
                created_at = now,
                updated_at = now,
                stop_at = download.stopAt,
                variant_uri = download.variantUri,
                audio_uri = download.audioUri,
            )
            val id = queries.lastInsertRowId().executeAsOne()
            download.segments.forEach { s ->
                queries.insertSegment(
                    Download_segment(
                        task_id = id,
                        track = s.track.toLong(),
                        position = s.position.toLong(),
                        uri = s.uri,
                        range_offset = s.rangeOffset,
                        range_length = s.rangeLength,
                        key_uri = s.keyUri,
                        iv_hex = s.ivHex,
                        is_init = if (s.isInit) 1 else 0,
                        duration_seconds = s.durationSeconds,
                        done = if (s.done) 1 else 0,
                        size = s.size,
                    ),
                )
            }
            download.subtitles.forEach { sub ->
                queries.insertSubtitle(id, sub.track.toLong(), sub.language, sub.name, sub.sourceUri, SubtitleKind.HLS.name, null, null)
            }
            id
        }
    }

    suspend fun segments(taskId: Long): List<SegmentRecord> = io {
        queries.selectSegments(taskId).executeAsList().map {
            SegmentRecord(
                track = it.track.toInt(),
                position = it.position.toInt(),
                uri = it.uri,
                rangeOffset = it.range_offset,
                rangeLength = it.range_length,
                keyUri = it.key_uri,
                ivHex = it.iv_hex,
                isInit = it.is_init != 0L,
                durationSeconds = it.duration_seconds,
                done = it.done != 0L,
                size = it.size,
            )
        }
    }

    suspend fun markSegmentDone(taskId: Long, segment: SegmentRecord, size: Long) = io {
        queries.markSegmentDone(size = size, taskId = taskId, track = segment.track.toLong(), position = segment.position.toLong())
    }

    suspend fun resetSegment(taskId: Long, segment: SegmentRecord) = io {
        queries.resetSegment(taskId = taskId, track = segment.track.toLong(), position = segment.position.toLong())
    }

    suspend fun updateStatus(id: Long, status: DownloadStatus, error: String? = null) = io {
        queries.updateStatus(status = status.name, error = error, updatedAt = clock.nowMillis(), id = id)
    }

    /** E.g. RUNNING → QUEUED on startup, after the app was killed mid-download. */
    suspend fun moveAll(from: DownloadStatus, to: DownloadStatus) = io {
        queries.updateStatusWhere(newStatus = to.name, updatedAt = clock.nowMillis(), oldStatus = from.name)
    }

    suspend fun updateProgress(id: Long, downloadedBytes: Long, completedSegments: Int) = io {
        queries.updateProgress(downloadedBytes, completedSegments.toLong(), clock.nowMillis(), id)
    }

    suspend fun updateRecordingProgress(id: Long, bytes: Long, durationSeconds: Double) = io {
        queries.updateRecordingProgress(bytes, durationSeconds, clock.nowMillis(), id)
    }

    suspend fun updateTotalBytes(id: Long, totalBytes: Long?) = io { queries.updateTotalBytes(totalBytes, id) }

    suspend fun markCompleted(id: Long, totalBytes: Long) = io {
        queries.markCompleted(totalBytes = totalBytes, completedAt = clock.nowMillis(), id = id)
    }

    suspend fun subtitles(taskId: Long): List<DownloadSubtitle> =
        io { queries.selectSubtitles(taskId).executeAsList().map { it.toModel() } }

    fun observeSubtitles(): Flow<Map<Long, List<DownloadSubtitle>>> =
        queries.selectAllSubtitles().asFlow().mapToList(ioDispatcher).map { rows -> rows.map { it.toModel() }.groupBy { it.taskId } }

    suspend fun addStandaloneSubtitle(taskId: Long, language: String, name: String, sourceUri: String, written: WrittenSidecars) = io {
        queries.insertSubtitle(taskId, null, language, name, sourceUri, SubtitleKind.FILE.name, written.vttPath, written.srtPath)
    }

    suspend fun markSubtitle(id: Long, written: WrittenSidecars?, error: String?) = io {
        queries.markSubtitleWritten(vttPath = written?.vttPath, srtPath = written?.srtPath, error = error, id = id)
    }

    suspend fun delete(id: Long) = io {
        database.transaction {
            queries.deleteSubtitles(id)
            queries.deleteSegments(id)
            queries.deleteTask(id)
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(ioDispatcher) { block() }
}

private fun Download_task.toModel() = DownloadTask(
    id = id,
    title = title,
    sourceUrl = source_url,
    headers = HeaderCodec.decode(http_headers),
    kind = DownloadKind.valueOf(kind),
    status = DownloadStatus.valueOf(status),
    outputPath = output_path,
    audioOutputPath = audio_output_path,
    qualityLabel = quality_label,
    durationSeconds = duration_seconds,
    totalBytes = total_bytes,
    estimatedBytes = estimated_bytes,
    downloadedBytes = downloaded_bytes,
    totalSegments = total_segments.toInt(),
    completedSegments = completed_segments.toInt(),
    error = error,
    createdAt = created_at,
    completedAt = completed_at,
    stopAt = stop_at,
    variantUri = variant_uri,
    audioUri = audio_uri,
)

private fun Download_subtitle.toModel() = DownloadSubtitle(
    id = id,
    taskId = task_id,
    track = track?.toInt(),
    language = language,
    name = name,
    kind = SubtitleKind.valueOf(kind),
    vttPath = vtt_path,
    srtPath = srt_path,
    error = error,
)
