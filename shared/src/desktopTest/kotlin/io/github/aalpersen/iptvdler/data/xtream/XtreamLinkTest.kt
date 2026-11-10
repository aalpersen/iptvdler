package io.github.aalpersen.iptvdler.data.xtream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XtreamLinkTest {

    @Test
    fun parsesM3uAndApiLinks() {
        assertEquals(
            XtreamCredentials("http://iptv.example.test:8080", "alice", "s3cret"),
            XtreamLink.parse("http://iptv.example.test:8080/get.php?username=alice&password=s3cret&type=m3u_plus&output=ts"),
        )
        assertEquals(
            XtreamCredentials("https://iptv.example.test", "bob", "p@ss&word"),
            XtreamLink.parse("https://iptv.example.test/player_api.php?username=bob&password=p%40ss%26word"),
        )
        // Without a scheme, as people often type it.
        assertEquals(
            XtreamCredentials("http://iptv.example.test:25461", "u", "p"),
            XtreamLink.parse("iptv.example.test:25461/get.php?username=u&password=p"),
        )
    }

    @Test
    fun parsesStreamUrls() {
        assertEquals(
            XtreamCredentials("http://iptv.example.test:8080", "alice", "s3cret"),
            XtreamLink.parse("http://iptv.example.test:8080/movie/alice/s3cret/1234.mkv"),
        )
        assertEquals(
            XtreamCredentials("http://iptv.example.test:8080/panel", "alice", "s3cret"),
            XtreamLink.parse("http://iptv.example.test:8080/panel/live/alice/s3cret/55.ts"),
        )
    }

    @Test
    fun rejectsOtherLinks() {
        assertNull(XtreamLink.parse("https://example.test/playlist.m3u"))
        assertNull(XtreamLink.parse("not a url at all"))
        assertFalse(XtreamLink.isXtreamM3uLink("https://example.test/playlist.m3u"))
        assertTrue(XtreamLink.isXtreamM3uLink("http://h.test/get.php?username=a&password=b"))
    }

    @Test
    fun normalizesServer() {
        assertEquals("http://iptv.example.test:8080", XtreamLink.normalizeServer("iptv.example.test:8080/"))
        assertEquals("https://iptv.example.test", XtreamLink.normalizeServer("https://iptv.example.test:443/player_api.php"))
        assertNull(XtreamLink.normalizeServer(""))
    }

    @Test
    fun buildsUrls() {
        val c = XtreamCredentials("http://h.test:8080", "a b", "p&q")
        assertEquals("http://h.test:8080/live/a%20b/p&q/7.ts", c.liveUrl("7", "ts"))
        assertEquals("http://h.test:8080/movie/a%20b/p&q/9.mkv", c.movieUrl("9", "mkv"))
        assertEquals("http://h.test:8080/series/a%20b/p&q/11.mp4", c.episodeUrl("11", "mp4"))
        assertEquals("http://h.test:8080/player_api.php?username=a%20b&password=p%26q&action=get_series_info&series_id=3", c.apiUrl("get_series_info", "series_id" to "3"))
        assertFalse("p&q" in c.toString())
    }
}
