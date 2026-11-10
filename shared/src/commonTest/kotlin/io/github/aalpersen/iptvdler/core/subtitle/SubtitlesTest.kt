package io.github.aalpersen.iptvdler.core.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SubtitlesTest {

    @Test
    fun parsesWebVttWithIdsSettingsNotesAndShortTimestamps() {
        val doc = WebVtt.parse(
            "﻿WEBVTT - some title\r\nX-TIMESTAMP-MAP=MPEGTS:900000,LOCAL:00:00:00.000\r\n\r\n" +
                "NOTE this is a comment\r\nspanning lines\r\n\r\n" +
                "STYLE\r\n::cue { color: yellow }\r\n\r\n" +
                "intro\r\n00:01.000 --> 00:02.500 align:start position:10%\r\n<i>Hello</i>\r\nworld &amp; you\r\n\r\n" +
                "01:02:03.004 --> 01:02:04.5\r\nSecond\r\n",
        )
        assertEquals(TimestampMap(900_000, 0), doc.timestampMap)
        assertEquals(
            listOf(
                Cue(1_000, 2_500, "<i>Hello</i>\nworld &amp; you", "align:start position:10%", "intro"),
                Cue(3_723_004, 3_724_500, "Second"),
            ),
            doc.cues,
        )
    }

    @Test
    fun rejectsFilesWithoutHeader() {
        assertFailsWith<SubtitleParseException> { WebVtt.parse("00:01.000 --> 00:02.000\nx") }
    }

    @Test
    fun writesVttAndSrt() {
        val cues = listOf(
            Cue(1_000, 2_500, "<v Roger>Hi <c.loud>there</c></v>\n<i>&lt;music&gt;</i> &amp; more", "line:0"),
            Cue(3_723_004, 3_724_000, "Bye"),
        )
        assertEquals(
            "WEBVTT\n\n00:00:01.000 --> 00:00:02.500 line:0\n<v Roger>Hi <c.loud>there</c></v>\n<i>&lt;music&gt;</i> &amp; more\n\n" +
                "01:02:03.004 --> 01:02:04.000\nBye\n\n",
            WebVtt.write(cues),
        )
        assertEquals(
            "1\n00:00:01,000 --> 00:00:02,500\nRoger: Hi there\n<i><music></i> & more\n\n" +
                "2\n01:02:03,004 --> 01:02:04,000\nBye\n\n",
            SubRip.write(cues),
        )
    }

    @Test
    fun parsesSrtAndConvertsToVttSafely() {
        val cues = SubRip.parse(
            "1\r\n00:00:01,000 --> 00:00:02,000 X1:10 X2:20\r\n<font color=\"red\">Tom & Jerry</font>\r\n<b>1 < 2</b>\r\n\r\n" +
                "2\r\n00:00:03,500 --> 00:00:04,000\r\nNext\r\n",
        )
        assertEquals(
            listOf(Cue(1_000, 2_000, "Tom &amp; Jerry\n<b>1 &lt; 2</b>"), Cue(3_500, 4_000, "Next")),
            cues,
        )
        // Round trip back to SRT restores the plain text.
        assertEquals("1\n00:00:01,000 --> 00:00:02,000\nTom & Jerry\n<b>1 < 2</b>\n\n", SubRip.write(cues.take(1)))
    }

    @Test
    fun detectsStandaloneFormat() {
        assertEquals(1, SubtitleFiles.parse("WEBVTT\n\n00:01.000 --> 00:02.000\nA\n").size)
        assertEquals(1, SubtitleFiles.parse("1\n00:00:01,000 --> 00:00:02,000\nA\n").size)
        assertFailsWith<SubtitleParseException> { SubtitleFiles.parse("<html>not subtitles</html>") }
    }

    private fun segment(mpegTs: Long?, local: String, vararg cues: String) = buildString {
        append("WEBVTT\n")
        if (mpegTs != null) append("X-TIMESTAMP-MAP=MPEGTS:$mpegTs,LOCAL:$local\n")
        append('\n')
        cues.forEach { append(it).append("\n\n") }
    }

    @Test
    fun mergesSegmentsOntoVideoTimeline() {
        // Video starts at PTS 900000 (10 s). Segment 1 maps LOCAL 0 to 10 s; segment 2 maps LOCAL 10 s to 20 s.
        val cues = VttSegmentMerger.merge(
            listOf(
                segment(900_000, "00:00:00.000", "00:00:01.000 --> 00:00:02.000\nOne"),
                segment(1_800_000, "00:00:10.000", "00:00:12.000 --> 00:00:13.000\nTwo"),
            ),
            videoStartPts = 900_000,
        )
        assertEquals(listOf(Cue(1_000, 2_000, "One"), Cue(12_000, 13_000, "Two")), cues)
    }

    @Test
    fun subtitleTimelineOffsetFromVideoStartIsApplied() {
        // Subtitles map LOCAL 0 to 10 s, but the video starts at 9.5 s: cues shift 0.5 s later.
        val cues = VttSegmentMerger.merge(
            listOf(segment(900_000, "00:00:00.000", "00:00:01.000 --> 00:00:02.000\nA")),
            videoStartPts = 855_000,
        )
        assertEquals(listOf(Cue(1_500, 2_500, "A")), cues)
    }

    @Test
    fun handles33BitRollover() {
        val nearWrap = VttSegmentMerger.PTS_WRAP - 90_000 // 1 s before wrap
        val cues = VttSegmentMerger.merge(
            listOf(
                segment(nearWrap, "00:00:00.000", "00:00:00.500 --> 00:00:00.900\nBefore"),
                segment(90_000, "00:00:00.000", "00:00:00.500 --> 00:00:00.900\nAfter"), // 1 s after wrap
            ),
            videoStartPts = nearWrap,
        )
        assertEquals(listOf(Cue(500, 900, "Before"), Cue(2_500, 2_900, "After")), cues)
        assertEquals(180_000, VttSegmentMerger.ptsDelta(from = nearWrap, to = 90_000))
        assertEquals(-180_000, VttSegmentMerger.ptsDelta(from = 90_000, to = nearWrap))
    }

    @Test
    fun usesFirstSegmentMapWhenVideoStartUnknownAndDropsDuplicates() {
        val cues = VttSegmentMerger.merge(
            listOf(
                segment(900_000, "00:00:00.000", "00:00:03.000 --> 00:00:07.000\nSpans segments"),
                segment(900_000, "00:00:00.000", "00:00:03.000 --> 00:00:07.000\nSpans segments", "00:00:08.000 --> 00:00:09.000\nLater"),
                "garbage that is not vtt",
            ),
            videoStartPts = null,
        )
        assertEquals(listOf(Cue(3_000, 7_000, "Spans segments"), Cue(8_000, 9_000, "Later")), cues)
    }

    @Test
    fun segmentsWithoutMapKeepTheirTimesAndNegativeCuesAreClamped() {
        val cues = VttSegmentMerger.merge(
            listOf(
                segment(null, "", "00:00:05.000 --> 00:00:06.000\nNo map"),
                segment(0, "00:00:00.000", "00:00:00.000 --> 00:00:01.000\nBefore video", "00:00:00.500 --> 00:00:02.000\nStraddles"),
            ),
            videoStartPts = 90_000, // video starts 1 s after subtitle time 0
        )
        assertEquals(listOf(Cue(0, 1_000, "Straddles"), Cue(5_000, 6_000, "No map")), cues)
    }

    @Test
    fun readsFirstPtsFromMpegTsPackets() {
        fun pesPacket(streamId: Int, pts: Long): ByteArray {
            val p = ByteArray(188) { 0xFF.toByte() }
            p[0] = 0x47; p[1] = 0x41; p[2] = 0x00; p[3] = 0x10 // PUSI, payload only
            val pes = byteArrayOf(0, 0, 1, streamId.toByte(), 0, 0, 0x80.toByte(), 0x80.toByte(), 5) + encodePts(pts)
            pes.copyInto(p, 4)
            return p
        }
        val psi = ByteArray(188).also { it[0] = 0x47; it[1] = 0x40; it[3] = 0x10 } // PAT-like packet without PES
        val data = psi + pesPacket(0xE0, 1_800_000) + pesPacket(0xC0, 1_796_400) + pesPacket(0xE0, 1_803_000)
        assertEquals(1_796_400, MediaTimestamps.firstTsPts(data))
        assertEquals(8_589_000_000, MediaTimestamps.firstTsPts(pesPacket(0xE0, 8_589_000_000)), "33-bit values")
        assertNull(MediaTimestamps.firstTsPts(ByteArray(100)))
    }

    private fun encodePts(pts: Long): ByteArray = byteArrayOf(
        (0x21 or ((pts shr 29) and 0x0E).toInt()).toByte(),
        (pts shr 22).toByte(),
        (((pts shr 14) and 0xFE) or 1).toByte(),
        (pts shr 7).toByte(),
        (((pts shl 1) and 0xFE) or 1).toByte(),
    )

    @Test
    fun readsFirstPtsFromFmp4Boxes() {
        fun box(type: String, payload: ByteArray): ByteArray {
            val size = 8 + payload.size
            return byteArrayOf((size shr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte()) +
                type.encodeToByteArray() + payload
        }
        fun u32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

        // mdhd v0: version/flags, creation, modification, timescale=12800, duration, lang
        val mdhd = box("mdhd", u32(0) + u32(0) + u32(0) + u32(12_800) + u32(0) + u32(0))
        val init = box("ftyp", u32(0)) + box("moov", box("trak", box("tkhd", u32(0)) + box("mdia", mdhd)))
        // tfdt v1: version/flags then 64-bit base media decode time = 1444 (0.1128 s at 12800)
        val tfdt = box("tfdt", byteArrayOf(1, 0, 0, 0) + u32(0) + u32(1_444))
        val segment = box("styp", u32(0)) + box("moof", box("mfhd", u32(0)) + box("traf", box("tfhd", u32(0)) + tfdt))

        assertEquals(1_444L * 90_000 / 12_800, MediaTimestamps.firstFmp4Pts(init, segment))
        assertNull(MediaTimestamps.firstFmp4Pts(init, box("mdat", u32(0))))
    }
}
