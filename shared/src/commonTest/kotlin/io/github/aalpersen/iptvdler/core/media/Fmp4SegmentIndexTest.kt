package io.github.aalpersen.iptvdler.core.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Fmp4SegmentIndexTest {

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        return u32(size.toLong()) + type.encodeToByteArray() + payload
    }

    private fun u32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    /** tkhd v0 (track_ID 7) + mdhd v0 (timescale 90000). */
    private val init = box("ftyp", u32(0)) + box(
        "moov",
        box("trak", box("tkhd", u32(0) + u32(0) + u32(0) + u32(7)) + box("mdia", box("mdhd", u32(0) + u32(0) + u32(0) + u32(90_000)))),
    )

    private fun fragment(baseTime: Long) =
        box("moof", box("traf", box("tfhd", u32(0)) + box("tfdt", byteArrayOf(1, 0, 0, 0) + u32(baseTime ushr 32) + u32(baseTime))))

    @Test
    fun buildsIndexWithSizesAndDurationsFromTfdt() {
        assertEquals(900_000, Mp4Boxes.baseDecodeTime(fragment(900_000)))
        val sidx = Fmp4SegmentIndex.build(
            init,
            listOf(
                Fmp4SegmentIndex.SegmentInfo(1_000, 900_000),
                Fmp4SegmentIndex.SegmentInfo(2_000, 1_440_000), // 6 s later
                Fmp4SegmentIndex.SegmentInfo(1_500, 1_980_000),
            ),
            lastDurationSeconds = 4.0,
        )!!

        fun u(i: Int) = Mp4Boxes.readUInt32(sidx, i)
        assertEquals(sidx.size.toLong(), u(0))
        assertEquals("sidx", sidx.decodeToString(4, 8))
        assertEquals(1L shl 24, u(8), "version 1")
        assertEquals(7, u(12), "reference_ID = first track")
        assertEquals(90_000, u(16), "timescale")
        assertEquals(900_000, Mp4Boxes.readUInt64(sidx, 20), "earliest presentation time")
        assertEquals(0, Mp4Boxes.readUInt64(sidx, 28), "first_offset")
        assertEquals(3, u(36) and 0xFFFF, "reference_count")
        val refs = (0 until 3).map { i -> Triple(u(40 + i * 12), u(44 + i * 12), u(48 + i * 12)) }
        assertEquals(
            listOf(Triple(1_000L, 540_000L, 0x90000000L), Triple(2_000L, 540_000L, 0x90000000L), Triple(1_500L, 360_000L, 0x90000000L)),
            refs,
        )
    }

    @Test
    fun refusesWhatItCannotIndex() {
        assertNull(Fmp4SegmentIndex.build(box("ftyp", u32(0)), listOf(Fmp4SegmentIndex.SegmentInfo(10, 0)), 1.0), "no moov")
        assertNull(Fmp4SegmentIndex.build(init, emptyList(), 1.0))
        assertNull(
            Fmp4SegmentIndex.build(init, listOf(Fmp4SegmentIndex.SegmentInfo(10, 500), Fmp4SegmentIndex.SegmentInfo(10, 400)), 1.0),
            "time going backwards",
        )
    }
}
