package io.github.aalpersen.iptvdler.core.download

import io.github.aalpersen.iptvdler.core.hls.DRM_DOWNLOAD_REFUSED_MESSAGE
import io.github.aalpersen.iptvdler.core.hls.DrmDetector
import io.github.aalpersen.iptvdler.core.hls.HlsIv
import io.github.aalpersen.iptvdler.core.hls.MasterPlaylist
import io.github.aalpersen.iptvdler.core.hls.MediaPlaylist
import io.github.aalpersen.iptvdler.core.hls.Rendition
import io.github.aalpersen.iptvdler.core.hls.Variant
import io.github.aalpersen.iptvdler.core.hls.VariantSelector
import io.github.aalpersen.iptvdler.core.storage.DownloadStorage
import io.github.aalpersen.iptvdler.core.storage.FileNames
import io.github.aalpersen.iptvdler.data.download.DownloadRepository
import io.github.aalpersen.iptvdler.data.download.NewDownload
import io.github.aalpersen.iptvdler.data.download.NewSubtitle
import io.github.aalpersen.iptvdler.data.download.SegmentRecord
import io.github.aalpersen.iptvdler.data.hls.HlsClient
import io.github.aalpersen.iptvdler.i18n.strings
import okio.Path

val LIVE_DOWNLOAD_REFUSED_MESSAGE: String get() = strings.liveDownloadRefused
val NOT_LIVE_RECORDING_MESSAGE: String get() = strings.notLiveRecording

/** Turns a user's choice (stream + quality + audio) into a persisted, ready-to-run download. */
class DownloadPlanner(
    private val hlsClient: HlsClient,
    private val repository: DownloadRepository,
    private val storage: DownloadStorage,
) {
    /**
     * @param master null when the URL was a media playlist itself
     * @param media the media playlist for [variant] if already fetched (avoids a second request)
     */
    suspend fun planHls(
        title: String,
        sourceUrl: String,
        headers: Map<String, String>,
        master: MasterPlaylist?,
        variant: Variant?,
        audio: Rendition?,
        media: MediaPlaylist? = null,
        subtitles: List<Rendition> = emptyList(),
    ): NewDownload {
        val video = media?.takeIf { variant == null || it.uri == variant.uri }
            ?: hlsClient.fetchMedia(variant?.uri ?: sourceUrl, headers)
        val audioMedia = audio?.uri?.let { hlsClient.fetchMedia(it, headers) }

        if (!DrmDetector.checkStream(master, *listOfNotNull(video, audioMedia).toTypedArray()).downloadable) {
            throw DownloadRefusedException(DRM_DOWNLOAD_REFUSED_MESSAGE)
        }
        if (video.isLive || audioMedia?.isLive == true) throw DownloadRefusedException(LIVE_DOWNLOAD_REFUSED_MESSAGE)

        val baseName = FileNames.sanitize(title)
        val videoExtension = if (video.isFragmentedMp4) "mp4" else extensionOf(video, default = "ts")
        val outputPath = uniquePath(baseName, videoExtension)
        val audioPath = audioMedia?.let {
            val ext = if (it.isFragmentedMp4) "m4a" else extensionOf(it, default = "ts")
            uniquePath("$baseName.audio", ext, taken = setOf(outputPath.toString()))
        }

        val subtitleTracks = subtitles.filter { it.uri != null }.mapIndexed { index, rendition ->
            val track = FIRST_SUBTITLE_TRACK + index
            val playlist = hlsClient.fetchMedia(rendition.uri!!, headers)
            NewSubtitle(track, rendition.language ?: "und", rendition.name, rendition.uri) to segmentRecords(playlist, track)
        }
        val segments = segmentRecords(video, track = 0) +
            (audioMedia?.let { segmentRecords(it, track = 1) } ?: emptyList()) +
            subtitleTracks.flatMap { it.second }
        return NewDownload(
            title = title,
            sourceUrl = sourceUrl,
            headers = headers,
            kind = DownloadKind.HLS,
            outputPath = outputPath.toString(),
            audioOutputPath = audioPath?.toString(),
            qualityLabel = variant?.let(::qualityLabel),
            durationSeconds = video.totalDurationSeconds,
            estimatedBytes = variant?.let { VariantSelector.estimateBytes(it, video.totalDurationSeconds) },
            segments = segments,
            subtitles = subtitleTracks.map { it.first },
        )
    }

    suspend fun planDirect(
        title: String,
        sourceUrl: String,
        headers: Map<String, String>,
        contentType: String?,
        contentLength: Long?,
    ): NewDownload {
        if (contentLength == null || contentLength <= 0) throw DownloadRefusedException(LIVE_DOWNLOAD_REFUSED_MESSAGE)
        val ext = directExtension(sourceUrl, contentType)
        return NewDownload(
            title = title,
            sourceUrl = sourceUrl,
            headers = headers,
            kind = DownloadKind.DIRECT,
            outputPath = uniquePath(FileNames.sanitize(title), ext).toString(),
            totalBytes = contentLength,
            estimatedBytes = contentLength,
        )
    }

    /**
     * A live recording. [media] is the variant's live playlist if already fetched. [startedAt] (epoch ms)
     * goes into the file name: "Channel 2026-09-25 20.30.ts".
     */
    suspend fun planHlsRecording(
        title: String,
        sourceUrl: String,
        headers: Map<String, String>,
        master: MasterPlaylist?,
        variant: Variant?,
        audio: Rendition?,
        media: MediaPlaylist?,
        stopAt: Long?,
        startedAt: Long,
    ): NewDownload {
        val videoUri = variant?.uri ?: sourceUrl
        val video = media?.takeIf { variant == null || it.uri == variant.uri } ?: hlsClient.fetchMedia(videoUri, headers)
        val audioMedia = audio?.uri?.let { hlsClient.fetchMedia(it, headers) }
        if (!DrmDetector.checkStream(master, *listOfNotNull(video, audioMedia).toTypedArray()).downloadable) {
            throw DownloadRefusedException(DRM_DOWNLOAD_REFUSED_MESSAGE)
        }
        if (!video.isLive) throw DownloadRefusedException(NOT_LIVE_RECORDING_MESSAGE)

        val baseName = FileNames.sanitize("$title ${RecordingNames.timestamp(startedAt)}")
        val outputPath = uniquePath(baseName, if (video.isFragmentedMp4) "mp4" else extensionOf(video, default = "ts"))
        val audioPath = audioMedia?.let {
            uniquePath("$baseName.audio", if (it.isFragmentedMp4) "m4a" else extensionOf(it, default = "ts"), taken = setOf(outputPath.toString()))
        }
        return NewDownload(
            title = title,
            sourceUrl = sourceUrl,
            headers = headers,
            kind = DownloadKind.HLS_LIVE,
            outputPath = outputPath.toString(),
            audioOutputPath = audioPath?.toString(),
            qualityLabel = variant?.let(::qualityLabel),
            durationSeconds = 0.0,
            stopAt = stopAt,
            variantUri = videoUri,
            audioUri = audio?.uri,
        )
    }

    /** Recording of a continuous stream; a known Content-Length means it's a file, not a live stream. */
    suspend fun planDirectRecording(
        title: String,
        sourceUrl: String,
        headers: Map<String, String>,
        contentType: String?,
        contentLength: Long?,
        stopAt: Long?,
        startedAt: Long,
    ): NewDownload {
        if (contentLength != null && contentLength > 0) throw DownloadRefusedException(NOT_LIVE_RECORDING_MESSAGE)
        val ext = directExtension(sourceUrl, contentType ?: "video/mp2t")
        return NewDownload(
            title = title,
            sourceUrl = sourceUrl,
            headers = headers,
            kind = DownloadKind.DIRECT_LIVE,
            outputPath = uniquePath(FileNames.sanitize("$title ${RecordingNames.timestamp(startedAt)}"), ext).toString(),
            durationSeconds = 0.0,
            stopAt = stopAt,
        )
    }

    private fun segmentRecords(playlist: MediaPlaylist, track: Int): List<SegmentRecord> {
        val records = ArrayList<SegmentRecord>()
        var lastInit: io.github.aalpersen.iptvdler.core.hls.InitSegment? = null
        for (segment in playlist.segments) {
            if (segment.gap) continue
            val init = segment.initSegment
            if (init != null && init != lastInit) {
                val initKey = init.keys.firstOrNull { it.isIdentity && it.method == io.github.aalpersen.iptvdler.core.hls.KeyMethod.AES_128 }
                records += SegmentRecord(
                    track = track,
                    position = records.size,
                    uri = init.uri,
                    rangeOffset = init.byteRange?.offset,
                    rangeLength = init.byteRange?.length,
                    keyUri = initKey?.uri,
                    // No IV on the key: use the sequence number of the first segment the map applies to.
                    ivHex = initKey?.let { hex(HlsIv.forSegment(it, segment.mediaSequence)) },
                    isInit = true,
                )
                lastInit = init
            }
            val key = segment.aes128Key
            records += SegmentRecord(
                track = track,
                position = records.size,
                uri = segment.uri,
                rangeOffset = segment.byteRange?.offset,
                rangeLength = segment.byteRange?.length,
                keyUri = key?.uri,
                ivHex = key?.let { hex(HlsIv.forSegment(it, segment.mediaSequence)) },
                durationSeconds = segment.durationSeconds,
            )
        }
        return records
    }

    private suspend fun uniquePath(baseName: String, extension: String, taken: Set<String> = emptySet()): Path {
        val claimed = repository.claimedPaths() + taken
        return FileNames.unique(storage.downloadsDirectory(), baseName, extension) { path ->
            path.toString() in claimed || storage.fileSystem.exists(path)
        }
    }

    private fun extensionOf(playlist: MediaPlaylist, default: String): String {
        val ext = playlist.segments.firstOrNull()?.uri?.pathExtension()
        return if (ext in setOf("aac", "mp3", "ac3", "ec3")) ext!! else default
    }

    private fun qualityLabel(variant: Variant): String =
        variant.resolution?.let { "${it.height}p" } ?: "${variant.bandwidth / 1000} kbps"

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    companion object {
        /** Tracks 0 (video/main) and 1 (separate audio) are media; subtitle renditions start here. */
        const val FIRST_SUBTITLE_TRACK = 2

        private val directExtensions = setOf("mp4", "mkv", "avi", "mov", "m4v", "webm", "flv", "ts", "mpg", "mpeg", "mp3", "aac", "m4a")

        fun directExtension(url: String, contentType: String?): String {
            url.pathExtension()?.takeIf { it in directExtensions }?.let { return it }
            return when (contentType?.substringBefore(';')?.trim()?.lowercase()) {
                "video/x-matroska" -> "mkv"
                "video/webm" -> "webm"
                "video/mp2t" -> "ts"
                "video/x-msvideo" -> "avi"
                "video/quicktime" -> "mov"
                "audio/mpeg" -> "mp3"
                else -> "mp4"
            }
        }

        private fun String.pathExtension(): String? =
            substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "").lowercase().ifEmpty { null }
    }
}
