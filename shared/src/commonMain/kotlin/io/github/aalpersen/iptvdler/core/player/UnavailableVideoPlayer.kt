package io.github.aalpersen.iptvdler.core.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Stand-in used when the platform can't play video at all (e.g. VLC missing on desktop). */
class UnavailableVideoPlayer(message: String) : VideoPlayer {
    override val state: StateFlow<PlayerState> =
        MutableStateFlow(PlayerState(status = PlaybackStatus.ERROR, errorMessage = message))

    override fun load(media: PlayableMedia, playWhenReady: Boolean) = Unit
    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun retry() = Unit
    override fun selectVideoTrack(trackId: String?) = Unit
    override fun selectAudioTrack(trackId: String) = Unit
    override fun selectSubtitleTrack(trackId: String?) = Unit
    override fun setVolume(volume: Float) = Unit
    override fun setMuted(muted: Boolean) = Unit
    override fun release() = Unit
}
