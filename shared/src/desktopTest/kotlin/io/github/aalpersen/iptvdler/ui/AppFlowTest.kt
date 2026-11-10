package io.github.aalpersen.iptvdler.ui

import io.github.aalpersen.iptvdler.di.bindLanguage
import io.github.aalpersen.iptvdler.i18n.AppLanguage
import io.github.aalpersen.iptvdler.i18n.I18n
import kotlinx.coroutines.cancel
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.captureToImage
import io.github.aalpersen.iptvdler.FakeVideoPlayer
import io.github.aalpersen.iptvdler.core.download.DownloadForegroundController
import io.github.aalpersen.iptvdler.core.download.DownloadManager
import io.github.aalpersen.iptvdler.core.download.FakeDownloadStorage
import io.github.aalpersen.iptvdler.core.storage.DownloadStorage
import io.github.aalpersen.iptvdler.core.storage.NetworkMonitor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import okio.Path.Companion.toPath
import kotlin.test.assertContentEquals
import io.github.aalpersen.iptvdler.TestEnvironment
import io.github.aalpersen.iptvdler.core.player.VideoPlayerFactory
import io.github.aalpersen.iptvdler.core.model.EpochClock
import io.github.aalpersen.iptvdler.core.model.PlaylistSourceType
import io.github.aalpersen.iptvdler.data.db.IptvdlerDatabase
import io.github.aalpersen.iptvdler.data.playlist.LocalFileSource
import io.github.aalpersen.iptvdler.data.playlist.PlaylistInput
import io.github.aalpersen.iptvdler.data.playlist.PlaylistRefresher
import io.github.aalpersen.iptvdler.data.playlist.PlaylistRepository
import io.github.aalpersen.iptvdler.di.dataModule
import io.github.aalpersen.iptvdler.di.viewModelModule
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class AppFlowTest {

    private val env = TestEnvironment()
    private val players = mutableListOf<FakeVideoPlayer>()
    private val storage = FakeDownloadStorage()

    init {
        // Clicks run on the test thread; make it the "main" thread that Lifecycle/Navigation insist on.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    private val koin = startKoin {
        modules(
            dataModule,
            viewModelModule,
            // Test doubles override the real bindings from dataModule.
            module {
                single<Settings> { MapSettings() }
                single<IptvdlerDatabase> { env.database }
                single<LocalFileSource> { env.localFiles }
                single<HttpClient> { env.httpClient }
                single<EpochClock> { env.clock }
                single<VideoPlayerFactory> { VideoPlayerFactory { FakeVideoPlayer().also(players::add) } }
                single<DownloadStorage> { storage }
                single<NetworkMonitor> { NetworkMonitor { true } }
                single<DownloadForegroundController> { DownloadForegroundController { } }
            },
        )
    }.koin

    @AfterTest
    fun tearDown() {
        I18n.apply(AppLanguage.ENGLISH) // global UI language; other tests expect English
        stopKoin()
        Dispatchers.resetMain()
    }

    @Test
    fun downloadMovieAndPlayItOffline() = runDesktopComposeUiTest(width = 900, height = 700) {
        val movie = ByteArray(200_000) { (it % 251).toByte() }
        env.binaryResponses["/movie/user/pass/2001.mkv"] = movie
        runBlocking {
            val id = koin.get<PlaylistRepository>().addPlaylist(PlaylistInput("Test playlist", "basic.m3u", PlaylistSourceType.FILE, null))
            koin.get<PlaylistRefresher>().refreshNow(id)
        }
        koin.get<DownloadManager>().start()

        setContent { App() }
        onNodeWithText("I understand").performClick()
        waitForText("Test playlist")
        onNodeWithText("Test playlist").performClick()
        waitForText("Series: Drama")
        onAllNodesWithText("Movies")[1].performClick() // [0] is the "Movies" filter chip
        waitForText("The Movie (2020)")
        onNodeWithContentDescription("Download").performClick()

        waitForText("Single file · 195 KB")
        screenshot("10-download-dialog")
        onNode(hasText("Download") and hasClickAction()).performClick()
        waitForText("Open downloads")
        // Reopening the dialog must start fresh, not show the previous "Download started".
        onNodeWithText("Close").performClick()
        onNodeWithContentDescription("Download").performClick()
        waitForText("Single file · 195 KB")
        onNode(hasText("Download") and hasClickAction()).performClick()
        waitForText("Open downloads")
        onNodeWithText("Open downloads").performClick()

        waitForText("Completed (2)")
        screenshot("11-downloads-completed")
        listOf("The Movie (2020).mkv", "The Movie (2020) (2).mkv").forEach { name ->
            assertContentEquals(movie, storage.fake.read("/downloads/$name".toPath()) { readByteArray() }, name)
        }

        onAllNodesWithContentDescription("Play")[0].performClick() // newest first
        waitUntil(conditionDescription = "offline playback", timeoutMillis = 5_000) { players.isNotEmpty() && players.last().loaded.isNotEmpty() }
        assertEquals("/downloads/The Movie (2020) (2).mkv", players.last().loaded.single().url)
        waitForText("The Movie (2020)")
        screenshot("12-offline-player")
    }

    @Test
    fun downloadDialogOffersSubtitlesAndSettingsChooseTheirFormat() = runDesktopComposeUiTest(width = 900, height = 800) {
        env.responses["/show/master.m3u8"] = io.ktor.http.HttpStatusCode.OK to """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="s",NAME="English",LANGUAGE="en",DEFAULT=YES,URI="en.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="s",NAME="Türkçe",LANGUAGE="tr",URI="tr.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="s",NAME="English (forced)",LANGUAGE="en",FORCED=YES,URI="en-f.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720,SUBTITLES="s"
            720.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,SUBTITLES="s"
            360.m3u8
        """.trimIndent()
        val media = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\n0.ts\n#EXT-X-ENDLIST\n"
        env.responses["/show/720.m3u8"] = io.ktor.http.HttpStatusCode.OK to media
        env.responses["/show/360.m3u8"] = io.ktor.http.HttpStatusCode.OK to media
        env.files["subs.m3u"] = "#EXTM3U\n#EXTINF:-1 group-title=\"Movies\",Subtitled Film\n${TestEnvironment.BASE_URL}/show/master.m3u8\n"
        runBlocking {
            val id = koin.get<PlaylistRepository>().addPlaylist(PlaylistInput("Subs", "subs.m3u", PlaylistSourceType.FILE, null))
            koin.get<PlaylistRefresher>().refreshNow(id)
        }

        setContent { App() }
        onNodeWithText("I understand").performClick()
        waitForText("Subs")
        onNodeWithText("Subs").performClick()
        waitForText("All channels")
        onNodeWithText("All channels").performClick()
        waitForText("Subtitled Film")
        onNodeWithContentDescription("Download").performClick()
        waitForText("English (forced) (en, forced)")
        screenshot("13-download-dialog-subtitles")
        onNodeWithText("Cancel").performClick()

        onNodeWithContentDescription("Back").performClick()
        onNodeWithContentDescription("Back").performClick()
        onNodeWithContentDescription("Settings").performClick()
        waitForText("Subtitle file format")
        onNodeWithText("Both").performClick()
        screenshot("14-settings")
        assertEquals(
            io.github.aalpersen.iptvdler.core.subtitle.SubtitleOutput.BOTH,
            koin.get<io.github.aalpersen.iptvdler.data.settings.AppSettings>().subtitleOutput.value,
        )
    }

    @Test
    fun updateBannerLinksToReleaseAndCanBeDismissed() = runDesktopComposeUiTest(width = 900, height = 700) {
        val settings = koin.get<io.github.aalpersen.iptvdler.data.settings.AppSettings>()
        settings.setLatestRelease(
            io.github.aalpersen.iptvdler.data.update.AvailableUpdate("99.0.0", "https://github.com/aalpersen/iptvdler/releases/tag/v99.0.0"),
        )
        val opened = mutableListOf<String>()
        val uriHandler = object : androidx.compose.ui.platform.UriHandler {
            override fun openUri(uri: String) { opened += uri }
        }

        setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalUriHandler provides uriHandler) { App() }
        }
        onNodeWithText("I understand").performClick()
        waitForText("Version 99.0.0 is available")
        screenshot("40-update-banner")
        onNodeWithText("Open").performClick()
        assertEquals(listOf("https://github.com/aalpersen/iptvdler/releases/tag/v99.0.0"), opened)

        onNodeWithText("Not now").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Version 99.0.0 is available").fetchSemanticsNodes().isEmpty() }
        assertEquals("99.0.0", settings.dismissedUpdate.value)
    }

    @Test
    fun switchingLanguageTranslatesTheUi() = runDesktopComposeUiTest(width = 900, height = 800) {
        val settings = koin.get<io.github.aalpersen.iptvdler.data.settings.AppSettings>()
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Main)
        settings.setLanguage(AppLanguage.ENGLISH) // the default follows the system, which may be Turkish
        bindLanguage(settings, scope)

        setContent { App() }
        onNodeWithText("I understand").performClick()
        waitForText("No playlists yet")
        onNodeWithContentDescription("Settings").performClick()
        waitForText("Language")
        onNodeWithText("Türkçe").performClick()
        waitForText("Dil") // the Settings screen itself switches right away
        waitForText("Altyazı dosya biçimi")
        screenshot("20-settings-turkish")
        assertEquals(AppLanguage.TURKISH, settings.language.value)

        onNodeWithContentDescription("Geri").performClick()
        waitForText("Henüz oynatma listesi yok")
        waitForText("İndirilenler")
        screenshot("21-playlists-turkish")
        scope.cancel()
    }

    @Test
    fun addXtreamAccountByPastingALinkThenPlayAnEpisode() = runDesktopComposeUiTest(width = 900, height = 800) {
        fun api(action: String?, body: String) {
            env.responses[if (action == null) "/player_api.php" else "/player_api.php?action=$action"] = io.ktor.http.HttpStatusCode.OK to body
        }
        api(null, """{"user_info":{"auth":1,"status":"Active","exp_date":"1893456000"}}""")
        api("get_live_categories", """[{"category_id":"1","category_name":"News"}]""")
        api("get_live_streams", """[{"name":"Haber 24","stream_id":101,"category_id":"1"}]""")
        api("get_vod_categories", "[]")
        api("get_vod_streams", "[]")
        api("get_series_categories", """[{"category_id":"20","category_name":"Dramas"}]""")
        api("get_series", """[{"name":"The Show","series_id":301,"category_id":"20"}]""")
        api("get_series_info", """{"episodes":{"1":[
            {"id":"801","episode_num":1,"title":"Pilot","container_extension":"mkv","season":1,"info":{"duration_secs":2700}},
            {"id":"802","episode_num":2,"title":"Second","container_extension":"mkv","season":1}]}}""")

        setContent { App() }
        onNodeWithText("I understand").performClick()
        onNodeWithContentDescription("Add playlist").performClick()
        onNodeWithText("Xtream").performClick()
        waitForText("Server URL")
        // Pasting a whole Xtream link fills in the server, username and password.
        onNode(hasSetTextAction() and hasText("Server URL")).performTextInput(
            "${TestEnvironment.BASE_URL}/get.php?username=alice&password=s3cret&type=m3u_plus&output=ts",
        )
        waitForText("alice")
        screenshot("30-xtream-dialog")
        onNode(hasText("Add") and hasClickAction()).performClick()

        waitForText("Account active until 2030-01-01")
        screenshot("31-xtream-playlist")
        onNodeWithText("lists.example.test").performClick()
        waitForText("Dramas")
        onNodeWithText("Dramas").performClick()
        waitForText("The Show")
        onNodeWithText("The Show").performClick()

        waitForText("Pilot")
        waitForText("S01E02")
        screenshot("32-xtream-episodes")
        onNodeWithText("Pilot").performClick()
        waitUntil(conditionDescription = "episode playback", timeoutMillis = 5_000) { players.isNotEmpty() && players.last().loaded.isNotEmpty() }
        assertEquals("${TestEnvironment.BASE_URL}/series/alice/s3cret/801.mkv", players.last().loaded.single().url)
        waitForText("The Show S01E01 Pilot")
    }

    @Test
    fun recordLiveChannelThenStop() = runDesktopComposeUiTest(width = 900, height = 800) {
        val playlist = buildString {
            append("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:10\n")
            (10..13).forEach { append("#EXTINF:2.0,\ns$it.ts\n") }
        }
        env.responses["/live/ch.m3u8"] = io.ktor.http.HttpStatusCode.OK to playlist
        (10..13).forEach { env.binaryResponses["/live/s$it.ts"] = ByteArray(1000) { b -> (b + it).toByte() } }
        env.files["live.m3u"] = "#EXTM3U\n#EXTINF:-1 group-title=\"News\",Haber Kanalı\n${TestEnvironment.BASE_URL}/live/ch.m3u8\n"
        runBlocking {
            val id = koin.get<PlaylistRepository>().addPlaylist(PlaylistInput("Live", "live.m3u", PlaylistSourceType.FILE, null))
            koin.get<PlaylistRefresher>().refreshNow(id)
        }
        koin.get<DownloadManager>().start()

        setContent { App() }
        onNodeWithText("I understand").performClick()
        waitForText("Live")
        onNodeWithText("Live").performClick()
        waitForText("All channels")
        onNodeWithText("All channels").performClick()
        waitForText("Haber Kanalı")
        onNodeWithContentDescription("Record").performClick()
        waitForText("Stop recording")
        onNodeWithText("1 h").performClick()
        screenshot("15-record-dialog")
        onNode(hasText("Record") and hasClickAction()).performClick()
        waitForText("Open downloads")
        onNodeWithText("Open downloads").performClick()

        waitForText("Recording (1)")
        waitUntil(conditionDescription = "REC line", timeoutMillis = 10_000) {
            onAllNodes(hasText("● REC", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("16-recording")
        onNodeWithContentDescription("Stop recording").performClick()
        waitForText("Completed (1)")
        waitUntil(conditionDescription = "completed line", timeoutMillis = 5_000) {
            onAllNodes(hasText("Recording · ", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("17-recording-completed")
        val output = storage.fake.list("/downloads".toPath()).single { it.name.startsWith("Haber Kanalı ") && it.name.endsWith(".ts") }
        assertEquals(3 * 1000L, storage.fake.metadata(output).size, "the last 3 segments of the live window")
    }

    @Test
    fun browsePlaylistGroupsChannelsAndFavorites() = runDesktopComposeUiTest(width = 900, height = 700) {
        runBlocking {
            val repository = koin.get<PlaylistRepository>()
            val id = repository.addPlaylist(PlaylistInput("Test playlist", "basic.m3u", PlaylistSourceType.FILE, 1440))
            koin.get<PlaylistRefresher>().refreshNow(id)
        }

        setContent { App() }

        waitForText("I understand")
        screenshot("01-disclaimer")
        onNodeWithText("I understand").performClick()

        waitForText("Test playlist")
        screenshot("02-playlists")

        onNodeWithContentDescription("Add playlist").performClick()
        waitForText("M3U URL")
        screenshot("03-add-playlist-dialog")
        onNodeWithText("Cancel").performClick()

        onNodeWithText("Test playlist").performClick()
        waitForText("Series: Drama")
        screenshot("04-groups")

        onNodeWithText("News").performClick()
        waitForText("News One HD")
        onNodeWithContentDescription("Add to favorites").performClick()
        waitForContentDescription("Remove from favorites")
        screenshot("05-channels-in-group")

        onNodeWithContentDescription("Back").performClick()
        onNodeWithText("All channels").performClick()
        waitForText("bare-entry.m3u8")
        onNode(hasSetTextAction()).performTextInput("sport")
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("News One HD").fetchSemanticsNodes().isEmpty() }
        waitForText("Sport One")
        screenshot("06-search")

        onNodeWithText("Sport One").performClick()
        waitForContentDescription("Play")
        assertEquals("http://stream.example.test/sport1/index.m3u8", players.single().loaded.single().url)
        waitForText("12:34")
        screenshot("07-player-controls")

        onNodeWithContentDescription("Play").performClick()
        waitForContentDescription("Pause")
        onNodeWithContentDescription("Quality and tracks").performClick()
        waitForText("1080p · 5.2 Mbps")
        onNodeWithText("720p · 2.5 Mbps").performClick()
        onNodeWithText("Türkçe").performClick()
        screenshot("08-player-tracks")
        assertEquals("v2", players.single().state.value.selectedVideoTrackId)
        assertEquals("a2", players.single().state.value.selectedAudioTrackId)
        onNodeWithText("Done").performClick()

        onNodeWithContentDescription("Back").performClick()
        waitUntil(conditionDescription = "player released", timeoutMillis = 5_000) { players.single().released }
        onNodeWithContentDescription("Back").performClick()
        onNodeWithText("Favorites").performClick()
        waitForText("News One HD")

        onNodeWithContentDescription("Back").performClick()
        onNodeWithText("Recently watched").performClick()
        waitForText("Sport One")
        screenshot("09-recently-watched")
    }

    private fun ComposeUiTest.waitForText(text: String) =
        waitUntil(conditionDescription = "text \"$text\" shown", timeoutMillis = 5_000) {
            onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }

    private fun ComposeUiTest.waitForContentDescription(description: String) =
        waitUntil(conditionDescription = "\"$description\" shown", timeoutMillis = 5_000) {
            onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        }

    /** Saves the topmost layer (window or dialog) to shared/build/screenshots/<name>.png for manual review. */
    private fun ComposeUiTest.screenshot(name: String) {
        waitForIdle()
        // Dialogs add a root of their own; capture the topmost one.
        val roots = onAllNodes(isRoot())
        val image = roots[roots.fetchSemanticsNodes().size - 1].captureToImage().toAwtImage()
        val dir = File("build/screenshots").apply { mkdirs() }
        ImageIO.write(image, "png", File(dir, "$name.png"))
    }
}
