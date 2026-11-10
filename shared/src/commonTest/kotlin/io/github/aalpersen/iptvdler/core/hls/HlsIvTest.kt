package io.github.aalpersen.iptvdler.core.hls

import kotlin.test.Test
import kotlin.test.assertContentEquals

class HlsIvTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun derivesIvFromMediaSequenceBigEndian() {
        assertContentEquals(ByteArray(16), HlsIv.fromSequence(0))
        assertContentEquals(
            "00000000000000000000000000001e72".chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            HlsIv.fromSequence(7794),
        )
        assertContentEquals(
            ByteArray(8) + byteArrayOf(0x7F, -1, -1, -1, -1, -1, -1, -1),
            HlsIv.fromSequence(Long.MAX_VALUE),
        )
        assertContentEquals(
            ByteArray(8) + byteArrayOf(0x01, 0x23, 0x45, 0x67, -0x77, -0x55, -0x33, -0x11),
            HlsIv.fromSequence(0x0123456789ABCDEFL),
        )
    }

    @Test
    fun explicitIvWinsOverSequence() {
        val key = HlsKey(KeyMethod.AES_128, "k", ivHex = "000102030405060708090a0b0c0d0e0f")
        assertContentEquals(ByteArray(16) { it.toByte() }, HlsIv.forSegment(key, mediaSequence = 99))
    }

    @Test
    fun missingIvUsesSegmentSequence() {
        val key = HlsKey(KeyMethod.AES_128, "k")
        kotlin.test.assertEquals("00000000000000000000000000000063", hex(HlsIv.forSegment(key, mediaSequence = 99)))
    }

    @Test
    fun parsedPlaylistIvsMatchSpec() {
        val playlist = HlsParser.parseMedia(io.github.aalpersen.iptvdler.readFixture("hls/media_aes.m3u8"), "https://h.test/p.m3u8")
        val ivs = playlist.segments.mapNotNull { segment -> segment.aes128Key?.let { hex(HlsIv.forSegment(it, segment.mediaSequence)) } }
        kotlin.test.assertEquals(
            listOf(
                "00000000000000000000000000001e72", // 7794, derived
                "00000000000000000000000000001e73", // 7795, derived
                "000102030405060708090a0b0c0d0e0f", // explicit
                "0000000000000000000000000000001f", // explicit, short
            ),
            ivs,
        )
    }
}
