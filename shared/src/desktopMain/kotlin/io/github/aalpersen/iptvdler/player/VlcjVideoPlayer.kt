package io.github.aalpersen.iptvdler.player

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import io.github.aalpersen.iptvdler.core.m3u.StreamHeaders
import io.github.aalpersen.iptvdler.core.player.PlayableMedia
import io.github.aalpersen.iptvdler.core.player.PlaybackStatus
import io.github.aalpersen.iptvdler.core.player.PlayerState
import io.github.aalpersen.iptvdler.core.player.TrackInfo
import io.github.aalpersen.iptvdler.core.player.TrackType
import io.github.aalpersen.iptvdler.core.player.UnavailableVideoPlayer
import io.github.aalpersen.iptvdler.core.player.VideoPlayer
import io.github.aalpersen.iptvdler.core.player.VideoPlayerFactory
import io.github.aalpersen.iptvdler.data.network.DEFAULT_USER_AGENT
import io.github.aalpersen.iptvdler.i18n.strings
import java.nio.ByteBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.media.MediaSlaveType
import uk.co.caprica.vlcj.media.TrackType as VlcTrackType
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.base.TrackDescription
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat

val VLC_MISSING_MESSAGE: String get() = strings.vlcMissing

val VLC_32_BIT_MESSAGE: String get() = strings.vlc32Bit

/** Explains why libVLC couldn't be loaded, with the one case we can detect precisely. */
internal fun vlcMissingMessage(): String {
    val programFilesX86 = System.getenv("ProgramFiles(x86)") ?: return VLC_MISSING_MESSAGE
    val has32Bit = java.io.File(programFilesX86, "VideoLAN/VLC/libvlc.dll").exists()
    return if (has32Bit) VLC_32_BIT_MESSAGE else VLC_MISSING_MESSAGE
}

/** Creates VLCJ players, or [UnavailableVideoPlayer] when libVLC can't be found. */
class VlcjVideoPlayerFactory : VideoPlayerFactory {
    private val mediaPlayerFactory: MediaPlayerFactory? by lazy {
        useUtf8ForNativeStrings()
        runCatching {
            if (NativeDiscovery().discover()) MediaPlayerFactory("--quiet", "--no-video-title-show") else null
        }.getOrNull()
    }

    override fun create(): VideoPlayer =
        mediaPlayerFactory?.let(::VlcjVideoPlayer) ?: UnavailableVideoPlayer(vlcMissingMessage())
}

/**
 * libVLC exchanges all strings as UTF-8, but JNA defaults to the OS code page (e.g. Cp1254 on Turkish
 * Windows), which turns "Türkçe" track names into "TÃ¼rkÃ§e". Must run before JNA's Native class loads;
 * the desktop app also passes -Djna.encoding=UTF-8 on the command line.
 */
fun useUtf8ForNativeStrings() {
    if (System.getProperty("jna.encoding") == null) System.setProperty("jna.encoding", "UTF-8")
}

/** `file:///C:/...` (java.io.File.toURI gives `file:/C:/...`, which VLC's input-slave doesn't accept). */
private fun fileMrl(path: String): String = java.io.File(path).toPath().toUri().toString()

/** A decoded frame; [generation] changes on every frame so Compose redraws. */
class VideoFrame(val image: ImageBitmap, val generation: Long)

/**
 * libVLC player that renders into memory (RV32 callbacks) and exposes frames as Compose images,
 * so iptvdler's Compose controls can be drawn over the video without heavyweight Swing interop.
 *
 * Limitation: VLC only supports the User-Agent and Referer headers per stream.
 */
class VlcjVideoPlayer(factory: MediaPlayerFactory) : VideoPlayer {

    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _frames = MutableStateFlow<VideoFrame?>(null)
    val frames: StateFlow<VideoFrame?> = _frames.asStateFlow()

    private val mediaPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer()
    private var current: PlayableMedia? = null

    // Two bitmaps so VLC writes one while Compose draws the other.
    private val bitmaps = arrayOf(Bitmap(), Bitmap())
    private var frameInfo: ImageInfo? = null
    private var pixels = ByteArray(0)
    private var generation = 0L

    private val bufferFormatCallback = object : BufferFormatCallback {
        override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
            frameInfo = ImageInfo(sourceWidth, sourceHeight, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
            pixels = ByteArray(sourceWidth * sourceHeight * 4)
            bitmaps.forEach { it.allocPixels(frameInfo!!) }
            _state.update { it.copy(videoAspectRatio = sourceWidth.toFloat() / sourceHeight) }
            return RV32BufferFormat(sourceWidth, sourceHeight)
        }

        override fun newFormatSize(bufferWidth: Int, bufferHeight: Int, displayWidth: Int, displayHeight: Int) {
            if (displayWidth > 0 && displayHeight > 0) {
                _state.update { it.copy(videoAspectRatio = displayWidth.toFloat() / displayHeight) }
            }
        }

        override fun allocatedBuffers(buffers: Array<out ByteBuffer>) = Unit
    }

    private val renderCallback = object : RenderCallback {
        override fun lock(mediaPlayer: MediaPlayer) = Unit
        override fun unlock(mediaPlayer: MediaPlayer) = Unit

        override fun display(
            mediaPlayer: MediaPlayer,
            nativeBuffers: Array<out ByteBuffer>,
            bufferFormat: BufferFormat,
            displayWidth: Int,
            displayHeight: Int,
        ) {
            val info = frameInfo ?: return
            val buffer = nativeBuffers[0]
            buffer.rewind()
            if (buffer.remaining() < pixels.size) return
            buffer.get(pixels)
            val target = bitmaps[(generation % 2).toInt()]
            target.installPixels(info, pixels, info.minRowBytes)
            generation++
            _frames.value = VideoFrame(target.asComposeImageBitmap(), generation)
        }
    }

    // Called on libVLC's event thread: only update state here, and hop to the player's
    // own thread (submit) for anything that calls back into libVLC.
    private val events = object : MediaPlayerEventAdapter() {
        override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) {
            _state.update {
                if (it.status == PlaybackStatus.ERROR) it
                else it.copy(status = if (newCache < 100f) PlaybackStatus.BUFFERING else PlaybackStatus.READY)
            }
        }

        override fun playing(mediaPlayer: MediaPlayer) {
            _state.update { it.copy(status = PlaybackStatus.READY, isPlaying = true, errorMessage = null) }
            mediaPlayer.submit {
                // libVLC ignores volume changes until audio output exists: re-apply the user's choice.
                mediaPlayer.audio().setVolume((_state.value.volume * 100).toInt())
                mediaPlayer.audio().isMute = _state.value.isMuted
                refreshTimeline()
                refreshTracks()
            }
        }

        override fun paused(mediaPlayer: MediaPlayer) = _state.update { it.copy(isPlaying = false) }

        override fun stopped(mediaPlayer: MediaPlayer) = _state.update { it.copy(isPlaying = false) }

        override fun finished(mediaPlayer: MediaPlayer) =
            _state.update { it.copy(status = PlaybackStatus.ENDED, isPlaying = false) }

        override fun error(mediaPlayer: MediaPlayer) = _state.update {
            it.copy(status = PlaybackStatus.ERROR, isPlaying = false, errorMessage = "VLC couldn't play this stream.")
        }

        override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) =
            _state.update { it.copy(positionMs = newTime.coerceAtLeast(0)) }

        override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
            mediaPlayer.submit { refreshTimeline() }
        }

        override fun seekableChanged(mediaPlayer: MediaPlayer, newSeekable: Int) {
            mediaPlayer.submit { refreshTimeline() }
        }

        override fun elementaryStreamAdded(mediaPlayer: MediaPlayer, type: VlcTrackType, id: Int) {
            mediaPlayer.submit { refreshTracks() }
        }

        override fun elementaryStreamDeleted(mediaPlayer: MediaPlayer, type: VlcTrackType, id: Int) {
            mediaPlayer.submit { refreshTracks() }
        }

        override fun elementaryStreamSelected(mediaPlayer: MediaPlayer, type: VlcTrackType, id: Int) {
            mediaPlayer.submit { refreshTracks() }
        }
    }

    init {
        mediaPlayer.videoSurface().set(
            factory.videoSurfaces().newVideoSurface(bufferFormatCallback, renderCallback, true),
        )
        mediaPlayer.events().addMediaPlayerEventListener(events)
    }

    override fun load(media: PlayableMedia, playWhenReady: Boolean) {
        current = media
        _frames.value = null
        // Keep the user's volume across channel changes.
        _state.value = PlayerState(status = PlaybackStatus.BUFFERING, volume = _state.value.volume, isMuted = _state.value.isMuted)
        val options = buildList {
            add(":http-user-agent=${media.headers[StreamHeaders.USER_AGENT] ?: DEFAULT_USER_AGENT}")
            media.headers[StreamHeaders.REFERER]?.let { add(":http-referrer=$it") }
            add(":network-caching=1500")
            // A separately saved audio track must be attached when the media opens (add_slave is too late for audio).
            media.externalAudioPath?.let { add(":input-slave=${fileMrl(it)}") }
        }.toTypedArray()
        val started = if (playWhenReady) {
            mediaPlayer.media().play(media.url, *options)
        } else {
            mediaPlayer.media().startPaused(media.url, *options)
        }
        if (!started) {
            _state.value = PlayerState(status = PlaybackStatus.ERROR, errorMessage = "VLC couldn't open this stream.")
            return
        }
        // Show the first downloaded subtitle file by default; the rest are selectable.
        media.subtitles.forEachIndexed { index, subtitle ->
            mediaPlayer.media().addSlave(MediaSlaveType.SUBTITLE, fileMrl(subtitle.path), index == 0)
        }
    }

    override fun play() {
        if (_state.value.status == PlaybackStatus.ENDED) {
            current?.let { load(it) }
        } else {
            mediaPlayer.controls().play()
        }
    }

    override fun pause() = mediaPlayer.controls().setPause(true)

    override fun seekTo(positionMs: Long) {
        mediaPlayer.controls().setTime(positionMs.coerceAtLeast(0))
        _state.update { it.copy(positionMs = positionMs.coerceAtLeast(0)) }
    }

    override fun retry() {
        current?.let { load(it) }
    }

    /** VLC picks HLS variants itself; its "video tracks" are elementary streams, not qualities. */
    override fun selectVideoTrack(trackId: String?) {
        trackId?.toIntOrNull()?.let { mediaPlayer.video().setTrack(it) }
    }

    override fun selectAudioTrack(trackId: String) {
        trackId.toIntOrNull()?.let { mediaPlayer.audio().setTrack(it) }
    }

    override fun selectSubtitleTrack(trackId: String?) {
        mediaPlayer.subpictures().setTrack(trackId?.toIntOrNull() ?: -1)
    }

    override fun setVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        mediaPlayer.audio().setVolume((v * 100).toInt())
        _state.update { it.copy(volume = v) }
    }

    override fun setMuted(muted: Boolean) {
        mediaPlayer.audio().isMute = muted
        _state.update { it.copy(isMuted = muted) }
    }

    override fun release() {
        mediaPlayer.events().removeMediaPlayerEventListener(events)
        mediaPlayer.controls().stop()
        mediaPlayer.release()
        _frames.value = null
    }

    private fun refreshTimeline() {
        val length = mediaPlayer.status().length()
        val seekable = mediaPlayer.status().isSeekable
        _state.update {
            it.copy(
                durationMs = length.takeIf { l -> l > 0 },
                isLive = length <= 0,
                isSeekable = seekable && length > 0,
            )
        }
    }

    private fun refreshTracks() {
        fun List<TrackDescription>.toTracks(type: TrackType): List<TrackInfo> {
            val tracks = filter { it.id() >= 0 }
            val labels = VlcTrackLabels.clean(tracks.map { it.description() ?: "Track ${it.id()}" })
            return tracks.mapIndexed { i, track -> TrackInfo(track.id().toString(), type, labels[i]) }
        }

        val video = mediaPlayer.video().trackDescriptions().toTracks(TrackType.VIDEO)
        val audio = mediaPlayer.audio().trackDescriptions().toTracks(TrackType.AUDIO)
        val subtitles = mediaPlayer.subpictures().trackDescriptions().toTracks(TrackType.SUBTITLE)
        val selectedAudio = mediaPlayer.audio().track().takeIf { it >= 0 }?.toString()
        val selectedSubtitle = mediaPlayer.subpictures().track().takeIf { it >= 0 }?.toString()
        _state.update {
            it.copy(
                videoTracks = video,
                audioTracks = audio,
                subtitleTracks = subtitles,
                selectedAudioTrackId = selectedAudio,
                selectedSubtitleTrackId = selectedSubtitle,
            )
        }
    }
}
