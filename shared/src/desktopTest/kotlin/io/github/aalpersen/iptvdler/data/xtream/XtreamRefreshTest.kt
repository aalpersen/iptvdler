package io.github.aalpersen.iptvdler.data.xtream

import io.github.aalpersen.iptvdler.TestEnvironment
import io.github.aalpersen.iptvdler.core.model.ContentType
import io.github.aalpersen.iptvdler.core.model.PlaylistSourceType
import io.github.aalpersen.iptvdler.data.playlist.ChannelFilter
import io.github.aalpersen.iptvdler.data.playlist.PlaylistInput
import io.github.aalpersen.iptvdler.data.playlist.PlaylistRefresher
import io.github.aalpersen.iptvdler.data.playlist.PlaylistRepository
import io.github.aalpersen.iptvdler.data.playlist.RefreshResult
import io.ktor.http.HttpStatusCode
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class XtreamRefreshTest {

    private val env = TestEnvironment()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository = PlaylistRepository(env.database, Dispatchers.IO, env.clock)
    private val refresher = PlaylistRefresher(env.database, env.httpClient, env.localFiles, Dispatchers.IO, scope, env.clock)
    private val server = TestEnvironment.BASE_URL

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun api(action: String?, body: String, status: HttpStatusCode = HttpStatusCode.OK) {
        env.responses[if (action == null) "/player_api.php" else "/player_api.php?action=$action"] = status to body
    }

    private fun serveAccount(status: String = "Active", auth: Int = 1, expires: String? = "\"1893456000\"") = api(
        null,
        """{"user_info":{"auth":$auth,"status":"$status","exp_date":${expires ?: "null"},"max_connections":"2",
           "allowed_output_formats":["m3u8","ts"]},"server_info":{"url":"iptv.example.test","port":"80"}}""",
    )

    private fun serveLists() {
        api("get_live_categories", """[{"category_id":"1","category_name":"News","parent_id":0},{"category_id":2,"category_name":"Sports"}]""")
        api("get_live_streams", """[
            {"num":1,"name":"Haber 24","stream_type":"live","stream_id":101,"stream_icon":"http://img.test/h.png","epg_channel_id":"haber.tr","category_id":"1"},
            {"num":2,"name":"Spor HD","stream_type":"live","stream_id":"102","stream_icon":"","epg_channel_id":null,"category_id":"2"}
        ]""")
        api("get_vod_categories", """[{"category_id":"10","category_name":"Movies | Drama"}]""")
        api("get_vod_streams", """[{"num":1,"name":"The Film","stream_id":201,"stream_icon":"http://img.test/f.jpg","category_id":"10","container_extension":"mkv"}]""")
        api("get_series_categories", """[{"category_id":"20","category_name":"Dramas"}]""")
        api("get_series", """[{"num":1,"name":"The Show","series_id":301,"cover":"http://img.test/s.jpg","category_id":"20"}]""")
    }

    private fun addXtream(): Long = runBlocking {
        repository.addPlaylist(PlaylistInput("My IPTV", server, PlaylistSourceType.XTREAM, null, xtreamUsername = "alice", xtreamPassword = "s3cret"))
    }

    private fun channels(id: Long) = runBlocking { repository.observeChannels(ChannelFilter(id), 100).first() }

    @Test
    fun loadsLiveMoviesAndSeriesByCategory() = runBlocking {
        serveAccount()
        serveLists()
        val id = addXtream()

        val result = refresher.refreshNow(id)
        assertEquals(RefreshResult.Success(4), result)

        val list = channels(id)
        assertEquals(listOf("Haber 24", "Spor HD", "The Film", "The Show"), list.map { it.name })
        assertEquals(listOf(ContentType.LIVE, ContentType.LIVE, ContentType.MOVIE, ContentType.SERIES), list.map { it.type })
        assertEquals(listOf("News", "Sports", "Movies | Drama", "Dramas"), list.map { it.group })
        // Live prefers MPEG-TS when the account allows it; movies keep their container.
        assertEquals("$server/live/alice/s3cret/101.ts", list[0].url)
        assertEquals("$server/movie/alice/s3cret/201.mkv", list[2].url)
        assertEquals("xtream-series:301", list[3].url)
        assertEquals("301", list[3].seriesId)
        assertEquals("haber.tr", list[0].tvgId)
        assertEquals("http://img.test/h.png", list[0].logo)
        assertNull(list[1].logo) // empty icon

        val playlist = repository.observePlaylist(id).first()!!
        assertEquals("Active", playlist.accountStatus)
        assertEquals(1_893_456_000_000, playlist.accountExpiresAt)
        assertEquals("$server/xmltv.php?username=alice&password=s3cret", playlist.epgUrl)
        assertTrue(env.requestedUrls.all { "username=alice" in it && "password=s3cret" in it })
    }

    @Test
    fun wrongPasswordKeepsThePreviousList() = runBlocking {
        serveAccount()
        serveLists()
        val id = addXtream()
        refresher.refreshNow(id)

        serveAccount(auth = 0)
        val result = assertIs<RefreshResult.Failed>(refresher.refreshNow(id))
        assertEquals(io.github.aalpersen.iptvdler.i18n.strings.xtreamAuthFailed, result.message)
        assertEquals(4, channels(id).size)
    }

    @Test
    fun expiredAccountIsReported() = runBlocking {
        serveAccount(status = "Expired")
        val id = addXtream()
        val result = assertIs<RefreshResult.Failed>(refresher.refreshNow(id))
        assertEquals(io.github.aalpersen.iptvdler.i18n.strings.xtreamAccountNotActive("Expired"), result.message)
    }

    @Test
    fun aBrokenSeriesEndpointDoesNotHideTheRest() = runBlocking {
        serveAccount(expires = null)
        serveLists()
        api("get_series", "", HttpStatusCode.InternalServerError)
        val id = addXtream()

        assertEquals(RefreshResult.Success(3), refresher.refreshNow(id))
        assertNull(repository.observePlaylist(id).first()!!.accountExpiresAt)
    }

    @Test
    fun notAnXtreamServer() = runBlocking {
        env.responses["/player_api.php"] = HttpStatusCode.OK to "<html>Welcome to nginx</html>"
        val id = addXtream()
        val result = assertIs<RefreshResult.Failed>(refresher.refreshNow(id))
        assertEquals(io.github.aalpersen.iptvdler.i18n.strings.xtreamNotAServer, result.message)
    }

    @Test
    fun seriesInfoInBothShapes() = runBlocking {
        val client = XtreamClient(env.httpClient)
        val c = XtreamCredentials(server, "alice", "s3cret")
        api("get_series_info", """{"info":{"name":"The Show"},"episodes":{
            "2":[{"id":"902","episode_num":"1","title":"The Show - S02E01 - Back","container_extension":"mp4","season":2,"info":{"duration_secs":1800}}],
            "1":[{"id":"802","episode_num":2,"title":"Second","container_extension":"mkv","season":1},
                 {"id":"801","episode_num":1,"title":"Pilot","container_extension":"mkv","season":"1","info":[]}]}}""")
        val info = client.seriesInfo(c, "301")
        assertEquals(listOf("801", "802", "902"), info.episodes.map { it.id })
        assertEquals(listOf(1, 2), info.seasons)
        assertEquals(1800L, info.episodes.last().durationSeconds)
        assertEquals("$server/series/alice/s3cret/801.mkv", c.episodeUrl(info.episodes[0].id, info.episodes[0].containerExtension))

        api("get_series_info", """{"episodes":[[{"id":"5","episode_num":1,"title":"A","container_extension":"mp4","season":1}]]}""")
        assertEquals(listOf("5"), client.seriesInfo(c, "9").episodes.map { it.id })
    }
}
