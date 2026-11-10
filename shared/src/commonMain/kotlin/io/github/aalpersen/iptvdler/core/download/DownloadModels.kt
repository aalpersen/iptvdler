package io.github.aalpersen.iptvdler.core.download

enum class DownloadKind {
    HLS,
    DIRECT,

    /** Recording of a live HLS stream (polls the media playlist). */
    HLS_LIVE,

    /** Recording of a continuous stream (e.g. an IPTV .ts channel URL). */
    DIRECT_LIVE,
    ;

    val isRecording: Boolean get() = this == HLS_LIVE || this == DIRECT_LIVE
}

enum class DownloadStatus { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED }

data class DownloadTask(
    val id: Long,
    val title: String,
    val sourceUrl: String,
    val headers: Map<String, String>,
    val kind: DownloadKind,
    val status: DownloadStatus,
    val outputPath: String,
    /** Separate audio file saved next to the video (not muxed). */
    val audioOutputPath: String?,
    val qualityLabel: String?,
    val durationSeconds: Double?,
    val totalBytes: Long?,
    val estimatedBytes: Long?,
    val downloadedBytes: Long,
    val totalSegments: Int,
    val completedSegments: Int,
    val error: String?,
    val createdAt: Long,
    val completedAt: Long?,
    /** Recordings: stop time (epoch ms); null = until stopped manually. */
    val stopAt: Long? = null,
    /** Recordings (HLS): the live media playlists being polled. */
    val variantUri: String? = null,
    val audioUri: String? = null,
) {
    val isRecording: Boolean get() = kind.isRecording

    val hasSeparateAudio: Boolean get() = audioOutputPath != null
}

enum class SubtitleKind { HLS, FILE }

/** A subtitle sidecar of a download. Paths are null until written; [error] set if it couldn't be made. */
data class DownloadSubtitle(
    val id: Long,
    val taskId: Long,
    /** Segment track holding its WebVTT segments (HLS renditions only). */
    val track: Int?,
    val language: String,
    val name: String,
    val kind: SubtitleKind,
    val vttPath: String?,
    val srtPath: String?,
    val error: String?,
) {
    /** The file to hand to a player (SRT preferred: widest support). */
    val playablePath: String? get() = srtPath ?: vttPath
}

/** Live numbers for a running task (not persisted). */
data class DownloadProgress(
    val downloadedBytes: Long,
    /** Known or estimated total; null when unknown. */
    val totalBytes: Long?,
    val completedSegments: Int = 0,
    val totalSegments: Int = 0,
    val bytesPerSecond: Long = 0,
    val etaSeconds: Long? = null,
    /** e.g. "Waiting for Wi-Fi", "Assembling", "Retrying…" */
    val note: String? = null,
) {
    /** 0..1, or null when the total is unknown. */
    val fraction: Float?
        get() = when {
            totalSegments > 0 -> completedSegments.toFloat() / totalSegments
            totalBytes != null && totalBytes > 0 -> (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
            else -> null
        }
}

/** Download refused before it starts (DRM, live stream, no space...). */
class DownloadRefusedException(message: String) : Exception(message)
