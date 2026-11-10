package io.github.aalpersen.iptvdler.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import io.github.aalpersen.iptvdler.core.m3u.StreamHeaders
import io.github.aalpersen.iptvdler.core.player.PlayableMedia
import io.github.aalpersen.iptvdler.core.player.PlaybackStatus
import io.github.aalpersen.iptvdler.core.player.PlayerState
import io.github.aalpersen.iptvdler.core.player.TrackInfo
import io.github.aalpersen.iptvdler.core.player.TrackType
import io.github.aalpersen.iptvdler.core.player.VideoPlayer
import io.github.aalpersen.iptvdler.data.network.DEFAULT_USER_AGENT
import io.github.aalpersen.iptvdler.i18n.strings
import java.io.File
import java.util.Locale
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class ExoVideoPlayer(context: Context) : VideoPlayer {

    private val appContext = context.applicationContext

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(appContext)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val scope = MainScope()
    private var current: PlayableMedia? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()

        override fun onPlayerError(error: PlaybackException) {
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                // Fell out of the live window (e.g. after a long pause): jump back to the live edge.
                exoPlayer.seekToDefaultPosition()
                exoPlayer.prepare()
            }
        }
    }

    init {
        exoPlayer.addListener(listener)
        scope.launch {
            // Position isn't evented; poll while playing.
            while (isActive) {
                if (exoPlayer.isPlaying) publish()
                delay(POSITION_POLL_MILLIS)
            }
        }
    }

    override fun load(media: PlayableMedia, playWhenReady: Boolean) {
        current = media
        val headers = media.headers
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(headers[StreamHeaders.USER_AGENT] ?: DEFAULT_USER_AGENT)
            .setDefaultRequestProperties(headers - StreamHeaders.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        // DefaultDataSource adds file:// and content:// support for offline playback.
        val mediaSourceFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(appContext, httpFactory))

        val item = MediaItem.Builder()
            .setUri(media.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(media.title).build())
            .apply { if (media.isHls ?: looksLikeHls(media.url)) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .setSubtitleConfigurations(
                media.subtitles.mapIndexed { index, subtitle ->
                    MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(File(subtitle.path)))
                        .setMimeType(if (subtitle.path.endsWith(".vtt", true)) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP)
                        .setLanguage(subtitle.language)
                        .setLabel(subtitle.label)
                        // The user downloaded these on purpose: show the first one by default.
                        .setSelectionFlags(if (index == 0) C.SELECTION_FLAG_DEFAULT else 0)
                        .build()
                },
            )
            .build()

        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        val videoSource = mediaSourceFactory.createMediaSource(item)
        // A download whose audio was saved separately: play both files in sync.
        val source = media.externalAudioPath?.let { audioPath ->
            MergingMediaSource(videoSource, mediaSourceFactory.createMediaSource(MediaItem.fromUri(Uri.fromFile(File(audioPath)))))
        } ?: videoSource
        exoPlayer.setMediaSource(source)
        exoPlayer.playWhenReady = playWhenReady
        exoPlayer.prepare()
        publish()
    }

    override fun play() {
        if (exoPlayer.playbackState == Player.STATE_ENDED) exoPlayer.seekToDefaultPosition()
        exoPlayer.play()
    }

    override fun pause() = exoPlayer.pause()

    override fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs.coerceAtLeast(0))
        publish()
    }

    override fun retry() {
        val media = current ?: return
        load(media, playWhenReady = true)
    }

    override fun selectVideoTrack(trackId: String?) {
        val builder = exoPlayer.trackSelectionParameters.buildUpon()
        val override = trackId?.let(::overrideFor)
        if (override == null) builder.clearOverridesOfType(C.TRACK_TYPE_VIDEO) else builder.setOverrideForType(override)
        exoPlayer.trackSelectionParameters = builder.build()
    }

    override fun selectAudioTrack(trackId: String) {
        val override = overrideFor(trackId) ?: return
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .setOverrideForType(override)
            .build()
    }

    override fun selectSubtitleTrack(trackId: String?) {
        val builder = exoPlayer.trackSelectionParameters.buildUpon()
        val override = trackId?.let(::overrideFor)
        if (override == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(override)
        }
        exoPlayer.trackSelectionParameters = builder.build()
    }

    private var mutedVolume: Float? = null

    override fun setVolume(volume: Float) {
        mutedVolume = null
        exoPlayer.volume = volume.coerceIn(0f, 1f)
        publish()
    }

    override fun setMuted(muted: Boolean) {
        if (muted && mutedVolume == null) {
            mutedVolume = exoPlayer.volume
            exoPlayer.volume = 0f
        } else if (!muted) {
            mutedVolume?.let { exoPlayer.volume = it }
            mutedVolume = null
        }
        publish()
    }

    override fun release() {
        scope.cancel()
        exoPlayer.removeListener(listener)
        exoPlayer.release()
    }

    /** Track ids are "groupIndex:trackIndex" into [Player.getCurrentTracks]. */
    private fun overrideFor(trackId: String): TrackSelectionOverride? {
        val (groupIndex, trackIndex) = trackId.split(':').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 } ?: return null
        val group = exoPlayer.currentTracks.groups.getOrNull(groupIndex) ?: return null
        if (trackIndex !in 0 until group.length) return null
        return TrackSelectionOverride(group.mediaTrackGroup, trackIndex)
    }

    private fun publish() {
        val player = exoPlayer
        val video = mutableListOf<TrackInfo>()
        val audio = mutableListOf<TrackInfo>()
        val text = mutableListOf<TrackInfo>()
        var selectedAudio: String? = null
        var selectedText: String? = null

        val videoOverride = player.trackSelectionParameters.overrides.values
            .firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
        var selectedVideo: String? = null

        player.currentTracks.groups.forEachIndexed { groupIndex, group ->
            for (trackIndex in 0 until group.length) {
                if (!group.isTrackSupported(trackIndex)) continue
                val format = group.getTrackFormat(trackIndex)
                val id = "$groupIndex:$trackIndex"
                when (group.type) {
                    C.TRACK_TYPE_VIDEO -> {
                        video += TrackInfo(
                            id = id,
                            type = TrackType.VIDEO,
                            label = format.label ?: "Video ${video.size + 1}",
                            bitrate = format.bitrate.takeIf { it > 0 },
                            width = format.width.takeIf { it > 0 },
                            height = format.height.takeIf { it > 0 },
                        )
                        if (videoOverride?.mediaTrackGroup == group.mediaTrackGroup && trackIndex in videoOverride.trackIndices) {
                            selectedVideo = id
                        }
                    }
                    C.TRACK_TYPE_AUDIO -> {
                        audio += TrackInfo(id, TrackType.AUDIO, trackLabel(format, "Audio", audio.size + 1), format.language)
                        if (group.isTrackSelected(trackIndex)) selectedAudio = id
                    }
                    C.TRACK_TYPE_TEXT -> {
                        text += TrackInfo(id, TrackType.SUBTITLE, trackLabel(format, "Subtitles", text.size + 1), format.language)
                        if (group.isTrackSelected(trackIndex)) selectedText = id
                    }
                }
            }
        }

        val error = player.playerError
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val size = player.videoSize
        _state.value = PlayerState(
            status = when {
                error != null -> PlaybackStatus.ERROR
                player.playbackState == Player.STATE_BUFFERING -> PlaybackStatus.BUFFERING
                player.playbackState == Player.STATE_READY -> PlaybackStatus.READY
                player.playbackState == Player.STATE_ENDED -> PlaybackStatus.ENDED
                else -> PlaybackStatus.IDLE
            },
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = if (player.isCurrentMediaItemLive) null else duration,
            isLive = player.isCurrentMediaItemLive,
            isSeekable = player.isCurrentMediaItemSeekable && duration != null,
            videoTracks = video,
            audioTracks = audio,
            subtitleTracks = text,
            selectedVideoTrackId = selectedVideo,
            selectedAudioTrackId = selectedAudio,
            selectedSubtitleTrackId = selectedText,
            videoAspectRatio = if (size.width > 0 && size.height > 0) {
                size.width * size.pixelWidthHeightRatio / size.height
            } else {
                null
            },
            errorMessage = error?.let(::describe),
            volume = mutedVolume ?: player.volume,
            isMuted = mutedVolume != null,
        )
    }

    private fun trackLabel(format: Format, kind: String, number: Int): String =
        format.label
            ?: format.language?.takeIf { it != C.LANGUAGE_UNDETERMINED }?.let { Locale.forLanguageTag(it).displayLanguage.ifBlank { it } }
            ?: "$kind $number"

    private fun describe(error: PlaybackException): String {
        val cause = error.cause
        // Media3 groups error codes by thousands: 2xxx I/O, 3xxx parsing, 4xxx decoding, 6xxx DRM.
        return when {
            cause is HttpDataSource.InvalidResponseCodeException -> strings.playerHttpError(cause.responseCode)
            error.errorCode in 6000..6999 -> strings.playerDrm
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                strings.playerConnection
            error.errorCode in 3000..3999 || error.errorCode in 4000..4999 -> strings.playerFormat
            else -> strings.playerFailed(error.errorCodeName)
        }
    }

    private companion object {
        const val POSITION_POLL_MILLIS = 500L

        fun looksLikeHls(url: String): Boolean {
            val lower = url.lowercase()
            val path = lower.substringBefore('?')
            return path.endsWith(".m3u8") || path.endsWith(".m3u") || "m3u8" in lower.substringAfter('?', "")
        }
    }
}
