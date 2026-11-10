package io.github.aalpersen.iptvdler

import io.github.aalpersen.iptvdler.core.player.PlayableMedia
import io.github.aalpersen.iptvdler.core.player.PlaybackStatus
import io.github.aalpersen.iptvdler.core.player.PlayerState
import io.github.aalpersen.iptvdler.core.player.TrackInfo
import io.github.aalpersen.iptvdler.core.player.TrackType
import io.github.aalpersen.iptvdler.core.player.VideoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Records calls and reports a paused VOD stream with a few tracks once loaded. */
class FakeVideoPlayer : VideoPlayer {
    val loaded = mutableListOf<PlayableMedia>()
    var released = false
        private set

    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state

    override fun load(media: PlayableMedia, playWhenReady: Boolean) {
        loaded += media
        _state.value = PlayerState(
            status = PlaybackStatus.READY,
            isPlaying = false,
            positionMs = 754_000,
            durationMs = 5_400_000,
            isSeekable = true,
            videoTracks = listOf(
                TrackInfo("v1", TrackType.VIDEO, "Low", bitrate = 800_000, width = 640, height = 360),
                TrackInfo("v2", TrackType.VIDEO, "Mid", bitrate = 2_500_000, width = 1280, height = 720),
                TrackInfo("v3", TrackType.VIDEO, "High", bitrate = 5_200_000, width = 1920, height = 1080),
            ),
            audioTracks = listOf(
                TrackInfo("a1", TrackType.AUDIO, "English", "en"),
                TrackInfo("a2", TrackType.AUDIO, "Türkçe", "tr"),
            ),
            subtitleTracks = listOf(TrackInfo("s1", TrackType.SUBTITLE, "English", "en")),
            selectedAudioTrackId = "a1",
            videoAspectRatio = 16f / 9,
        )
    }

    override fun play() = _state.update { it.copy(isPlaying = true) }
    override fun pause() = _state.update { it.copy(isPlaying = false) }
    override fun seekTo(positionMs: Long) = _state.update { it.copy(positionMs = positionMs) }
    override fun retry() = Unit
    override fun selectVideoTrack(trackId: String?) = _state.update { it.copy(selectedVideoTrackId = trackId) }
    override fun selectAudioTrack(trackId: String) = _state.update { it.copy(selectedAudioTrackId = trackId) }
    override fun selectSubtitleTrack(trackId: String?) = _state.update { it.copy(selectedSubtitleTrackId = trackId) }
    override fun setVolume(volume: Float) = _state.update { it.copy(volume = volume.coerceIn(0f, 1f)) }
    override fun setMuted(muted: Boolean) = _state.update { it.copy(isMuted = muted) }

    override fun release() {
        released = true
    }
}
