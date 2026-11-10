package io.github.aalpersen.iptvdler.core.m3u

import io.github.aalpersen.iptvdler.core.model.ContentType.LIVE
import io.github.aalpersen.iptvdler.core.model.ContentType.MOVIE
import io.github.aalpersen.iptvdler.core.model.ContentType.SERIES
import kotlin.test.Test
import kotlin.test.assertEquals

class ContentClassifierTest {

    private fun classify(url: String, group: String? = null, title: String = "Title") =
        ContentClassifier.classify(url, group, title)

    @Test
    fun xtreamUrlLayoutsWin() {
        assertEquals(LIVE, classify("http://h.test/live/u/p/1.ts", group = "Movies 24/7"))
        assertEquals(MOVIE, classify("http://h.test/movie/u/p/1.mkv"))
        assertEquals(SERIES, classify("http://h.test/series/u/p/1.mp4"))
    }

    @Test
    fun episodeTitlesAndSeriesGroupsAreSeries() {
        assertEquals(SERIES, classify("http://h.test/x.mp4", title = "Show S02E10"))
        assertEquals(SERIES, classify("http://h.test/x.m3u8", group = "TV Shows"))
        assertEquals(SERIES, classify("http://h.test/x.m3u8", group = "Yerli Diziler"))
    }

    @Test
    fun vodExtensionsAndMovieGroupsAreMovies() {
        assertEquals(MOVIE, classify("http://h.test/film.mkv?token=1"))
        assertEquals(MOVIE, classify("http://h.test/stream.m3u8", group = "VOD | Action"))
        assertEquals(MOVIE, classify("http://h.test/stream.m3u8", group = "Filmler"))
    }

    @Test
    fun everythingElseIsLive() {
        assertEquals(LIVE, classify("http://h.test/news/index.m3u8", group = "News"))
        assertEquals(LIVE, classify("http://h.test/1.ts", group = "Vodafone TV"), "whole words only")
        assertEquals(LIVE, classify("udp://@239.0.0.1:1234"))
    }
}
