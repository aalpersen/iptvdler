package io.github.aalpersen.iptvdler.core.hls

import io.github.aalpersen.iptvdler.readFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HlsParserTest {

    private val base = "https://media.example.test/show/v1/master.m3u8"

    private fun master(name: String) = HlsParser.parseMaster(readFixture("hls/$name"), base)
    private fun media(name: String) = HlsParser.parseMedia(readFixture("hls/$name"), base)

    @Test
    fun detectsPlaylistKind() {
        assertIs<MasterPlaylist>(HlsParser.parse(readFixture("hls/master.m3u8"), base))
        assertIs<MediaPlaylist>(HlsParser.parse(readFixture("hls/media_vod.m3u8"), base))
    }

    @Test
    fun rejectsNonPlaylists() {
        assertFailsWith<HlsParseException> { HlsParser.parse("<html></html>", base) }
        assertFailsWith<HlsParseException> { HlsParser.parse("#EXTM3U\n#EXT-X-VERSION:3\n", base) }
    }

    @Test
    fun parsesVariantsWithResolvedUris() {
        val playlist = master("master.m3u8")
        assertEquals(4, playlist.variants.size)

        val low = playlist.variants[0]
        assertEquals("https://media.example.test/show/v1/low/index.m3u8", low.uri)
        assertEquals(1_280_000, low.bandwidth)
        assertEquals(1_000_000, low.averageBandwidth)
        assertEquals(Resolution(640, 360), low.resolution)
        assertEquals("avc1.4d401e,mp4a.40.2", low.codecs)
        assertEquals("aac", low.audioGroup)
        assertEquals("subs", low.subtitlesGroup)
        assertEquals("cc", low.closedCaptionsGroup)

        val hi = playlist.variants[1]
        assertEquals("https://media.example.test/hi/index.m3u8?token=abc", hi.uri)
        assertEquals(29.97, hi.frameRate)
        assertNull(hi.closedCaptionsGroup, "CLOSED-CAPTIONS=NONE means none")

        assertEquals("https://media.example.test/show/mid/index.m3u8", playlist.variants[2].uri)
        assertTrue(playlist.variants[3].isAudioOnly)
        assertFalse(low.isAudioOnly)
    }

    @Test
    fun parsesRenditions() {
        val playlist = master("master.m3u8")
        val audio = playlist.renditions(RenditionType.AUDIO, "aac")
        assertEquals(listOf("English", "Türkçe"), audio.map { it.name })
        assertEquals("https://media.example.test/show/v1/audio/en/index.m3u8", audio[0].uri)
        assertTrue(audio[0].isDefault && audio[0].autoSelect)
        assertEquals("2", audio[0].channels)
        assertFalse(audio[1].isDefault)

        val subs = playlist.renditions(RenditionType.SUBTITLES, "subs")
        assertEquals(listOf("en", "de-DE"), subs.map { it.language })

        val cc = playlist.renditions(RenditionType.CLOSED_CAPTIONS, "cc").single()
        assertEquals("CC1", cc.instreamId)
        assertNull(cc.uri)
    }

    @Test
    fun parsesVodMediaPlaylist() {
        val playlist = media("media_vod.m3u8")
        assertEquals(10.0, playlist.targetDurationSeconds)
        assertEquals(PlaylistType.VOD, playlist.playlistType)
        assertTrue(playlist.hasEndList)
        assertFalse(playlist.isLive)
        assertFalse(playlist.isFragmentedMp4)
        assertEquals(3, playlist.version)
        assertEquals(21.021, playlist.totalDurationSeconds, 1e-9)

        val segments = playlist.segments
        assertEquals(listOf(0L, 1L, 2L), segments.map { it.mediaSequence })
        assertEquals("https://media.example.test/show/v1/seg0.ts", segments[0].uri)
        assertEquals("First segment", segments[0].title)
        assertNull(segments[1].title)
        assertEquals("https://cdn.example.test/other/seg2.ts", segments[2].uri)
        assertEquals(3.003, segments[2].durationSeconds)
    }

    @Test
    fun parsesKeysRotationAndNone() {
        val segments = media("media_aes.m3u8").segments
        assertEquals((7794L..7798L).toList(), segments.map { it.mediaSequence })

        val key1 = segments[0].aes128Key!!
        assertEquals("https://media.example.test/show/v1/key1.bin", key1.uri)
        assertNull(key1.ivHex)
        assertEquals(key1, segments[1].aes128Key, "a key applies until the next EXT-X-KEY")

        val key2 = segments[2].aes128Key!!
        assertEquals("https://keys.example.test/key2?id=2", key2.uri)
        assertEquals("000102030405060708090a0b0c0d0e0f", key2.ivHex)

        assertTrue(segments[3].keys.isEmpty(), "METHOD=NONE clears encryption")
        assertEquals("0000000000000000000000000000001f", segments[4].aes128Key!!.ivHex, "short IVs are left-padded")
    }

    @Test
    fun parsesByteRangesWithImplicitOffsets() {
        val segments = media("media_byterange.m3u8").segments
        assertEquals(
            listOf(ByteRange(75232, 0), ByteRange(82112, 75232), ByteRange(69864, 157344), ByteRange(1000, 500)),
            segments.map { it.byteRange },
        )
    }

    @Test
    fun byteRangeWithoutOffsetOnNewResourceIsRejected() {
        val text = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\n#EXT-X-BYTERANGE:100\na.ts\n"
        assertFailsWith<HlsParseException> { HlsParser.parseMedia(text, base) }
    }

    @Test
    fun parsesFmp4MapsWithByteRangeAndEncryption() {
        val playlist = media("media_fmp4.m3u8")
        assertTrue(playlist.isFragmentedMp4)
        val segments = playlist.segments

        val map = segments[0].initSegment!!
        assertEquals("https://media.example.test/show/v1/main.mp4", map.uri)
        assertEquals(ByteRange(720, 0), map.byteRange)
        assertEquals("k.key", map.keys.single().uri!!.substringAfterLast('/'), "init section is encrypted too")

        assertEquals(ByteRange(250000, 720), segments[0].byteRange)
        assertEquals(ByteRange(251000, 250720), segments[1].byteRange)
        assertEquals(map, segments[1].initSegment)

        assertTrue(segments[2].discontinuity)
        assertEquals("https://media.example.test/show/v1/init2.mp4", segments[2].initSegment!!.uri)
        assertNull(segments[2].initSegment!!.byteRange)
    }

    @Test
    fun parsesLivePlaylistWithDiscontinuities() {
        val playlist = media("media_live.m3u8")
        assertTrue(playlist.isLive)
        assertFalse(playlist.hasEndList)
        assertEquals(2680, playlist.mediaSequence)

        val segments = playlist.segments
        assertEquals((2680L..2684L).toList(), segments.map { it.mediaSequence })
        assertEquals(listOf(12L, 12L, 13L, 13L, 14L), segments.map { it.discontinuitySequence })
        assertEquals(listOf(false, false, true, false, true), segments.map { it.discontinuity })
        assertEquals("2026-09-25T10:00:00.000Z", segments[0].programDateTime)
        assertNull(segments[1].programDateTime)
        assertTrue(segments[3].gap)
    }

    @Test
    fun eventPlaylistWithoutEndListIsLive() {
        val text = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-PLAYLIST-TYPE:EVENT\n#EXTINF:4,\na.ts\n"
        assertTrue(HlsParser.parseMedia(text, base).isLive)
    }

    @Test
    fun parsesAttributeListsWithQuotedCommas() {
        assertEquals(
            mapOf("CODECS" to "avc1.4d401e,mp4a.40.2", "BANDWIDTH" to "100", "NAME" to "A, B", "IV" to "0x1F"),
            HlsParser.parseAttributes("""CODECS="avc1.4d401e,mp4a.40.2",BANDWIDTH=100, NAME="A, B",IV=0x1F"""),
        )
    }

    @Test
    fun toleratesBomCrlfAndBlankLines() {
        val text = "﻿#EXTM3U\r\n\r\n#EXT-X-TARGETDURATION:4\r\n#EXTINF:4.0,\r\n a.ts \r\n#EXT-X-ENDLIST\r\n"
        val playlist = HlsParser.parseMedia(text, base)
        assertEquals("https://media.example.test/show/v1/a.ts", playlist.segments.single().uri)
    }
}
