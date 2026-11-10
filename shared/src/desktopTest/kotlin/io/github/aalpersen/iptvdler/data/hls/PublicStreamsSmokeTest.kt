package io.github.aalpersen.iptvdler.data.hls

import io.github.aalpersen.iptvdler.core.hls.EncryptionStatus
import io.github.aalpersen.iptvdler.core.hls.RenditionType
import io.github.aalpersen.iptvdler.core.hls.VariantSelector
import io.github.aalpersen.iptvdler.data.network.createHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Parses real public test streams (Apple, Mux). Needs network, so it only runs with
 * `./gradlew :shared:desktopTest --tests "*PublicStreamsSmokeTest*" -Piptvdler.networkTests=true`.
 */
class PublicStreamsSmokeTest {

    private val enabled = System.getProperty("iptvdler.networkTests") == "true"
    private val client = HlsClient(createHttpClient())

    @Test
    fun appleAdvancedFmp4Example() = runBlocking {
        if (!enabled) return@runBlocking
        val probe = client.probe(
            "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8",
            emptyMap(),
        )
        assertIs<StreamProbe.Hls>(probe)
        val master = probe.master!!
        println("Apple: ${master.variants.size} variants, ${master.renditions.size} renditions, " +
            "selected ${probe.selection?.variant?.resolution}, ${probe.media.segments.size} segments, " +
            "${probe.media.totalDurationSeconds}s, fMP4=${probe.media.isFragmentedMp4}, audio=${probe.selection?.audio?.name}, " +
            "subs=${probe.selection?.subtitles?.map { it.language }}, encryption=${probe.encryption}")

        assertTrue(master.variants.size > 5)
        assertTrue(master.renditions.any { it.type == RenditionType.SUBTITLES && it.uri != null })
        assertTrue(probe.media.isFragmentedMp4)
        assertTrue(!probe.media.isLive && probe.media.segments.isNotEmpty())
        assertTrue(probe.media.segments.first().uri.startsWith("https://devstreaming-cdn.apple.com/"))
        assertEquals(EncryptionStatus.Clear, probe.encryption)
        assertTrue(VariantSelector.estimateBytes(probe.selection!!.variant, probe.media.totalDurationSeconds) > 0)
    }

    @Test
    fun muxBigBuckBunny() = runBlocking {
        if (!enabled) return@runBlocking
        val probe = client.probe("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", emptyMap())
        assertIs<StreamProbe.Hls>(probe)
        println("Mux: ${probe.master?.variants?.size} variants, selected ${probe.selection?.variant?.resolution}, " +
            "${probe.media.segments.size} TS segments, ${probe.media.totalDurationSeconds}s, encryption=${probe.encryption}")
        assertEquals(false, probe.media.isFragmentedMp4)
        assertEquals(1080, probe.selection?.variant?.resolution?.height)
        assertTrue(probe.media.totalDurationSeconds > 600)
    }
}
