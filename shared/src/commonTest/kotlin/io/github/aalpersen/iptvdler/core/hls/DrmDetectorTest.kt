package io.github.aalpersen.iptvdler.core.hls

import io.github.aalpersen.iptvdler.readFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DrmDetectorTest {

    private val base = "https://media.example.test/p.m3u8"

    private fun status(fixture: String) = DrmDetector.check(HlsParser.parse(readFixture("hls/$fixture"), base))

    @Test
    fun clearAndAes128StreamsAreDownloadable() {
        assertEquals(EncryptionStatus.Clear, status("media_vod.m3u8"))
        assertEquals(EncryptionStatus.Aes128, status("media_aes.m3u8"))
        assertEquals(EncryptionStatus.Aes128, status("media_fmp4.m3u8"))
        assertTrue(status("media_aes.m3u8").downloadable)
    }

    @Test
    fun detectsDrmSystems() {
        assertEquals(EncryptionStatus.Drm("Widevine"), status("drm_widevine.m3u8"))
        assertEquals(EncryptionStatus.Drm("FairPlay"), status("drm_fairplay.m3u8"))
        assertEquals(EncryptionStatus.Drm("PlayReady"), status("drm_playready_aes.m3u8"), "KEYFORMAT wins over METHOD=AES-128")
        assertEquals(EncryptionStatus.Drm("SAMPLE-AES"), status("drm_sample_aes_identity.m3u8"))
        assertFalse(status("drm_widevine.m3u8").downloadable)
    }

    @Test
    fun sessionKeysInMasterAreChecked() {
        assertEquals(EncryptionStatus.Drm("FairPlay"), status("master_session_key_drm.m3u8"))
        assertEquals(EncryptionStatus.Clear, status("master.m3u8"))
    }

    @Test
    fun anyDrmKeyAmongSeveralFormatsWins() {
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-KEY:METHOD=AES-128,URI="clear.key"
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://x",KEYFORMAT="com.apple.streamingkeydelivery"
            #EXTINF:6,
            a.ts
        """.trimIndent()
        val playlist = HlsParser.parseMedia(text, base)
        assertEquals(2, playlist.segments.single().keys.size)
        assertIs<EncryptionStatus.Drm>(DrmDetector.check(playlist))
    }

    @Test
    fun unknownMethodsAndKeySystemsAreRefused() {
        val unknownMethod = HlsKey(KeyMethod.UNKNOWN, "k", rawMethod = "AES-256")
        assertEquals(EncryptionStatus.Unsupported("METHOD=AES-256"), DrmDetector.classify(unknownMethod))

        val customSystem = HlsKey(KeyMethod.AES_128, "k", keyFormat = "com.example.custom-drm")
        assertIs<EncryptionStatus.Drm>(DrmDetector.classify(customSystem))

        assertFalse(DrmDetector.classify(HlsKey(KeyMethod.AES_128, uri = null)).downloadable)
    }

    @Test
    fun combinesMasterAndMediaStatus() {
        val master = HlsParser.parseMaster(readFixture("hls/master.m3u8"), base)
        val clear = HlsParser.parseMedia(readFixture("hls/media_vod.m3u8"), base)
        val aes = HlsParser.parseMedia(readFixture("hls/media_aes.m3u8"), base)
        val drm = HlsParser.parseMedia(readFixture("hls/drm_widevine.m3u8"), base)

        assertEquals(EncryptionStatus.Clear, DrmDetector.checkStream(master, clear))
        assertEquals(EncryptionStatus.Aes128, DrmDetector.checkStream(master, clear, aes))
        assertEquals(EncryptionStatus.Drm("Widevine"), DrmDetector.checkStream(master, aes, drm), "e.g. DRM audio rendition")
    }
}
