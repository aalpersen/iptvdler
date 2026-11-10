package io.github.aalpersen.iptvdler.core.player

import kotlinx.coroutines.flow.StateFlow

/** What to play. [headers] apply to every request (playlist, keys, segments). */
data class PlayableMedia(
    val url: String,
    val title: String,
    val headers: Map<String, String> = emptyMap(),
    /** true/false when known (e.g. from a probe); null = let the player guess from the URL. */
    val isHls: Boolean? = null,
    /** External subtitle files (offline playback of downloads). */
    val subtitles: List<SubtitleFile> = emptyList(),
    /** Separate audio file to play in sync with [url] (downloads whose audio wasn't muxed). */
    val externalAudioPath: String? = null,
)

/** A local .srt/.vtt file to load alongside the media. */
data class SubtitleFile(val path: String, val language: String, val label: String)

enum class PlaybackStatus { IDLE, BUFFERING, READY, ENDED, ERROR }

enum class TrackType { VIDEO, AUDIO, SUBTITLE }

data class TrackInfo(
    /** Opaque, implementation-specific identifier. */
    val id: String,
    val type: TrackType,
    val label: String,
    val language: String? = null,
    val bitrate: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
)

data class PlayerState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    /** null when unknown or live. */
    val durationMs: Long? = null,
    val isLive: Boolean = false,
    val isSeekable: Boolean = false,
    val videoTracks: List<TrackInfo> = emptyList(),
    val audioTracks: List<TrackInfo> = emptyList(),
    val subtitleTracks: List<TrackInfo> = emptyList(),
    /** null = automatic (adaptive) video selection. */
    val selectedVideoTrackId: String? = null,
    val selectedAudioTrackId: String? = null,
    /** null = subtitles off. */
    val selectedSubtitleTrackId: String? = null,
    /** Video width / height, when known. */
    val videoAspectRatio: Float? = null,
    val errorMessage: String? = null,
    /** 0..1 */
    val volume: Float = 1f,
    val isMuted: Boolean = false,
)

/**
 * Platform media player (Media3 ExoPlayer on Android, VLCJ on desktop).
 * Must be used from the main thread; [state] may be collected from anywhere.
 */
interface VideoPlayer {
    val state: StateFlow<PlayerState>

    fun load(media: PlayableMedia, playWhenReady: Boolean = true)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun retry()

    /** null = automatic quality. */
    fun selectVideoTrack(trackId: String?)
    fun selectAudioTrack(trackId: String)

    /** null = subtitles off. */
    fun selectSubtitleTrack(trackId: String?)

    /** 0..1 of the player's own volume (the system volume is separate). */
    fun setVolume(volume: Float)
    fun setMuted(muted: Boolean)

    fun release()
}

fun interface VideoPlayerFactory {
    fun create(): VideoPlayer
}

enum class AspectMode { FIT, FILL, STRETCH }
