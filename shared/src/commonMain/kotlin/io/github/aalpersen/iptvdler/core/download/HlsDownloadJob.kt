package io.github.aalpersen.iptvdler.core.download

import io.github.aalpersen.iptvdler.core.crypto.Aes128CbcDecryptor
import io.github.aalpersen.iptvdler.core.error.UserMessages
import io.github.aalpersen.iptvdler.core.hls.ByteRange
import io.github.aalpersen.iptvdler.core.hls.HlsIv
import io.github.aalpersen.iptvdler.core.media.Fmp4SegmentIndex
import io.github.aalpersen.iptvdler.core.media.Mp4Boxes
import io.github.aalpersen.iptvdler.core.storage.DownloadStorage
import io.github.aalpersen.iptvdler.core.subtitle.MediaTimestamps
import io.github.aalpersen.iptvdler.core.subtitle.SubtitleOutput
import io.github.aalpersen.iptvdler.core.subtitle.SubtitleSidecars
import io.github.aalpersen.iptvdler.core.subtitle.VttSegmentMerger
import io.github.aalpersen.iptvdler.data.download.DownloadRepository
import io.github.aalpersen.iptvdler.data.download.SegmentRecord
import io.github.aalpersen.iptvdler.i18n.strings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/**
 * Downloads an HLS task's segments into a temp folder (resumable: finished segments are recorded in
 * the DB and verified against the files on restart), decrypts AES-128, then concatenates each track
 * into its output file. Temp files are deleted only after the output is verified.
 */
class HlsDownloadJob(
    private val task: DownloadTask,
    private val repository: DownloadRepository,
    private val fetcher: SegmentFetcher,
    private val decryptor: Aes128CbcDecryptor,
    private val storage: DownloadStorage,
    private val parallelism: Int,
    private val subtitleOutput: SubtitleOutput,
    private val reporterFactory: (initialBytes: Long, initialSegments: Int) -> ProgressReporter,
) {
    private val fs = storage.fileSystem
    private val tempDir = storage.tempDirectory(task.id)
    private val reportMutex = Mutex()

    /** Subtitle tracks whose segments couldn't be fetched: skipped, the video still completes. */
    private val failedSubtitleTracks = HashMap<Int, String>()

    suspend fun run() {
        fs.createDirectories(tempDir)
        val segments = repository.segments(task.id)
        if (segments.none { !it.isInit }) throw IOException("Download has no segments")

        val pending = ArrayList<SegmentRecord>()
        var doneBytes = 0L
        var doneSegments = 0
        for (segment in segments) {
            val onDisk = if (segment.done) fs.metadataOrNull(segmentFile(segment))?.size else null
            if (segment.done && onDisk != null && onDisk == segment.size) {
                doneBytes += onDisk
                if (!segment.isInit) doneSegments++
            } else {
                if (segment.done) repository.resetSegment(task.id, segment) // file lost: fetch again
                pending += segment
            }
        }

        checkFreeSpace(neededBytes = (task.estimatedBytes ?: 0) - doneBytes, doubled = true)
        val reporter = reporterFactory(doneBytes, doneSegments)

        if (pending.isNotEmpty()) {
            val queue = Channel<SegmentRecord>(Channel.UNLIMITED)
            pending.forEach { queue.send(it) }
            queue.close()
            coroutineScope {
                repeat(parallelism.coerceIn(1, pending.size)) {
                    launch {
                        for (segment in queue) {
                            val size = if (segment.track >= DownloadPlanner.FIRST_SUBTITLE_TRACK) {
                                downloadSubtitleSegment(segment) ?: continue
                            } else {
                                download(segment)
                            }
                            reportMutex.withLock {
                                reporter.add(size, if (segment.isInit) 0 else 1)
                                repository.updateProgress(task.id, reporter.downloadedBytes, reporter.completedSegments)
                            }
                        }
                    }
                }
            }
        }

        reporter.setNote(strings.noteAssembling)
        val finished = repository.segments(task.id)
        val media = finished.filter { it.track < DownloadPlanner.FIRST_SUBTITLE_TRACK }
        if (media.any { !it.done }) throw IOException("Some segments are missing")
        checkFreeSpace(neededBytes = media.sumOf { it.size ?: 0 }, doubled = false)

        // fMP4 keeps the stream's timestamps (often starting at 10 s) and players show that timeline as-is,
        // unlike MPEG-TS, which they start at 0. Shift fMP4 to start at 0 too, the same for video and audio.
        val tracks = listOf(0, 1).associateWith { track -> readFmp4Track(media.filter { it.track == track }) }
        val fmp4Offset = timelineOffsetSeconds(tracks.values.filterNotNull())
        val subtitleOrigin = if (fmp4Offset != null) (fmp4Offset * VttSegmentMerger.TICKS_PER_SECOND).toLong()
        else videoStartPts(media.filter { it.track == 0 })

        var total = assemble(media.filter { it.track == 0 }, task.outputPath.toPath(), tracks[0], fmp4Offset)
        task.audioOutputPath?.let { total += assemble(media.filter { it.track == 1 }, it.toPath(), tracks[1], fmp4Offset) }
        writeSubtitles(finished, subtitleOrigin)

        fs.deleteRecursively(tempDir)
        repository.markCompleted(task.id, total)
    }

    private suspend fun download(segment: SegmentRecord): Long {
        val range = segment.rangeLength?.let { ByteRange(it, segment.rangeOffset ?: 0) }
        val raw = fetcher.fetch(segment.uri, task.headers, range)
        val data = segment.keyUri?.let { keyUri ->
            val key = fetcher.key(keyUri, task.headers)
            val iv = HlsIv.fromHex(segment.ivHex ?: throw IOException("Missing IV for ${segment.uri}"))
            decryptor.decrypt(raw, key, iv)
        } ?: raw

        // Write to .part, then rename: a file named like a finished segment is always complete.
        val file = segmentFile(segment)
        val part = file.parent!! / (file.name + ".part")
        fs.write(part) { write(data) }
        fs.atomicMove(part, file)
        repository.markSegmentDone(task.id, segment, data.size.toLong())
        return data.size.toLong()
    }

    /** Like [download] for a subtitle segment, but a failure only drops that subtitle track. */
    private suspend fun downloadSubtitleSegment(segment: SegmentRecord): Long? =
        if (reportMutex.withLock { segment.track in failedSubtitleTracks }) {
            null
        } else {
            try {
                download(segment)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportMutex.withLock { failedSubtitleTracks[segment.track] = UserMessages.describe(e, strings.downloadFailed) }
                null
            }
        }

    /** First timestamp of a TS video, so subtitle cues line up with the assembled file (players start TS at 0 there). */
    private fun videoStartPts(videoSegments: List<SegmentRecord>): Long? = runCatching {
        val sorted = videoSegments.sortedBy { it.position }
        val firstMedia = sorted.firstOrNull { !it.isInit } ?: return null
        val bytes = fs.read(segmentFile(firstMedia)) { readByteArray() }
        val init = sorted.lastOrNull { it.isInit && it.position < firstMedia.position }
        if (init != null) {
            MediaTimestamps.firstFmp4Pts(fs.read(segmentFile(init)) { readByteArray() }, bytes)
        } else {
            MediaTimestamps.firstTsPts(bytes)
        }
    }.getOrNull()

    private suspend fun writeSubtitles(segments: List<SegmentRecord>, videoStartPts: Long?) {
        for (subtitle in repository.subtitles(task.id).filter { it.kind == SubtitleKind.HLS && it.vttPath == null && it.srtPath == null }) {
            val track = subtitle.track ?: continue
            val parts = segments.filter { it.track == track }.sortedBy { it.position }
            val failure = failedSubtitleTracks[track] ?: if (parts.any { !it.done }) strings.subtitleSegmentsMissing else null
            if (failure != null) {
                repository.markSubtitle(subtitle.id, null, failure)
                continue
            }
            try {
                val texts = parts.map { fs.read(segmentFile(it)) { readUtf8() } }
                val cues = VttSegmentMerger.merge(texts, videoStartPts)
                val written = SubtitleSidecars.write(fs, task.outputPath.toPath(), subtitle.language, cues, subtitleOutput)
                repository.markSubtitle(subtitle.id, written, null)
            } catch (e: Exception) {
                repository.markSubtitle(subtitle.id, null, e.message ?: "Couldn't convert subtitles")
            }
        }
    }

    /**
     * Concatenates a track's segments (init sections included, in order) and verifies the result.
     * For single-init fMP4 ([fmp4] parsed) it also shifts fragment times by [offsetSeconds] and writes
     * a `sidx` after the init section so the file is seekable everywhere.
     */
    private fun assemble(parts: List<SegmentRecord>, output: Path, fmp4: Fmp4Track?, offsetSeconds: Double?): Long {
        val sorted = parts.sortedBy { it.position }
        val shift: (Long, Long) -> Long = { trackId, time ->
            time - if (offsetSeconds == null) 0 else (offsetSeconds * (fmp4?.timescales?.get(trackId) ?: 0)).toLong()
        }
        val index = fmp4?.let { track ->
            val firstTrack = Mp4Boxes.firstTrackId(track.init)
            val infos = track.segmentTimes.mapIndexed { i, times ->
                val time = times.firstOrNull { it.first == firstTrack }?.second ?: return@let null
                Fmp4SegmentIndex.SegmentInfo(track.media[i].size ?: return@let null, shift(firstTrack!!, time))
            }
            Fmp4SegmentIndex.build(track.init, infos, track.media.last().durationSeconds)
        }
        val expected = sorted.sumOf { it.size ?: 0 } + (index?.size ?: 0)
        output.parent?.let(fs::createDirectories)
        val temp = output.parent!! / (output.name + ".part")
        fs.sink(temp).buffer().use { sink ->
            sorted.forEach { segment ->
                if (fmp4 != null && offsetSeconds != null && !segment.isInit) {
                    val bytes = fs.read(segmentFile(segment)) { readByteArray() }
                    Mp4Boxes.rewriteDecodeTimes(bytes, shift)
                    sink.write(bytes)
                } else {
                    fs.source(segmentFile(segment)).use { sink.writeAll(it) }
                }
                if (index != null && segment === sorted.first()) sink.write(index) // right after the init section
            }
        }
        val actual = fs.metadata(temp).size ?: -1
        if (actual <= 0 || actual != expected) {
            fs.delete(temp)
            throw IOException("Verification failed for ${output.name}: expected $expected bytes, wrote $actual")
        }
        if (fs.exists(output)) fs.delete(output)
        fs.atomicMove(temp, output)
        return actual
    }

    /** A single-init fMP4 track: its init section, timescales and every fragment's (track, decode time). */
    private class Fmp4Track(
        val init: ByteArray,
        val timescales: Map<Long, Long>,
        val media: List<SegmentRecord>,
        val segmentTimes: List<List<Triple<Long, Long, Int>>>,
    )

    /** Parses the fragment headers of a track; null for TS, multi-init streams or anything unparseable. */
    private fun readFmp4Track(parts: List<SegmentRecord>): Fmp4Track? = runCatching {
        val sorted = parts.sortedBy { it.position }
        val init = sorted.firstOrNull()?.takeIf { it.isInit } ?: return null
        val media = sorted.drop(1)
        if (media.isEmpty() || media.any { it.isInit }) return null
        val initBytes = fs.read(segmentFile(init)) { readByteArray() }
        val timescales = Mp4Boxes.trackTimescales(initBytes).ifEmpty { return null }
        val times = media.map { segment ->
            val size = segment.size ?: return null
            val head = fs.read(segmentFile(segment)) { readByteArray(minOf(size, INDEX_HEAD_BYTES)) }
            Mp4Boxes.fragmentTimes(head).ifEmpty { return null }
        }
        Fmp4Track(initBytes, timescales, media, times)
    }.getOrNull()

    /**
     * Earliest start (seconds) across the tracks' first fragments, or null when there's nothing to shift
     * or shifting isn't safe (a later fragment starting before it, e.g. a timestamp reset).
     */
    private fun timelineOffsetSeconds(tracks: List<Fmp4Track>): Double? {
        if (tracks.isEmpty()) return null
        fun seconds(track: Fmp4Track, trackId: Long, time: Long): Double =
            track.timescales[trackId]?.let { time.toDouble() / it } ?: Double.NaN
        val offset = tracks.minOf { track -> track.segmentTimes.first().minOf { (id, time, _) -> seconds(track, id, time) } }
        if (offset.isNaN() || offset <= 0.0) return null
        val safe = tracks.all { track -> track.segmentTimes.all { times -> times.all { (id, time, _) -> seconds(track, id, time) >= offset } } }
        return offset.takeIf { safe }
    }

    private fun segmentFile(segment: SegmentRecord): Path = tempDir / "t${segment.track}_${segment.position}.seg"

    private fun checkFreeSpace(neededBytes: Long, doubled: Boolean) {
        if (neededBytes <= 0) return
        val free = storage.freeBytes(storage.downloadsDirectory()) ?: return
        // Segments and the assembled file coexist until verification, so a full download needs ~2x.
        val required = (if (doubled) neededBytes * 2 else neededBytes) + SPACE_MARGIN_BYTES
        if (free < required) throw DownloadRefusedException(strings.notEnoughSpace(required / MB, free / MB))
    }

    private companion object {
        const val MB = 1024 * 1024L
        const val SPACE_MARGIN_BYTES = 50 * MB

        /** Enough of a segment to contain its moof (tfdt) even with long sample tables. */
        const val INDEX_HEAD_BYTES = 256 * 1024L
    }
}
