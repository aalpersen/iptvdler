package io.github.aalpersen.iptvdler.core.hls

import io.github.aalpersen.iptvdler.readFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VariantSelectorTest {

    private val base = "https://media.example.test/show/v1/master.m3u8"
    private val master = HlsParser.parseMaster(readFixture("hls/master.m3u8"), base)

    @Test
    fun ranksVideoByHeightThenBandwidthAndAudioOnlyLast() {
        assertEquals(
            listOf(1080, 720, 360, null),
            VariantSelector.ranked(master).map { it.resolution?.height },
        )
    }

    @Test
    fun selectsByQualityPreference() {
        assertEquals(1080, VariantSelector.selectVariant(master)?.resolution?.height)
        assertEquals(360, VariantSelector.selectVariant(master, QualityPreference.Lowest)?.resolution?.height)
        assertEquals(720, VariantSelector.selectVariant(master, QualityPreference.MaxHeight(900))?.resolution?.height)
        assertEquals(360, VariantSelector.selectVariant(master, QualityPreference.MaxHeight(100))?.resolution?.height)
    }

    @Test
    fun picksAudioByLanguageThenDefault() {
        val default = VariantSelector.select(master)!!
        assertEquals("en", default.audio?.language)
        assertTrue(default.hasSeparateAudio)

        assertEquals("tr", VariantSelector.select(master, audioLanguage = "TR")?.audio?.language)
        assertEquals("en", VariantSelector.select(master, audioLanguage = "ja")?.audio?.language, "falls back to DEFAULT")
    }

    @Test
    fun filtersSubtitlesByLanguage() {
        assertEquals(listOf("en", "de-DE"), VariantSelector.select(master)!!.subtitles.map { it.language })
        assertEquals(listOf("de-DE"), VariantSelector.select(master, subtitleLanguages = setOf("de"))!!.subtitles.map { it.language })
    }

    @Test
    fun audioGroupWithoutUrisMeansMuxedAudio() {
        val muxed = HlsParser.parseMaster(readFixture("hls/master_muxed.m3u8"), base)
        val selection = VariantSelector.select(muxed)!!
        assertEquals(720, selection.variant.resolution?.height)
        assertNull(selection.audio)
        assertFalse(selection.hasSeparateAudio)
    }

    @Test
    fun estimatesSizeFromAverageBandwidthWhenAvailable() {
        val low = master.variants.first { it.resolution?.height == 360 }
        val hi = master.variants.first { it.resolution?.height == 1080 }
        assertEquals(1_000_000L * 600 / 8, VariantSelector.estimateBytes(low, 600.0))
        assertEquals(6_400_000L * 600 / 8, VariantSelector.estimateBytes(hi, 600.0))
    }
}

class VariantChoicesTest {
    private val master = HlsParser.parseMaster(readFixture("hls/master_audio_codecs.m3u8"), "https://h.test/master.m3u8")

    @Test
    fun highestPrefersAacOverAc3Duplicates() {
        assertEquals("https://h.test/v1080-aac.m3u8", VariantSelector.selectVariant(master)?.uri)
        assertEquals("https://h.test/v720-eac3-only.m3u8", VariantSelector.selectVariant(master, QualityPreference.Lowest)?.uri)
    }

    @Test
    fun lowestAvoidsAc3DuplicateAtTheSameResolution() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=541052,CODECS="avc1.640015,mp4a.40.2",RESOLUTION=480x270
            a.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=763555,CODECS="avc1.640015,ac-3",RESOLUTION=480x270
            b.m3u8
        """.trimIndent()
        val m = HlsParser.parseMaster(text, "https://h.test/m.m3u8")
        assertEquals("https://h.test/a.m3u8", VariantSelector.selectVariant(m, QualityPreference.Lowest)?.uri)
    }

    @Test
    fun qualityOptionsDropAudioCodecDuplicatesAndLabelCodecs() {
        val options = VariantSelector.qualityOptions(master)
        assertEquals(
            listOf("v1080-aac", "v1080-hevc", "v1080-low", "v720-eac3-only"),
            options.map { it.uri.substringAfterLast('/').removeSuffix(".m3u8") },
            "AC-3 duplicate of 1080p H.264 removed; 720p kept because it has no AAC alternative",
        )
        assertEquals(
            listOf("1080p · H.264 · 8.0 Mbps", "1080p · HEVC · 6.0 Mbps", "1080p · H.264 · 4.9 Mbps", "720p · 2.0 Mbps"),
            options.map { VariantSelector.label(it, options) },
        )
    }
}
