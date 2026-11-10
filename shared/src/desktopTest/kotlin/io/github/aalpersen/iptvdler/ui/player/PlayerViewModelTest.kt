package io.github.aalpersen.iptvdler.ui.player

import io.github.aalpersen.iptvdler.FakeVideoPlayer
import io.github.aalpersen.iptvdler.TestEnvironment
import io.github.aalpersen.iptvdler.core.player.PlayableMedia
import io.github.aalpersen.iptvdler.data.hls.HlsClient
import io.github.aalpersen.iptvdler.readFixture
import io.ktor.http.HttpStatusCode
import io.github.aalpersen.iptvdler.core.model.PlaylistSourceType
import io.github.aalpersen.iptvdler.data.playlist.ChannelFilter
import io.github.aalpersen.iptvdler.data.playlist.PlaylistInput
import io.github.aalpersen.iptvdler.data.playlist.PlaylistRefresher
import io.github.aalpersen.iptvdler.data.playlist.PlaylistRepository
import io.github.aalpersen.iptvdler.ui.navigation.PlayerRoute
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {

    private val env = TestEnvironment()
    private val repository = PlaylistRepository(env.database, Dispatchers.IO, env.clock)
    private val downloads = io.github.aalpersen.iptvdler.data.download.DownloadRepository(env.database, Dispatchers.IO, env.clock)
    private val refresher =
        PlaylistRefresher(env.database, env.httpClient, env.localFiles, Dispatchers.IO, CoroutineScope(Dispatchers.IO), env.clock)

    init {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun loadsChannelWithItsHeadersIntoThePlayer() = runBlocking {
        val playlistId = repository.addPlaylist(PlaylistInput("H", "headers.m3u", PlaylistSourceType.FILE, null))
        refresher.refreshNow(playlistId)
        val channel = repository.observeChannels(ChannelFilter(playlistId, search = "VLC Options"), 1).first().single()
        val player = FakeVideoPlayer()

        PlayerViewModel(PlayerRoute(channel.id), repository, { player }, HlsClient(env.httpClient), downloads)

        withTimeout(5_000) { while (player.loaded.isEmpty()) kotlinx.coroutines.delay(10) }
        val media = player.loaded.single()
        assertEquals("http://stream.example.test/vlc.m3u8", media.url)
        assertEquals("VLC Options", media.title)
        assertEquals(
            mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0) Test", "Referer" to "https://ref.example.test/"),
            media.headers,
        )
        assertNull(media.isHls, "URL has an .m3u8 extension; no probe needed")
    }

    @Test
    fun probesExtensionlessUrlsToDetectHls() = runBlocking {
        env.responses["/live/u/p/1001"] = HttpStatusCode.OK to readFixture("hls/media_live.m3u8")
        env.responses["/live/u/p/1002"] = HttpStatusCode.OK to "G" + "x".repeat(5000)
        env.files["list.m3u"] = """
            #EXTM3U
            #EXTINF:-1,Hls Channel
            ${TestEnvironment.BASE_URL}/live/u/p/1001
            #EXTINF:-1,Ts Channel
            ${TestEnvironment.BASE_URL}/live/u/p/1002
        """.trimIndent()
        val playlistId = repository.addPlaylist(PlaylistInput("L", "list.m3u", PlaylistSourceType.FILE, null))
        refresher.refreshNow(playlistId)

        suspend fun loadedFor(name: String): PlayableMedia {
            val channel = repository.observeChannels(ChannelFilter(playlistId, search = name), 1).first().single()
            val player = FakeVideoPlayer()
            PlayerViewModel(PlayerRoute(channel.id), repository, { player }, HlsClient(env.httpClient), downloads)
            withTimeout(5_000) { while (player.loaded.isEmpty()) kotlinx.coroutines.delay(10) }
            return player.loaded.single()
        }

        assertEquals(true, loadedFor("Hls Channel").isHls)
        assertEquals(false, loadedFor("Ts Channel").isHls)
    }

    @Test
    fun offlinePlaybackLoadsTheFileAndItsSubtitleSidecars() = runBlocking {
        val id = downloads.create(
            io.github.aalpersen.iptvdler.data.download.NewDownload(
                title = "Movie",
                sourceUrl = "https://vod.test/m.mp4",
                headers = emptyMap(),
                kind = io.github.aalpersen.iptvdler.core.download.DownloadKind.DIRECT,
                outputPath = "/downloads/Movie.mp4",
                audioOutputPath = "/downloads/Movie.audio.m4a",
            ),
        )
        downloads.addStandaloneSubtitle(
            id, "en", "English", "https://subs.test/en.srt",
            io.github.aalpersen.iptvdler.core.subtitle.WrittenSidecars(vttPath = "/downloads/Movie.en.vtt", srtPath = "/downloads/Movie.en.srt"),
        )
        downloads.addStandaloneSubtitle(
            id, "de", "de", "https://subs.test/de.vtt",
            io.github.aalpersen.iptvdler.core.subtitle.WrittenSidecars(vttPath = "/downloads/Movie.de.vtt", srtPath = null),
        )
        val player = FakeVideoPlayer()

        PlayerViewModel(PlayerRoute(downloadId = id), repository, { player }, HlsClient(env.httpClient), downloads)

        withTimeout(5_000) { while (player.loaded.isEmpty()) kotlinx.coroutines.delay(10) }
        val media = player.loaded.single()
        assertEquals("/downloads/Movie.mp4", media.url)
        assertEquals(false, media.isHls)
        assertEquals("/downloads/Movie.audio.m4a", media.externalAudioPath, "separately saved audio plays along")
        assertEquals(
            listOf(
                io.github.aalpersen.iptvdler.core.player.SubtitleFile("/downloads/Movie.en.srt", "en", "English"),
                io.github.aalpersen.iptvdler.core.player.SubtitleFile("/downloads/Movie.de.vtt", "de", "de"),
            ),
            media.subtitles,
        )
    }

    @Test
    fun recognisesMediaExtensions() {
        assertTrue(hasKnownExtension("http://h.test/live/u/p/1.ts"))
        assertTrue(hasKnownExtension("http://h.test/a/index.M3U8?token=1"))
        assertFalse(hasKnownExtension("http://h.test/live/u/p/1001"))
        assertFalse(hasKnownExtension("http://h.test/play.php?id=5"))
    }
}
