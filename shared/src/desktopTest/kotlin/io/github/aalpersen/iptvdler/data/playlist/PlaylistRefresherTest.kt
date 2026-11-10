package io.github.aalpersen.iptvdler.data.playlist

import io.github.aalpersen.iptvdler.TestEnvironment
import io.github.aalpersen.iptvdler.TestEnvironment.Companion.BASE_URL
import io.github.aalpersen.iptvdler.core.error.UserMessages
import io.github.aalpersen.iptvdler.core.model.ContentType
import io.github.aalpersen.iptvdler.core.model.PlaylistSourceType
import io.github.aalpersen.iptvdler.readFixture
import io.ktor.http.HttpStatusCode
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking

class PlaylistRefresherTest {

    private val env = TestEnvironment()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val repository = PlaylistRepository(env.database, Dispatchers.IO, env.clock)
    private val refresher = PlaylistRefresher(env.database, env.httpClient, env.localFiles, Dispatchers.IO, scope, env.clock)

    @AfterTest
    fun tearDown() = scope.cancel()

    private suspend fun addUrlPlaylist(path: String, interval: Long? = null) =
        repository.addPlaylist(PlaylistInput("Remote", "$BASE_URL$path", PlaylistSourceType.URL, interval))

    private suspend fun addFilePlaylist(fixture: String) =
        repository.addPlaylist(PlaylistInput("Local", fixture, PlaylistSourceType.FILE, null))

    private suspend fun channels(filter: ChannelFilter) = repository.observeChannels(filter, limit = 1_000).first()

    @Test
    fun refreshesUrlPlaylistIntoGroupsAndChannels() = runBlocking {
        env.responses["/basic.m3u"] = HttpStatusCode.OK to readFixture("basic.m3u")
        val id = addUrlPlaylist("/basic.m3u")

        assertEquals(RefreshResult.Success(6), refresher.refreshNow(id))

        val playlist = repository.observePlaylist(id).first()!!
        assertEquals(6, playlist.channelCount)
        assertEquals(env.now, playlist.lastRefreshedAt)
        assertEquals("http://epg.example.test/guide.xml", playlist.epgUrl)
        assertNull(playlist.lastError)

        val groups = repository.observeGroups(id).first()
        assertEquals(listOf("News", "Sports", "Movies", "Series: Drama", "Misc", ""), groups.map { it.name })

        val all = channels(ChannelFilter(id))
        assertEquals(ContentType.LIVE, all.single { it.name == "News One HD" }.type)
        assertEquals(ContentType.MOVIE, all.single { it.name == "The Movie (2020)" }.type)
        assertEquals(ContentType.SERIES, all.single { it.name == "Drama S01E02" }.type)
        assertEquals(listOf("Movies"), repository.observeGroups(id, ContentType.MOVIE).first().map { it.name })
        assertEquals(
            listOf("The Movie (2020)"),
            channels(ChannelFilter(id, contentType = ContentType.MOVIE)).map { it.name },
        )
    }

    @Test
    fun readsLocalFilesAndDropsExactDuplicates() = runBlocking {
        val id = addFilePlaylist("messy.m3u")

        assertEquals(RefreshResult.Success(6), refresher.refreshNow(id))
        assertEquals(1, channels(ChannelFilter(id, search = "Duplicate")).size)
    }

    @Test
    fun storesHeadersPerChannel() = runBlocking {
        val id = addFilePlaylist("headers.m3u")
        refresher.refreshNow(id)

        val pipe = channels(ChannelFilter(id, search = "Pipe Headers")).single()
        assertEquals("http://stream.example.test/pipe.m3u8", pipe.url)
        assertEquals("My Agent/1.0", pipe.headers["User-Agent"])
        assertTrue(channels(ChannelFilter(id, search = "No Headers")).single().headers.isEmpty())
    }

    @Test
    fun searchIsCaseInsensitiveAndTreatsWildcardsLiterally() = runBlocking {
        val id = addFilePlaylist("basic.m3u")
        refresher.refreshNow(id)

        assertEquals(listOf("Sport One"), channels(ChannelFilter(id, search = "sPoRt")).map { it.name })
        assertTrue(channels(ChannelFilter(id, search = "%")).isEmpty())
        assertTrue(channels(ChannelFilter(id, search = "_")).isEmpty())
        assertEquals(listOf("Sport One"), channels(ChannelFilter(id, group = "Sports")).map { it.name })
        assertEquals(listOf("bare-entry.m3u8"), channels(ChannelFilter(id, group = "")).map { it.name })
    }

    @Test
    fun searchIgnoresTurkishCaseAndAccents() = runBlocking {
        env.files["tr.m3u"] = listOf("TRT 1 HD", "İSTANBUL TV", "Işık FM", "Türkçe Haber", "Ülke TV")
            .joinToString("\n", prefix = "#EXTM3U\n") { "#EXTINF:-1,$it\nhttp://h.test/${it.hashCode()}" }
        val id = addFilePlaylist("tr.m3u")
        refresher.refreshNow(id)

        suspend fun find(q: String) = channels(ChannelFilter(id, search = q)).map { it.name }
        assertEquals(listOf("İSTANBUL TV"), find("istanbul"))
        assertEquals(listOf("İSTANBUL TV"), find("İstanbul"))
        assertEquals(listOf("Işık FM"), find("ışık"))
        assertEquals(listOf("Işık FM"), find("isik"))
        assertEquals(listOf("Türkçe Haber"), find("TÜRKÇE"))
        assertEquals(listOf("Türkçe Haber"), find("turkce"))
        assertEquals(listOf("Ülke TV"), find("ülke"))
    }

    @Test
    fun backfillFixesSearchNamesOfMigratedRows() = runBlocking {
        env.files["tr2.m3u"] = "#EXTM3U\n#EXTINF:-1,İSTANBUL TV\nhttp://h.test/1\n#EXTINF:-1,Plain\nhttp://h.test/2\n"
        val id = addFilePlaylist("tr2.m3u")
        refresher.refreshNow(id)
        // Simulate the v3 -> v4 migration's ASCII-only lower().
        env.database.channelQueries.selectSearchNamesPage(0, 100).executeAsList().forEach {
            env.database.channelQueries.updateSearchName(it.name.map { c -> if (c in 'A'..'Z') c + 32 else c }.joinToString(""), it.id)
        }
        assertTrue(channels(ChannelFilter(id, search = "istanbul")).isEmpty())

        assertEquals(1, repository.backfillSearchNames())

        assertEquals(listOf("İSTANBUL TV"), channels(ChannelFilter(id, search = "istanbul")).map { it.name })
        assertEquals(0, repository.backfillSearchNames())
    }

    @Test
    fun favoritesAndRecentsSurviveRefresh() = runBlocking {
        val id = addFilePlaylist("basic.m3u")
        refresher.refreshNow(id)
        val sport = channels(ChannelFilter(id, search = "Sport One")).single()
        repository.setFavorite(id, sport.url, true)
        repository.markWatched(id, sport.url)

        refresher.refreshNow(id)

        val favorites = channels(ChannelFilter(id, favoritesOnly = true))
        assertEquals(listOf("Sport One"), favorites.map { it.name })
        assertTrue(favorites.single().isFavorite)
        assertEquals(listOf("Sport One"), repository.observeRecent(id, 10).first().map { it.name })

        repository.setFavorite(id, sport.url, false)
        assertTrue(channels(ChannelFilter(id, favoritesOnly = true)).isEmpty())
    }

    @Test
    fun failedRefreshKeepsPreviousChannelsAndRecordsError() = runBlocking {
        env.responses["/list.m3u"] = HttpStatusCode.OK to readFixture("basic.m3u")
        val id = addUrlPlaylist("/list.m3u")
        refresher.refreshNow(id)

        env.responses["/list.m3u"] = HttpStatusCode.InternalServerError to "oops"
        val result = refresher.refreshNow(id)

        assertIs<RefreshResult.Failed>(result)
        assertEquals(UserMessages.httpStatus(500), result.message)
        val playlist = repository.observePlaylist(id).first()!!
        assertEquals(UserMessages.httpStatus(500), playlist.lastError)
        assertEquals(6, playlist.channelCount)
        assertEquals(6, channels(ChannelFilter(id)).size)

        env.responses["/list.m3u"] = HttpStatusCode.OK to readFixture("basic.m3u")
        refresher.refreshNow(id)
        assertNull(repository.observePlaylist(id).first()!!.lastError)
    }

    @Test
    fun rejectsNonPlaylistsAndEmptyPlaylists() = runBlocking {
        env.responses["/page.html"] = HttpStatusCode.OK to "<html><body>Not found</body></html>"
        env.responses["/empty.m3u"] = HttpStatusCode.OK to "#EXTM3U\n"

        val html = refresher.refreshNow(addUrlPlaylist("/page.html"))
        val empty = refresher.refreshNow(addUrlPlaylist("/empty.m3u"))

        assertEquals(RefreshResult.Failed("This doesn't look like an M3U playlist"), html)
        assertEquals(RefreshResult.Failed("The playlist has no entries"), empty)
    }

    @Test
    fun autoRefreshRunsOnlyWhenIntervalElapsed() = runBlocking {
        env.responses["/auto.m3u"] = HttpStatusCode.OK to readFixture("basic.m3u")
        val auto = addUrlPlaylist("/auto.m3u", interval = 60)
        addUrlPlaylist("/auto.m3u", interval = null)

        refresher.refreshDuePlaylists().joinAll()
        assertNotNull(repository.observePlaylist(auto).first()!!.lastRefreshedAt)

        env.now += 30 * 60_000L
        assertTrue(refresher.refreshDuePlaylists().isEmpty())

        env.now += 31 * 60_000L
        assertEquals(1, refresher.refreshDuePlaylists().size)
    }

    @Test
    fun importsVeryLargePlaylists() = runBlocking {
        env.files["big.m3u"] = buildString {
            appendLine("#EXTM3U")
            repeat(100_000) { i ->
                appendLine("#EXTINF:-1 tvg-id=\"c$i\" group-title=\"Group ${i % 200}\",Channel $i")
                appendLine("http://stream.example.test/live/$i.ts")
            }
        }
        val id = addFilePlaylist("big.m3u")

        val started = System.nanoTime()
        assertEquals(RefreshResult.Success(100_000), refresher.refreshNow(id))
        println("Imported 100k entries in ${(System.nanoTime() - started) / 1_000_000} ms")

        assertEquals(200, repository.observeGroups(id).first().size)
        assertEquals(listOf("Channel 99999"), channels(ChannelFilter(id, search = "Channel 99999")).map { it.name })
    }

    @Test
    fun deletingPlaylistRemovesItsData() = runBlocking {
        val id = addFilePlaylist("basic.m3u")
        refresher.refreshNow(id)
        repository.setFavorite(id, "http://stream.example.test/sport1/index.m3u8", true)

        repository.deletePlaylist(id)

        assertNull(repository.observePlaylist(id).first())
        assertTrue(channels(ChannelFilter(id)).isEmpty())
        assertTrue(repository.observeGroups(id).first().isEmpty())
    }
}
