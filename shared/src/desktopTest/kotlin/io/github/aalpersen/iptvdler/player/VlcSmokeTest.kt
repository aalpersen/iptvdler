package io.github.aalpersen.iptvdler.player

import androidx.compose.ui.graphics.toAwtImage
import io.github.aalpersen.iptvdler.core.player.PlayableMedia
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Plays a public test stream through the locally installed VLC. Needs VLC and network, so it only
 * runs with `-Piptvdler.vlcSmoke=true`. Saves a decoded frame to build/screenshots/vlc-frame.png.
 */
class VlcSmokeTest {

    /** Uses the Apple download made by RealDownloadSmokeTest.appleFmp4WithEnglishSubtitles, if present. */
    @Test
    fun playsDownloadedFileWithSidecarSubtitles() {
        if (System.getProperty("iptvdler.vlcSmoke") != "true") return
        val video = File("build/smoke-downloads/apple-fmp4-subs.mp4")
        val srt = File("build/smoke-downloads/apple-fmp4-subs.en.srt")
        if (!video.exists() || !srt.exists()) return

        val player = VlcjVideoPlayerFactory().create() as VlcjVideoPlayer
        try {
            player.load(
                PlayableMedia(
                    url = video.absolutePath,
                    title = "Offline",
                    isHls = false,
                    subtitles = listOf(io.github.aalpersen.iptvdler.core.player.SubtitleFile(srt.absolutePath, "en", "English")),
                    // The Apple stream's audio was a separate rendition, saved next to the video.
                    externalAudioPath = File("build/smoke-downloads/apple-fmp4-subs.audio.m4a").takeIf { it.exists() }?.absolutePath,
                ),
            )
            player.setVolume(0.4f)
            runBlocking {
                withTimeout(20_000) { player.state.first { it.isPlaying } }
                val ready = kotlinx.coroutines.withTimeoutOrNull(20_000) {
                    player.state.first { it.subtitleTracks.isNotEmpty() && it.audioTracks.isNotEmpty() }
                }
                if (ready == null) println("VLC offline DEBUG state: ${player.state.value}")
                kotlin.test.assertNotNull(ready)
            }
            println("VLC offline subtitle tracks: ${player.state.value.subtitleTracks} audio: ${player.state.value.audioTracks}")
            assertEquals(0.4f, player.state.value.volume)
            player.selectSubtitleTrack(player.state.value.subtitleTracks.first().id)
            runBlocking { withTimeout(10_000) { player.state.first { it.selectedSubtitleTrackId != null } } }
        } finally {
            player.release()
        }
    }

    /**
     * Non-ASCII both ways: a Turkish file name passed to libVLC and a Turkish track name coming back.
     * Needs the local ffmpeg stream download from RealDownloadSmokeTest (local-fmp4.mp4).
     */
    @Test
    fun handlesNonAsciiPathsAndTrackNames() {
        if (System.getProperty("iptvdler.vlcSmoke") != "true") return
        val source = File("build/smoke-downloads/local-fmp4.mp4")
        if (!source.exists()) return
        val dir = File("build/smoke-unicode").apply { mkdirs() }
        val video = File(dir, "Türkçe Şarkı İçin.mp4").also { source.copyTo(it, overwrite = true) }
        val srt = File(dir, "Türkçe Şarkı İçin.tr.srt").apply {
            writeText("1\n00:00:00,500 --> 00:00:05,000\nMerhaba dünya, ığüşöç İĞÜŞÖÇ\n")
        }

        val player = VlcjVideoPlayerFactory().create() as VlcjVideoPlayer
        try {
            player.load(
                PlayableMedia(
                    url = video.absolutePath,
                    title = "Unicode",
                    isHls = false,
                    subtitles = listOf(io.github.aalpersen.iptvdler.core.player.SubtitleFile(srt.absolutePath, "tr", "Türkçe")),
                ),
            )
            runBlocking {
                withTimeout(20_000) { player.state.first { it.isPlaying || it.errorMessage != null } }
                withTimeout(20_000) { player.state.first { it.subtitleTracks.isNotEmpty() || it.errorMessage != null } }
            }
            val state = player.state.value
            println("VLC unicode: error=${state.errorMessage} labels=" + (state.audioTracks + state.subtitleTracks).joinToString { t -> t.label.map { c -> "U+%04X".format(c.code) }.joinToString(" ") })
            assertEquals(null, state.errorMessage, "VLC must open a file whose path has Turkish characters")
            val labels = (state.audioTracks + state.subtitleTracks).map { it.label }
            assertTrue(labels.none { "Ã" in it || "Ä" in it || "Å" in it }, "labels must be decoded as UTF-8: $labels")
        } finally {
            player.release()
        }
    }

    @Test
    fun playsAndSeeksPublicHlsStream() {
        if (System.getProperty("iptvdler.vlcSmoke") != "true") return

        val player = VlcjVideoPlayerFactory().create()
        check(player is VlcjVideoPlayer) { VLC_MISSING_MESSAGE }
        try {
            player.load(
                PlayableMedia(
                    url = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                    title = "Big Buck Bunny",
                    headers = mapOf("User-Agent" to "iptvdler smoke test"),
                ),
            )
            runBlocking {
                withTimeout(45_000) { player.frames.first { (it?.generation ?: 0) > 50 } }
                withTimeout(10_000) { player.state.first { it.isPlaying && it.durationMs != null } }
                println("VLC state after start: ${player.state.value}")

                player.seekTo(120_000)
                withTimeout(20_000) { player.state.first { it.positionMs >= 120_000 } }

                player.pause()
                withTimeout(5_000) { player.state.first { !it.isPlaying } }
            }

            val state = player.state.value
            assertTrue(state.isSeekable, "VOD stream should be seekable")
            assertTrue((state.durationMs ?: 0) > 500_000, "Big Buck Bunny is ~10 minutes")
            assertTrue((state.videoAspectRatio ?: 0f) > 1.5f)

            val frame = player.frames.value!!.image.toAwtImage()
            val dir = File("build/screenshots").apply { mkdirs() }
            ImageIO.write(frame, "png", File(dir, "vlc-frame.png"))
        } finally {
            player.release()
        }
    }
}
