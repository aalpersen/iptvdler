package io.github.aalpersen.iptvdler.core.m3u

import io.github.aalpersen.iptvdler.readFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.Buffer

class M3uParserTest {

    private class Parsed(val parser: M3uParser, val entries: List<M3uEntry>)

    /** Splits lines the way the app does (okio readUtf8Line: LF or CRLF). */
    private fun parse(text: String): Parsed {
        val parser = M3uParser()
        val source = Buffer().writeUtf8(text)
        val entries = buildList {
            while (true) {
                val line = source.readUtf8Line() ?: break
                parser.feedLine(line)?.let(::add)
            }
        }
        return Parsed(parser, entries)
    }

    @Test
    fun parsesHeaderAttributesAndEpgUrls() {
        val header = parse(readFixture("basic.m3u")).parser.header!!
        assertEquals("http://epg.example.test/guide.xml", header.attributes["url-tvg"])
        assertEquals(
            listOf("http://epg.example.test/guide.xml", "http://epg.example.test/a.xml", "http://epg.example.test/b.xml"),
            header.epgUrls,
        )
    }

    @Test
    fun parsesExtinfAttributesAndTitles() {
        val entries = parse(readFixture("basic.m3u")).entries
        assertEquals(6, entries.size)

        val news = entries[0]
        assertEquals("News One HD", news.title)
        assertEquals("http://stream.example.test/live/user/pass/1001.ts", news.url)
        assertEquals(-1.0, news.durationSeconds)
        assertEquals("news.one", news.tvgId)
        assertEquals("News One", news.tvgName)
        assertEquals("http://img.example.test/news1.png", news.tvgLogo)
        assertEquals("News", news.group)

        val sport = entries[1]
        assertEquals("default", sport.catchup)
        assertEquals("?utc={utc}&lutc={lutc}", sport.catchupSource)

        assertEquals(7200.0, entries[2].durationSeconds)
        assertEquals("The Movie (2020)", entries[2].title)
    }

    @Test
    fun extgrpIsUsedWhenGroupTitleIsMissing() {
        val plain = parse(readFixture("basic.m3u")).entries[4]
        assertEquals("Plain Channel", plain.title)
        assertEquals("Misc", plain.group)
    }

    @Test
    fun urlWithoutExtinfBecomesEntryTitledFromUrl() {
        val bare = parse(readFixture("basic.m3u")).entries[5]
        assertEquals("bare-entry.m3u8", bare.title)
        assertNull(bare.group)
        assertTrue(bare.attributes.isEmpty())
    }

    @Test
    fun toleratesBomCrlfQuotesAndBrokenLines() {
        val parsed = parse(readFixture("messy.m3u"))
        assertTrue(parsed.parser.header != null, "BOM must not hide the #EXTM3U header")

        val entries = parsed.entries
        assertEquals(
            listOf(
                "Title, With, Commas",
                "Spaced Title",
                "Duplicate",
                "Duplicate",
                "lowercase directive",
                "Name From Attr",
                "Fallback Name",
            ),
            entries.map { it.title },
        )

        assertEquals("Single Quoted", entries[0].tvgName)
        assertEquals("Quotes", entries[0].group)

        assertEquals("unquoted", entries[1].tvgId)
        assertEquals("Group, With Comma", entries[1].group)
        assertNull(entries[1].tvgLogo, "blank attributes are treated as missing")

        assertEquals("Lower", entries[4].group, "attribute keys are case-insensitive")
        assertEquals("Broken", entries[5].group, "unterminated quote ends at the next comma")
        assertEquals("http://stream.example.test/d.m3u8", entries[5].url)
    }

    @Test
    fun orphanExtinfIsDroppedWithoutShiftingLaterEntries() {
        val entries = parse(readFixture("messy.m3u")).entries
        assertTrue(entries.none { it.title == "Orphan" })
        assertEquals("http://stream.example.test/dup.m3u8", entries[2].url)
    }

    @Test
    fun htmlIsNotRecognizedAsM3u() {
        val parsed = parse("<html>\n<body>Error</body>\n</html>\n")
        assertEquals(false, parsed.parser.looksLikeM3u)
        assertTrue(parse("#EXTINF:-1,A\nhttp://a.test/1\n").parser.looksLikeM3u, "header is optional")
    }

    @Test
    fun trailingCarriageReturnIsIgnored() {
        val parser = M3uParser()
        parser.feedLine("#EXTM3U\r")
        parser.feedLine("#EXTINF:-1 group-title=\"G\",Name\r")
        val entry = parser.feedLine("http://stream.example.test/x.m3u8\r")!!
        assertEquals("Name", entry.title)
        assertEquals("http://stream.example.test/x.m3u8", entry.url)
    }

    @Test
    fun vlcOptionsBecomeHeaders() {
        val vlc = parse(readFixture("headers.m3u")).entries[0]
        assertEquals(
            mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0) Test",
                "Referer" to "https://ref.example.test/",
            ),
            vlc.headers,
        )
    }

    @Test
    fun pipeHeadersAreSplitOffAndDecoded() {
        val pipe = parse(readFixture("headers.m3u")).entries[1]
        assertEquals("http://stream.example.test/pipe.m3u8", pipe.url)
        assertEquals(
            mapOf("User-Agent" to "My Agent/1.0", "Referer" to "https://ref.example.test/", "X-Custom" to "abc"),
            pipe.headers,
        )
    }

    @Test
    fun kodiStreamHeadersAreParsed() {
        val kodi = parse(readFixture("headers.m3u")).entries[2]
        assertEquals(mapOf("User-Agent" to "KodiAgent", "Referer" to "https://kodi.example.test/"), kodi.headers)
        assertEquals("hls", kodi.kodiProps["inputstream.adaptive.manifest_type"])
    }

    @Test
    fun pipeHeadersOverrideVlcOptionsAndOptionsDoNotLeak() {
        val entries = parse(readFixture("headers.m3u")).entries
        assertEquals(mapOf("User-Agent" to "FromPipe"), entries[3].headers)
        assertTrue(entries[4].headers.isEmpty())
        assertTrue(entries[4].kodiProps.isEmpty())
    }

    @Test
    fun parsesVeryLargePlaylistsStreaming() {
        val count = 60_000
        val text = buildString {
            appendLine("#EXTM3U")
            repeat(count) { i ->
                appendLine("#EXTINF:-1 tvg-id=\"ch$i\" group-title=\"Group ${i % 100}\",Channel $i")
                appendLine("http://stream.example.test/live/$i.ts")
            }
        }
        val entries = parse(text).entries
        assertEquals(count, entries.size)
        assertEquals("Channel ${count - 1}", entries.last().title)
        assertEquals("Group 99", entries[99].group)
    }
}
